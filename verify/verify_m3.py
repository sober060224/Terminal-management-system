"""M3 端到端验收（能退改）：全部用 assert，可重复运行。

依赖 verify/m3-seed.sql 先造好相对 NOW() 的测试班次。断言覆盖：
  全额退 / 2h 内扣 20% / 发船后拒绝 / 改签补差价 / 改签退差额 /
  改签限同航线 / 乘客不能退别人的票 / 售票员代售代退 / 管理员看全部并代退。
"""

import http.cookiejar
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

BASE = os.environ.get("FERRY_BASE", "http://localhost:8089")
# 已发船班次不在 /api/schedules 里，m3-seed.sql 用 price = 99.99 打标记，
# verify-m3.cmd 查出自增 id 后通过这个环境变量传进来。
PAST_SCHEDULE_ID = int(os.environ.get("FERRY_PAST_SCHEDULE_ID", "0") or "0")

# 身份证必须带 GB 11643 校验位，后端会校验；序号每次不同，避免一人一票判重。
_ID_SEQ = int(time.time() * 1000) % 100000
ID_WEIGHTS = (7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
ID_CHECK = "10X98765432"


def next_id_card():
    global _ID_SEQ
    _ID_SEQ = (_ID_SEQ + 1) % 100000
    body = "440100199001%05d" % _ID_SEQ
    return body + ID_CHECK[sum(int(d) * w for d, w in zip(body, ID_WEIGHTS)) % 11]


_PHONE_SEQ = int(time.time()) % 10000


def next_phone():
    """合法手机号：1[3-9] 开头共 11 位。"""
    global _PHONE_SEQ
    _PHONE_SEQ = (_PHONE_SEQ + 1) % 10000
    return "138%08d" % _PHONE_SEQ

# 本轮自己创建的订单 id，退出时统一清掉（P2-7）。
CREATED_ORDERS = []
import atexit
from m3_teardown import teardown as teardown_orders

atexit.register(lambda: teardown_orders(CREATED_ORDERS))

failures = []


def check(label, actual, expected):
    ok = actual == expected
    print(("  PASS " if ok else "  FAIL ") + label + f"  (got {actual!r}, want {expected!r})")
    if not ok:
        failures.append(label)
    return ok


def close(label, actual, expected, eps=0.001):
    ok = actual is not None and abs(float(actual) - float(expected)) < eps
    print(("  PASS " if ok else "  FAIL ") + label + f"  (got {actual!r}, want {expected!r})")
    if not ok:
        failures.append(label)
    return ok


def parse_time(value):
    return datetime.fromisoformat(value)


class Client:
    """带 Cookie 的浏览器替身（CSRF 流程同 verify_m0.py）。"""

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


def login(username, password):
    client = Client()
    client.seed_csrf()
    status, body = client.post("/api/auth/login", {"username": username, "password": password})
    if status != 200:
        raise SystemExit(f"登录失败 {username}: {status} {body}")
    return client


def list_schedules(client):
    status, body = client.get_json("/api/schedules")
    if status != 200:
        raise SystemExit(f"班次列表失败: {status} {body}")
    return body["data"]


def free_seat(client, schedule_id):
    status, body = client.get_json(f"/api/schedules/{schedule_id}/seats")
    if status != 200:
        raise SystemExit(f"座位列表失败 schedule={schedule_id}: {status} {body}")
    for seat in body["data"]["seats"]:
        if seat["state"] == "可选":
            return seat
    raise AssertionError(f"班次 {schedule_id} 没有可选座位")


def free_seats(client, schedule_id, count):
    status, body = client.get_json(f"/api/schedules/{schedule_id}/seats")
    if status != 200:
        raise SystemExit(f"座位列表失败 schedule={schedule_id}: {status} {body}")
    free = [s for s in body["data"]["seats"] if s["state"] == "可选"]
    if len(free) < count:
        raise AssertionError(f"班次 {schedule_id} 可选座位不足 {count} 个")
    return free[:count]


def order_count(client):
    status, body = client.get_json("/api/orders/mine")
    assert status == 200, (status, body)
    return len(body["data"] or [])


def create_and_pay(client, list_path, schedule_id, name, id_card, create_path="/api/orders",
                   phone=None):
    phone = phone or next_phone()
    seat = free_seat(client, schedule_id)
    status, body = client.post(create_path, {
        "scheduleId": schedule_id,
        "seats": [{"seatNo": seat["seatNo"], "passengerName": name, "idCard": id_card, "phone": phone}],
    })
    check(f"下单 {name} 200", status, 200)
    order_id = body["data"]["id"]
    CREATED_ORDERS.append(order_id)
    status, body = client.post(f"{create_path}/{order_id}/pay")
    check(f"支付 {name} 200", status, 200)
    return order_id, seat


def find_order(client, list_path, order_id):
    status, body = client.get_json(list_path)
    assert status == 200, (status, body)
    for order in body["data"]:
        if order["id"] == order_id:
            return order
    raise AssertionError(f"订单 {order_id} 不在 {list_path}")


def pick_schedules(schedules):
    """按 price + 发船时刻分类，取每类最大 id（即最新一批种子）。"""
    now = datetime.now()

    def pick(predicate):
        candidates = [
            s for s in schedules
            if s["routeId"] == 1
            and float(s["price"]) in (100.0, 120.0, 130.0)
            and predicate(s)
        ]
        return max(candidates, key=lambda s: s["id"]) if candidates else None

    full = pick(lambda s: float(s["price"]) == 100.0
                and parse_time(s["departTime"]) > now + timedelta(hours=2))
    within = pick(lambda s: float(s["price"]) == 100.0
                  and now < parse_time(s["departTime"]) <= now + timedelta(hours=2))
    rebook = pick(lambda s: float(s["price"]) == 120.0
                  and parse_time(s["departTime"]) > now + timedelta(hours=2))
    nearby = pick(lambda s: float(s["price"]) == 130.0
                  and now < parse_time(s["departTime"]) <= now + timedelta(hours=1))
    for label, schedule in (("FULL", full), ("WITHIN", within), ("REBOOK", rebook),
                                   ("NEARBY", nearby)):
        if schedule is None:
            raise SystemExit(
                f"缺少测试班次 {label}：先跑 verify/m3-seed.sql，并确认后端连的是同一个库"
                "（DB_PORT 与 backend 的 application.yml 指向同一实例）")
    return full, within, rebook, nearby


print("=== M3 端到端验收 base=%s ===" % BASE)

passenger = login("passenger1", "123456")
other = login("passenger2", "123456")
seller = login("seller1", "seller123")
admin = login("admin", "admin123")

schedules = list_schedules(passenger)
full, within, rebook, nearby = pick_schedules(schedules)
print(f"测试班次 id: FULL={full['id']} WITHIN={within['id']} REBOOK={rebook['id']} NEARBY={nearby['id']}")

# --- 1. 全额退（>2h） ---
print("\n[1] 距发船 >2h：全额退")
oid, _ = create_and_pay(passenger, "/api/orders/mine", full["id"], "全额退甲", next_id_card())
order = find_order(passenger, "/api/orders/mine", oid)
tid = order["tickets"][0]["id"]
status, body = passenger.post(f"/api/tickets/{tid}/refund")
check("退票 200", status, 200)
close("实退金额 = 票面 100", body["data"]["refundAmount"], 100)
order = find_order(passenger, "/api/orders/mine", oid)
close("订单总额 100-100 = 0", order["totalAmount"], 0)
check("订单状态 已退票", order["status"], "已退票")
check("票状态 已退票", order["tickets"][0]["status"], "已退票")

# --- 2. 距发船 <=2h：扣 20% ---
print("\n[2] 距发船 <=2h：扣 20%")
oid, _ = create_and_pay(passenger, "/api/orders/mine", within["id"], "扣费乙", next_id_card())
tid = find_order(passenger, "/api/orders/mine", oid)["tickets"][0]["id"]
status, body = passenger.post(f"/api/tickets/{tid}/refund")
check("退票 200", status, 200)
close("实退金额 = 100*0.8 = 80", body["data"]["refundAmount"], 80)
order = find_order(passenger, "/api/orders/mine", oid)
close("订单总额 100-80 = 20", order["totalAmount"], 20)
check("唯一一张票退掉 → 订单已退票（判据是有效票计数，不看金额归零）", order["status"], "已退票")

# --- 2b. 部分退票：两票退一张，订单仍已支付 ---
print("\n[2b] 部分退票：两票退一张")
status, body = passenger.get_json(f"/api/schedules/{within['id']}/seats")
free = [s for s in body["data"]["seats"] if s["state"] == "可选"]
assert len(free) >= 2, free
seats = [
    {"seatNo": free[0]["seatNo"], "passengerName": "部分甲", "idCard": next_id_card(),
     "phone": next_phone()},
    {"seatNo": free[1]["seatNo"], "passengerName": "部分乙", "idCard": next_id_card(),
     "phone": next_phone()},
]
status, body = passenger.post("/api/orders", {"scheduleId": within["id"], "seats": seats})
check("下单 两票 200", status, 200)
if status != 200 or not body.get("data"):
    raise SystemExit(f"[2b] 下单失败: {status} {body}")
oid_part = body["data"]["id"]
CREATED_ORDERS.append(oid_part)
status, body = passenger.post(f"/api/orders/{oid_part}/pay")
check("支付 两票 200", status, 200)
part = find_order(passenger, "/api/orders/mine", oid_part)
status, body = passenger.post(f"/api/tickets/{part['tickets'][0]['id']}/refund")
check("退其中一张 200", status, 200)
part = find_order(passenger, "/api/orders/mine", oid_part)
close("订单总额 200-80 = 120", part["totalAmount"], 120)
check("还有一张有效票 → 订单仍已支付", part["status"], "已支付")
check("退掉的那张 已退票", part["tickets"][0]["status"], "已退票")
check("留下的那张 仍已支付", part["tickets"][1]["status"], "已支付")

# --- 3. 发船班次不在列表 + 下单被拒 ---
print("\n[3] 发船班次不出现在列表，且不能下单")
if not PAST_SCHEDULE_ID:
    raise SystemExit("FERRY_PAST_SCHEDULE_ID 没传进来：请用 verify/verify-m3.cmd 运行，"
                     "或手动设置该环境变量")
check("发船班次不在列表", any(s["id"] == PAST_SCHEDULE_ID for s in schedules), False)
status, body = passenger.post("/api/orders", {
    "scheduleId": PAST_SCHEDULE_ID,
    "seats": [{"seatNo": "1A", "passengerName": "过期丙", "idCard": next_id_card(),
    "phone": next_phone()}],
})
check("已发船班次下单 409", status, 409)
check("错误提示含 班次已发船", "班次已发船" in str(body.get("message", "")), True)

# --- 4. 改签补差价（100 → 120） ---
print("\n[4] 改签补差价")
oid, _ = create_and_pay(passenger, "/api/orders/mine", full["id"], "改签丁", next_id_card())
order = find_order(passenger, "/api/orders/mine", oid)
old_tid = order["tickets"][0]["id"]
target = free_seat(passenger, rebook["id"])
status, body = passenger.post(f"/api/tickets/{old_tid}/rebook",
                              {"targetScheduleId": rebook["id"], "targetSeatId": target["seatId"]})
check("改签 200", status, 200)
new_ticket = body["data"]
check("新票班次 = 目标班次", new_ticket["scheduleId"], rebook["id"])
close("新票票价 120", new_ticket["price"], 120)
check("新票指回原票", new_ticket["changedFromTicketId"], old_tid)
order = find_order(passenger, "/api/orders/mine", oid)
close("订单总额 100+20 = 120", order["totalAmount"], 120)
old = [t for t in order["tickets"] if t["id"] == old_tid][0]
check("原票状态 已退票", old["status"], "已退票")
check("原票退票原因 改签", old["refundReason"], "改签")
close("补差价时原票实退现金 0", old["refundAmount"], 0)

# --- 5. 改签退差额（120 → 100） ---
print("\n[5] 改签退差额")
oid, _ = create_and_pay(passenger, "/api/orders/mine", rebook["id"], "改签戊", next_id_card())
order = find_order(passenger, "/api/orders/mine", oid)
old_tid = order["tickets"][0]["id"]
target = free_seat(passenger, full["id"])
status, body = passenger.post(f"/api/tickets/{old_tid}/rebook",
                              {"targetScheduleId": full["id"], "targetSeatId": target["seatId"]})
check("改签 200", status, 200)
order = find_order(passenger, "/api/orders/mine", oid)
close("订单总额 120-20 = 100", order["totalAmount"], 100)
old = [t for t in order["tickets"] if t["id"] == old_tid][0]
close("退差额时原票实退现金 20", old["refundAmount"], 20)
check("原票退票原因 改签", old["refundReason"], "改签")

# --- 6. 改签限同航线 ---
print("\n[6] 改签限同航线")
oid, _ = create_and_pay(passenger, "/api/orders/mine", full["id"], "跨线己", next_id_card())
tid = find_order(passenger, "/api/orders/mine", oid)["tickets"][0]["id"]
cross_route = [s for s in schedules if s["routeId"] != 1 and s["id"] != rebook["id"]]
if cross_route:
    status, body = passenger.post(f"/api/tickets/{tid}/rebook",
                                  {"targetScheduleId": cross_route[0]["id"], "targetSeatId": 1})
    check("跨航线改签 409", status, 409)
    check("错误提示含 同航线", "同航线" in str(body.get("message", "")), True)

# --- 7. 乘客不能退别人的票 ---
print("\n[7] 乘客不能操作别人的订单")
status, body = other.post(f"/api/tickets/{tid}/refund")
check("他人退票 403", status, 403)
check("错误提示含 自己的订单", "自己的订单" in str(body.get("message", "")), True)

# --- 8. 售票员代售 + 代退 ---
print("\n[8] 售票员代售 / 代退")
oid, _ = create_and_pay(seller, "/api/seller/orders", full["id"], "代售庚",
        next_id_card(), "/api/seller/orders")
order = find_order(seller, "/api/seller/orders", oid)
check("代售订单 passengerId = 售票员", order["passengerId"] is not None, True)
tid = order["tickets"][0]["id"]
status, body = seller.post(f"/api/seller/orders/tickets/{tid}/refund")
check("代退 200", status, 200)
close("代退全额 100", body["data"]["refundAmount"], 100)

# --- 9. 管理员看全部并代操作 ---
print("\n[9] 管理员查看全部并代退")
status, body = admin.get_json("/api/seller/orders")
check("管理员订单列表 200", status, 200)
check("管理员能看到订单", isinstance(body.get("data"), list) and len(body["data"]) > 0, True)

# --- 10. 管理员代退乘客的票 ---
print("\n[10] 管理员代乘客退票")
status, body = admin.post(f"/api/tickets/{tid}/refund")
check("已退票再退 409", status, 409)

# --- 11. 改签后再退票：手续费按新班次算（缺陷 #4 回归） ---
print("\n[11] 改签后退票按新班次算手续费")
oid, _ = create_and_pay(passenger, "/api/orders/mine", full["id"], "改后再退辛", next_id_card())
order = find_order(passenger, "/api/orders/mine", oid)
tid = order["tickets"][0]["id"]
target = free_seat(passenger, within["id"])
status, body = passenger.post(f"/api/tickets/{tid}/rebook",
                              {"targetScheduleId": within["id"], "targetSeatId": target["seatId"]})
check("改签到 2h 内的班次 200", status, 200)
new_tid = body["data"]["id"]
status, body = passenger.post(f"/api/tickets/{new_tid}/refund")
check("改签后退票 200", status, 200)
close("按新班次扣 20%（按原班次会全额退 100）", body["data"]["refundAmount"], 80)
order = find_order(passenger, "/api/orders/mine", oid)
close("订单总额 100-80 = 20", order["totalAmount"], 20)
check("没有有效票 → 订单已退票（不看金额归零）", order["status"], "已退票")

# --- 12. 原班次距发船不足 1 小时不能改签 ---
print("\n[12] 原班次距发船不足 1 小时不能改签")
oid, _ = create_and_pay(passenger, "/api/orders/mine", nearby["id"], "近班壬", next_id_card())
tid = find_order(passenger, "/api/orders/mine", oid)["tickets"][0]["id"]
target = free_seat(passenger, full["id"])
status, body = passenger.post(f"/api/tickets/{tid}/rebook",
                              {"targetScheduleId": full["id"], "targetSeatId": target["seatId"]})
check("原班次不足 1 小时改签 409", status, 409)
check("错误提示含 1 小时", "1 小时" in str(body.get("message", "")), True)

# --- 13. 目标班次距发船不足 1 小时不能改签 ---
print("\n[13] 目标班次距发船不足 1 小时不能改签")
oid, _ = create_and_pay(passenger, "/api/orders/mine", full["id"], "近目标癸", next_id_card())
tid = find_order(passenger, "/api/orders/mine", oid)["tickets"][0]["id"]
target = free_seat(passenger, nearby["id"])
status, body = passenger.post(f"/api/tickets/{tid}/rebook",
                              {"targetScheduleId": nearby["id"], "targetSeatId": target["seatId"]})
check("目标班次不足 1 小时改签 409", status, 409)
check("错误提示含 目标班次", "目标班次" in str(body.get("message", "")), True)
# --- 14. 负向路径：后端校验必须真的在（删掉校验这段就会红） ---
print("\n[14] 非法输入一律 400，且库里不新增订单")
before = order_count(passenger)

def bad(desc, schedule_id, seats):
    status, body = passenger.post("/api/orders", {"scheduleId": schedule_id, "seats": seats})
    check(f"{desc} 400", status, 400)
    check(f"{desc} 错误码是校验错误", str(body.get("code")), "VALIDATION_ERROR")

seat_a = free_seat(passenger, full["id"])
bad("空姓名", full["id"], [{"seatNo": seat_a["seatNo"], "passengerName": "", "idCard": next_id_card(), "phone": next_phone()}])
seat_b = free_seat(passenger, full["id"])
bad("非法手机号", full["id"], [{"seatNo": seat_b["seatNo"], "passengerName": "坏手机子", "idCard": next_id_card(), "phone": "123"}])
seat_c = free_seat(passenger, full["id"])
legal = next_id_card()
# 翻转校验位造一个确定的非法身份证（GB 11643 校验位必错）
bad_check = "0" if legal[-1] != "0" else "1"
bad("身份证校验位错", full["id"], [{"seatNo": seat_c["seatNo"], "passengerName": "坏证子", "idCard": legal[:-1] + bad_check, "phone": next_phone()}])
seat_d, seat_e = free_seats(passenger, full["id"], 2)
same_id = next_id_card()
bad("同单两个乘船人同一身份证", full["id"], [
    {"seatNo": seat_d["seatNo"], "passengerName": "重复甲", "idCard": same_id, "phone": next_phone()},
    {"seatNo": seat_e["seatNo"], "passengerName": "重复乙", "idCard": same_id, "phone": next_phone()},
])
many = free_seats(passenger, full["id"], 6)
bad("一张订单 6 张票", full["id"], [
    {"seatNo": s["seatNo"], "passengerName": "超量%d" % i, "idCard": next_id_card(), "phone": next_phone()}
    for i, s in enumerate(many)
])
check("五次非法下单后订单数没变", order_count(passenger), before)

print("\n=== 结果 ===")
if failures:
    print("失败 %d 项:" % len(failures))
    for name in failures:
        print("  -", name)
    raise SystemExit(1)
print("全部通过")
