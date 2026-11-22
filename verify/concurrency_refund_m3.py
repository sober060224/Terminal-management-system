# -*- coding: utf-8 -*-
"""
模块三并发退票验收：同一张订单的两张票被两个请求同时退掉，
订单总额必须正好等于「票面合计 - 两笔实退」，不能出现丢失更新。

没有订单行锁时，两个事务各自读到旧的 total_amount 再回写，
最终总额会是 120 或 160 而不是 40 —— 这个脚本就是抓这个。

用法：先跑 verify/m3-seed.sql，起后端 8089，然后
    python verify/concurrency_refund_m3.py
可用 FERRY_BASE / ROUNDS 覆盖。
"""
import datetime
import http.cookiejar
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

BASE = os.environ.get("FERRY_BASE", "http://localhost:8089")
ROUNDS = int(os.environ.get("ROUNDS", "10"))
USER = ("passenger1", "123456")
ID_PREFIX = "44010019900201"
# 每跑一次换一个 2 位跑次标记，避免上一轮没退成的票触发一人一票（id_card 共 18 位）
RUN = str(int(time.time()))[-2:]


class Client:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def _csrf(self):
        for cookie in self.jar:
            if cookie.name == "XSRF-TOKEN":
                return cookie.value
        return None

    def call(self, method, path, body=None):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(BASE + path, data=data, method=method)
        request.add_header("Content-Type", "application/json;charset=UTF-8")
        if method != "GET":
            token = self._csrf()
            if token:
                request.add_header("X-XSRF-TOKEN", token)
        try:
            with self.opener.open(request) as response:
                return response.status, json.loads(response.read() or b"{}")
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read() or b"{}")

    def get(self, path):
        return self.call("GET", path)

    def post(self, path, body=None):
        return self.call("POST", path, body)


def login():
    client = Client()
    client.get("/api/auth/csrf")
    status, body = client.post("/api/auth/login", {"username": USER[0], "password": USER[1]})
    assert status == 200, "登录失败 %s %s" % (status, body)
    return client


def future_schedule_with_seats(client, need):
    """挑一个 2 小时以后发船、且至少有 need 个可选座位的班次（退了才是全额/扣 20% 可预期）。"""
    status, body = client.get("/api/schedules")
    assert status == 200 and body.get("data"), "没有售票中的班次，先跑 verify/m3-seed.sql"
    horizon = datetime.datetime.now() + datetime.timedelta(hours=2)
    for schedule in body["data"]:
        if not schedule["departTime"]:
            continue
        # 只挑发船在 2 小时之后的班次，否则退票会走手续费档 / 直接被拒
        try:
            if datetime.datetime.fromisoformat(schedule["departTime"]) <= horizon:
                continue
        except ValueError:
            continue
        status, seats = client.get("/api/schedules/%d/seats" % schedule["id"])
        if status != 200:
            continue
        free = [s for s in seats["data"]["seats"] if s["state"] == "可选"]
        if len(free) >= need:
            return schedule, free[:need]
    raise SystemExit("找不到有 %d 个空座的班次" % need)


def run_round(index):
    """一轮：下单两张票 → 支付 → 两个线程同时退掉这两张票 → 校验订单总额。"""
    buyer = login()
    schedule, seats = future_schedule_with_seats(buyer, 2)
    payload = {
        "scheduleId": schedule["id"],
        "seats": [
            {"seatNo": seats[0]["seatNo"], "passengerName": "并发退%dA" % index,
             "idCard": ID_PREFIX + RUN + "1" + str(index)},
            {"seatNo": seats[1]["seatNo"], "passengerName": "并发退%dB" % index,
             "idCard": ID_PREFIX + RUN + "2" + str(index)},
        ],
    }
    status, body = buyer.post("/api/orders", payload)
    assert status == 200, "下单失败 %s %s" % (status, body)
    order_id = body["data"]["id"]
    status, body = buyer.post("/api/orders/%d/pay" % order_id)
    assert status == 200, "支付失败 %s %s" % (status, body)
    # 支付接口回的不是订单视图，重新拉一次列表拿票号
    status, listing = buyer.get("/api/orders/mine")
    assert status == 200, listing
    order = next(o for o in listing["data"] if o["id"] == order_id)
    ticket_ids = [t["id"] for t in order["tickets"]]
    price = float(order["tickets"][0]["price"])
    assert len(ticket_ids) == 2, "订单没有两张票：%s" % order

    clients = [login(), login()]
    barrier = threading.Barrier(2)
    results = []
    lock = threading.Lock()

    def refund(slot):
        barrier.wait()
        status, body = clients[slot].post("/api/tickets/%d/refund" % ticket_ids[slot])
        with lock:
            results.append((slot, status, body))

    threads = [threading.Thread(target=refund, args=(slot,)) for slot in (0, 1)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    bad = [r for r in results if r[1] != 200]
    if bad:
        return "第 %d 轮退票返回异常 %s" % (index, [(s, b.get("message")) for _, s, b in bad])

    status, body = buyer.get("/api/orders/mine")
    assert status == 200, body
    order = next((o for o in body["data"] if o["id"] == order_id), None)
    if order is None:
        return "第 %d 轮订单不见了" % index

    # 距发船 2 小时以上 → 全额退，总额 = 2*price - 2*price = 0；扣 20% 时是 2*price*0.2
    refunded = order["tickets"][0]["refundAmount"]
    expected = round(2 * price - 2 * float(refunded), 2)
    if abs(float(order["totalAmount"]) - expected) > 0.001:
        return ("第 %d 轮丢失更新：总额 %s，应为 %s（两笔实退 %s）"
                % (index, order["totalAmount"], expected, refunded))
    if order["status"] != "已退票":
        return "第 %d 轮订单状态是 %s，应为 已退票" % (index, order["status"])
    return None


def main():
    problems = []
    for index in range(ROUNDS):
        problem = run_round(index)
        print(("  PASS 第 %d 轮" % index) if problem is None else ("  FAIL " + problem))
        if problem:
            problems.append(problem)

    print("\n并发退票 %d 轮，失败 %d 轮" % (ROUNDS, len(problems)))
    if problems:
        for problem in problems[:5]:
            print("  -", problem)
        raise SystemExit(1)
    print("全部通过")


if __name__ == "__main__":
    main()