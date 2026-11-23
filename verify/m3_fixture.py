"""浏览器验收（m3-browser.mjs）的前置数据，纯 HTTP，不依赖库里预留订单。

注册两个全新乘客并登录，取同航线的两个未发船班次，乘客 A 在其中一班买 2 张票并支付，
最后向 stdout 打印一行 JSON：账号、订单号、票号、改签目标班次与座位。
"""

import http.cookiejar
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta

BASE = os.environ.get("FERRY_BASE", "http://localhost:8089")
STAMP = str(int(time.time() * 1000))[-8:]
PASSWORD = "123456"
ID_WEIGHTS = (7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
ID_CHECK = "10X98765432"


def id_card(index):
    """GB 11643 校验位，index 不同即身份证不同。"""
    body = "440100199001%05d" % (int(time.time()) % 60000 + index)
    return body + ID_CHECK[sum(int(d) * w for d, w in zip(body, ID_WEIGHTS)) % 11]


def phone(index):
    """合法手机号：1[3-9] 开头共 11 位。"""
    return "138%08d" % (int(time.time()) % 10000 + index)


def fail(message):
    print(f"[fixture] {message}", file=sys.stderr)
    raise SystemExit(1)


class Client:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def cookie(self, name):
        for c in self.jar:
            if c.name == name:
                return c.value
        return None

    def seed_csrf(self):
        self.get_json("/api/auth/csrf")

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


def register(index, name):
    username = "b" + STAMP + f"{index:02d}"
    client = Client()
    client.seed_csrf()
    status, body = client.post("/api/auth/register", {
        "username": username,
        "password": PASSWORD,
        "name": name,
        "phone": phone(index),
        "idCard": id_card(index),
    })
    if status != 200:
        fail(f"注册失败 {username}: {status} {body}")
    return username


def login(username):
    client = Client()
    client.seed_csrf()
    status, body = client.post("/api/auth/login", {"username": username, "password": PASSWORD})
    if status != 200:
        fail(f"登录失败 {username}: {status} {body}")
    return client


def future_schedules(client, need_seats):
    status, body = client.get_json("/api/schedules")
    if status != 200:
        fail(f"班次列表失败: {status} {body}")
    soon = datetime.now() + timedelta(hours=1)
    by_route = {}
    for s in body["data"]:
        if datetime.fromisoformat(s["departTime"]) <= soon:
            continue
        if int(s.get("remainingSeats") or 0) < need_seats:
            continue
        by_route.setdefault(s["routeId"], []).append(s)
    pairs = [sorted(v, key=lambda s: s["departTime"]) for v in by_route.values() if len(v) >= 2]
    if not pairs:
        fail("没有同航线、1 小时以后发船、余票 ≥ 2 的两个班次，先运行 verify/m3-seed.sql")
    return pairs[0][0], pairs[0][1]


def available_seats(client, schedule_id, count):
    status, body = client.get_json(f"/api/schedules/{schedule_id}/seats")
    if status != 200:
        fail(f"座位列表失败 schedule={schedule_id}: {status} {body}")
    seats = [s for s in body["data"]["seats"] if s["state"] == "可选"]
    if len(seats) < count:
        fail(f"班次 {schedule_id} 可选座位不足 {count} 个")
    return seats[:count]


def teardown(client, username, order_id):
    """中途失败时退掉已支付的订单，避免给浏览器验收留下脏数据（审计 §4）。"""
    try:
        status, body = client.get_json("/api/orders/mine")
        if status != 200:
            return
        order = next((o for o in body["data"] or [] if o["id"] == order_id), None)
        if not order:
            return
        for ticket in order.get("tickets") or []:
            if ticket.get("status") in ("已支付", "已检票"):
                client.post(f"/api/tickets/{ticket["id"]}/refund", {"reason": "其他"})
        status, body = client.get_json("/api/orders/mine")
        order = next((o for o in body["data"] or [] if o["id"] == order_id), None)
        if order and order.get("status") == "已退票":
            print(f"[fixture] 已清理测试订单 {order_id}（整单退票）", file=sys.stderr)
    except Exception as e:
        print(f"[fixture] 清理失败（不影响验收结果，重跑可复用同一订单）: {e}", file=sys.stderr)

def main():
    username = None
    order_id = None
    client = None
    try:
        username = register(1, "浏览器甲")
        register(2, "浏览器乙")
        client = login(username)

        schedule, target = future_schedules(client, 2)
        seats = available_seats(client, schedule["id"], 2)
        status, body = client.post("/api/orders", {
            "scheduleId": schedule["id"],
            "seats": [
                {"seatNo": s["seatNo"], "passengerName": "浏览器甲", "idCard": id_card(10 + n),
                 "phone": phone(10 + n)}
                for n, s in enumerate(seats)
            ],
        })
        if status != 200:
            fail(f"下单失败: {status} {body}")
        order_id = body["data"]["id"]

        status, body = client.post(f"/api/orders/{order_id}/pay")
        if status != 200:
            fail(f"支付失败: {status} {body}")

        status, body = client.get_json("/api/orders/mine")
        if status != 200:
            fail(f"订单列表失败: {status} {body}")
        order = next((o for o in body["data"] if o["id"] == order_id), None)
        if order is None:
            fail(f"订单 {order_id} 不在 /api/orders/mine")

        target_seat = available_seats(client, target["id"], 1)[0]
        print(json.dumps({
            "username": username,
            "password": PASSWORD,
            "orderId": order_id,
            "ticketId": order["tickets"][0]["id"],
            "targetScheduleId": target["id"],
            "targetDepartTime": target["departTime"],
            "targetSeatId": target_seat["seatId"],
            "routeId": schedule["routeId"],
        }, ensure_ascii=False))
    except BaseException:
        if client and order_id:
            teardown(client, username, order_id)
        raise


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    main()
