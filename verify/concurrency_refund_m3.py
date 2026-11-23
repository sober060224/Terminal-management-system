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

# 本轮自己创建的订单，退出时清掉，库才不会越跑越脏（P2-7）。
CREATED_ORDERS = []
import atexit
from m3_teardown import teardown as teardown_orders

atexit.register(lambda: teardown_orders(CREATED_ORDERS))
TRIPLE_ROUNDS = int(os.environ.get("TRIPLE_ROUNDS", "5"))
USER = ("passenger1", "123456")

# 身份证带 GB 11643 校验位，序号每次不同，避免上一轮没退成的票触发一人一票
_ID_SEQ = int(time.time()) % 60000
_PHONE_SEQ = int(time.time()) % 10000
ID_WEIGHTS = (7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
ID_CHECK = "10X98765432"


def next_id_card():
    global _ID_SEQ
    _ID_SEQ = (_ID_SEQ + 1) % 100000
    body = "440100199001%05d" % _ID_SEQ
    return body + ID_CHECK[sum(int(d) * w for d, w in zip(body, ID_WEIGHTS)) % 11]


def next_phone():
    global _PHONE_SEQ
    _PHONE_SEQ = (_PHONE_SEQ + 1) % 10000
    return "138%08d" % _PHONE_SEQ


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


def future_schedule_with_seats(client, need, exclude_id=None, price=None, horizon_hours=2):
    """挑一个 2 小时以后发船、且至少有 need 个可选座位的班次（退了才是全额/扣 20% 可预期）。"""
    status, body = client.get("/api/schedules")
    assert status == 200 and body.get("data"), "没有售票中的班次，先跑 verify/m3-seed.sql"
    horizon = datetime.datetime.now() + datetime.timedelta(hours=horizon_hours)
    for schedule in body["data"]:
        if exclude_id is not None and schedule["id"] == exclude_id:
            continue
        if price is not None and abs(float(schedule["price"]) - price) > 0.001:
            continue
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
             "idCard": next_id_card(), "phone": next_phone()},
            {"seatNo": seats[1]["seatNo"], "passengerName": "并发退%dB" % index,
             "idCard": next_id_card(), "phone": next_phone()},
        ],
    }
    status, body = buyer.post("/api/orders", payload)
    assert status == 200, "下单失败 %s %s" % (status, body)
    order_id = body["data"]["id"]
    CREATED_ORDERS.append(order_id)
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


def run_triple(index):
    """同一订单上并发发起 退票 / 取消 / 改签，校验状态与现金流。

    注意：订单模型限制同一订单的票都在同一班次，所以这里只能测「同订单同班次」
    的并发，跨班次同订单并发无法构造（已登记 docs/deviations.md）。
    取消只对 待支付 生效，因此已支付订单上唯一合法的取消结果是 409。
    """
    buyer = login()
    schedule, seats = future_schedule_with_seats(buyer, 2, horizon_hours=1)
    price = float(schedule["price"])
    status, body = buyer.post("/api/orders", {
        "scheduleId": schedule["id"],
        "seats": [
            {"seatNo": seats[0]["seatNo"], "passengerName": "三方%dA" % index,
             "idCard": next_id_card(), "phone": next_phone()},
            {"seatNo": seats[1]["seatNo"], "passengerName": "三方%dB" % index,
             "idCard": next_id_card(), "phone": next_phone()},
        ],
    })
    assert status == 200, "下单失败 %s %s" % (status, body)
    order_id = body["data"]["id"]
    CREATED_ORDERS.append(order_id)
    status, body = buyer.post("/api/orders/%d/pay" % order_id)
    assert status == 200, "支付失败 %s %s" % (status, body)
    status, order = buyer.get("/api/orders/%d" % order_id)
    assert status == 200 and len(order["data"]["tickets"]) == 2, order
    order = order["data"]
    initial = float(order["totalAmount"])
    first, second = order["tickets"]

    # 改签目标必须同航线、同票价（差价 0，才能用「总额 = 初始 − Σ实退」这一条等式）
    target, target_seats = future_schedule_with_seats(
        buyer, 1, exclude_id=schedule["id"], price=price, horizon_hours=1)

    clients = [login() for _ in range(3)]
    barrier = threading.Barrier(3)
    results = {}
    lock = threading.Lock()

    def act(slot, name, path, payload=None):
        barrier.wait()
        status, body = clients[slot].post(path, payload)
        with lock:
            results[name] = (status, body)

    threads = [
        threading.Thread(target=act, args=(0, "refund", "/api/tickets/%d/refund" % first["id"])),
        threading.Thread(target=act, args=(1, "cancel", "/api/orders/%d/cancel" % order_id)),
        threading.Thread(target=act, args=(2, "rebook", "/api/tickets/%d/rebook" % second["id"],
                                           {"targetScheduleId": target["id"],
                                            "targetSeatId": target_seats[0]["seatId"]})),
    ]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    if results["cancel"][0] != 409:
        return "第 %d 轮已支付订单取消返回 %d，应为 409" % (index, results["cancel"][0])
    if results["refund"][0] != 200:
        return "第 %d 轮并发中退票返回 %d %s" % (index, results["refund"][0],
                                                 results["refund"][1].get("message"))
    if results["rebook"][0] != 200:
        return "第 %d 轮并发中改签返回 %d %s" % (index, results["rebook"][0],
                                                 results["rebook"][1].get("message"))

    status, body = buyer.get("/api/orders/%d" % order_id)
    if status != 200:
        return "第 %d 轮订单详情失败 %s" % (index, status)
    order = body["data"]
    refunded = 0.0
    for ticket in order["tickets"]:
        amount = ticket.get("refundAmount")
        if ticket["status"] == "已退票":
            if amount is None:
                return "第 %d 轮已退票但没有实退额：%s" % (index, ticket)
            refunded += float(amount)
        elif amount not in (None, 0, 0.0):
            return "第 %d 轮未退票却记了实退额：%s" % (index, ticket)
    expected = round(initial - refunded, 2)
    if abs(float(order["totalAmount"]) - expected) > 0.001:
        return "第 %d 轮现金流不自洽：总额 %s，应为 %s（初始 %s，Σ实退 %s）" % (
            index, order["totalAmount"], expected, initial, refunded)
    if order["status"] != "已支付":
        return "第 %d 轮订单状态是 %s，应为 已支付" % (index, order["status"])
    return None


def main():
    problems = []
    for index in range(ROUNDS):
        problem = run_round(index)
        print(("  PASS 第 %d 轮" % index) if problem is None else ("  FAIL " + problem))
        if problem:
            problems.append(problem)

    for index in range(TRIPLE_ROUNDS):
        problem = run_triple(index)
        print(("  PASS 三方并发第 %d 轮" % index) if problem is None else ("  FAIL " + problem))
        if problem:
            problems.append(problem)

    print("\n并发退票 %d 轮 + 三方并发 %d 轮，失败 %d 轮"
          % (ROUNDS, TRIPLE_ROUNDS, len(problems)))
    if problems:
        for problem in problems[:5]:
            print("  -", problem)
        raise SystemExit(1)
    print("全部通过")


if __name__ == "__main__":
    main()
