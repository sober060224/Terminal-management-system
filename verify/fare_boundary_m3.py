"""退票手续费秒级边界实测（P3-1）：1:59:59 / 2:00:00 / 2:00:01 三个真实时刻。

做法：验收库与后端同实例，脚本直接把 schedule.depart_time 改成 NOW()+N 秒，
再走真实 HTTP 下单 / 支付 / 退票，断言实退金额：
  距发船 > 2h   → 全额退 100
  距发船 <= 2h  → 扣 20% 退 80
边界正好落在秒上，靠 TIMESTAMPDIFF 对齐到目标秒；7201 那一档若被时钟跳秒吃掉
（退票时只剩 7199.x 秒），重试一次，其余两档出现全额退即为真 bug。
"""

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

from m3_teardown import _mysql_bin, teardown

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

BASE = os.environ.get("FERRY_BASE", "http://localhost:8089")
DB = os.environ.get("FERRY_DB", "ferry_system")
PORT = os.environ.get("DB_PORT", "3307")
USER = os.environ.get("DB_USER", "root")
PWD = os.environ.get("DB_PASSWORD", "")
PRICE = 100.0
CREATED_ORDERS = []
MYSQL = _mysql_bin()

failures = []


def check(label, actual, expected):
    ok = actual == expected
    print(("  PASS " if ok else "  FAIL ") + label + f"  (got {actual!r}, want {expected!r})")
    if not ok:
        failures.append(label)
    return ok


def sql(statement):
    """跑一条 SQL，返回 stdout 文本（-N -s 去掉表头与边框）。"""
    cmd = [MYSQL, "--protocol=TCP", "-h", "127.0.0.1", "-P", PORT, "-u", USER,
           "-p" + PWD, "--default-character-set=utf8mb4", "-N", "-s", "-e",
           f"USE {DB}; {statement}"]
    done = subprocess.run(cmd, capture_output=True)
    if done.returncode != 0:
        raise SystemExit("SQL 失败: " + statement + "\n" + done.stderr.decode("utf-8", "replace"))
    return done.stdout.decode("utf-8", "replace").strip()


class Client:
    def __init__(self):
        import http.cookiejar
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def cookie(self, name):
        for c in self.jar:
            if c.name == name:
                return c.value
        return None

    def _request(self, req):
        try:
            r = self.opener.open(req)
            raw = r.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            try:
                return e.code, json.loads(raw)
            except Exception:
                return e.code, raw
        try:
            return r.status, json.loads(raw)
        except Exception:
            return r.status, raw

    def get_json(self, path):
        return self._request(urllib.request.Request(BASE + path, method="GET"))

    def post(self, path, payload=None):
        data = json.dumps(payload or {}, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(BASE + path, data=data, method="POST")
        req.add_header("Content-Type", "application/json;charset=UTF-8")
        token = self.cookie("XSRF-TOKEN")
        if token:
            req.add_header("X-XSRF-TOKEN", token)
        return self._request(req)


def login(username, password):
    client = Client()
    client.get_json("/api/auth/csrf")
    status, body = client.post("/api/auth/login", {"username": username, "password": password})
    if status != 200:
        raise SystemExit(f"登录失败 {username}: {status} {body}")
    return client


def next_id_card():
    seq = int(time.time() * 1000) % 100000
    weights = (7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
    digits = "10X98765432"
    body = "440100199001%05d" % seq
    return body + digits[sum(int(d) * w for d, w in zip(body, weights)) % 11]


def free_seat(client, schedule_id):
    status, body = client.get_json(f"/api/schedules/{schedule_id}/seats")
    if status != 200:
        raise SystemExit(f"座位列表失败 schedule={schedule_id}: {status} {body}")
    for seat in body["data"]["seats"]:
        if seat["state"] == "可选":
            return seat
    raise AssertionError(f"班次 {schedule_id} 没有可选座位")


def run_case(client, schedule_id, offset):
    """把发船时间调到 NOW()+offset 秒，立即完成一次「下单-支付-退票」。"""
    for attempt in range(4):
        sql(f"UPDATE schedule SET depart_time = NOW() + INTERVAL {offset} SECOND, "
            f"arrive_time = NOW() + INTERVAL {offset + 7200} SECOND WHERE id = {schedule_id};")
        remaining = sql(f"SELECT TIMESTAMPDIFF(SECOND, NOW(), depart_time) FROM schedule WHERE id = {schedule_id};")
        if remaining != str(offset):
            continue  # 没对齐到目标秒，再来一次
        seat = free_seat(client, schedule_id)
        status, body = client.post("/api/orders", {
            "scheduleId": schedule_id,
            "seats": [{"seatNo": seat["seatNo"], "passengerName": "边界子",
                       "idCard": next_id_card(), "phone": "138" + str(int(time.time()) % 100000000).zfill(8)}],
        })
        if status != 200:
            raise SystemExit(f"边界下单失败 offset={offset}: {status} {body}")
        order_id = body["data"]["id"]
        CREATED_ORDERS.append(order_id)
        status, body = client.post(f"/api/orders/{order_id}/pay")
        if status != 200:
            raise SystemExit(f"边界支付失败 offset={offset}: {status} {body}")
        status, body = client.get_json("/api/orders/mine")
        ticket_id = None
        for order in body["data"] or []:
            if order["id"] == order_id:
                ticket_id = order["tickets"][0]["id"]
        if ticket_id is None:
            raise SystemExit(f"边界订单 {order_id} 不在列表里")
        status, body = client.post(f"/api/tickets/{ticket_id}/refund")
        if status != 200:
            raise SystemExit(f"边界退票失败 offset={offset}: {status} {body}")
        got = float(body["data"]["refundAmount"])
        # 7201 档若被时钟跳秒吃掉（退票时已不足 7200.0 秒）会退 80，重试
        if offset == 7201 and got == 80.0 and attempt < 3:
            print(f"  (offset=7201 第 {attempt + 1} 次跳秒，重试)")
            continue
        return got
    raise SystemExit(f"offset={offset} 连续 4 次没能落在目标秒")


def main():
    if not MYSQL:
        raise SystemExit("找不到 mysql 客户端：设置 FERRY_MYSQL_BIN 后重试")
    print("=== 退票手续费秒级边界（1:59:59 / 2:00:00 / 2:00:01）===")
    client = login("passenger1", "123456")
    # 专用班次：票价 100，座位由 trg_schedule_generate_seats 自动生成
    schedule_id = int(sql(
        "INSERT INTO schedule (route_id, ship_id, depart_time, arrive_time, price, total_seats, sold_seats, status) "
        f"VALUES (1, 1, NOW() + INTERVAL 3 DAY, NOW() + INTERVAL 3 DAY + INTERVAL 2 HOUR, {PRICE}, 32, 0, '售票中'); "
        "SELECT LAST_INSERT_ID();"))
    print(f"边界靶子班次 id={schedule_id} 票价={PRICE}")
    cases = [
        (7201, 100.0, "距发船 2:00:01（> 2h）全额退 100"),
        (7200, 80.0, "距发船 2:00:00（= 2h）扣 20% 退 80"),
        (7199, 80.0, "距发船 1:59:59（< 2h）扣 20% 退 80"),
    ]
    try:
        for offset, expect, label in cases:
            got = run_case(client, schedule_id, offset)
            check(label, got, expect)
    finally:
        if CREATED_ORDERS:
            teardown(CREATED_ORDERS)
        sql(f"DELETE FROM seat WHERE schedule_id = {schedule_id}; "
            f"DELETE FROM schedule WHERE id = {schedule_id};")

    print("\n=== 结果 ===")
    if failures:
        for name in failures:
            print("  -", name)
        raise SystemExit(1)
    print("全部通过")


if __name__ == "__main__":
    main()
