"""M2 端到端验收（航线与班次管理）：全部用 check 断言，可重复运行。

接口口径唯一来源是 docs/api.md 第 8 节（8.1–8.6），本脚本只验证、不改后端/前端源码。
断言覆盖：
  - 鉴权：未登录 401 / 乘客·售票员 403 / 管理员放行（走 CSRF）
  - 航线：新增 → 名称搜索 → 详情 → 改名 → 删除（无班次时成功）；重名 409
  - 删除守卫：航线有未完成班次 → 409；班次取消后 → 409「历史班次，请改为停用」
  - 船舶：新增 → 船名搜索 → 改描述 → 建班次后删除 409 → 已有班次改 totalSeats 409
  - 班次：新增（到达时间/座位数/待发布）→ 座位生成数 → 同船重叠 409 → 发布 →
    重复发布 409 → 乘客端可见 → 改票价
  - 批量生成：3 天 created==3；预占 1 天进 skipped；超过 62 天 400
  - 取消班次自动退票：乘客下单+支付 → 管理员取消 → 退款字段/票/座位/订单总额
  - 收尾：取消本轮全部班次、航线改停运、船舶改停用，保证可重复运行

名字带时间戳后缀，每次运行互不冲突；失败时打印实际响应体便于定位。
"""

import http.cookiejar
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, datetime, time as dtime, timedelta

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

BASE = os.environ.get("FERRY_BASE", "http://localhost:8089")
STAMP = str(int(time.time() * 1000))[-9:]

# 种子账号（见 docs/api.md 附录）。管理员/售票员密码与 verify_m0.py 保持一致。
ADMIN_USER, ADMIN_PW = "admin", "admin123"
SELLER_USER, SELLER_PW = "seller1", "seller123"
PASSENGER_USER, PASSENGER_PW = "passenger1", "123456"

# 本轮创建的资源，收尾统一按名字/ID 定位后取消或停运。
STATE = {
    "route_crud": None,
    "route_work": None,
    "ship_work": None,
    "route_work_name": None,
    "route_work_ports": None,
    "ship_work_name": None,
    "ship_work_type": None,
    "ship_work_desc": None,
    "ship_seats": 20,
    "route_duration": 90,
}

failures = []


def _short(body, limit=1800):
    try:
        text = json.dumps(body, ensure_ascii=False)
    except Exception:
        text = repr(body)
    return text if len(text) <= limit else text[:limit] + " ...(截断)"


def check(label, actual, expected, body=None):
    ok = actual == expected
    print(("  PASS " if ok else "  FAIL ") + label + f"  (got {actual!r}, want {expected!r})")
    if not ok:
        failures.append(label)
        if body is not None:
            print("        实际响应体: " + _short(body))
    return ok


def close(label, actual, expected, eps=0.001, body=None):
    ok = actual is not None and abs(float(actual) - float(expected)) < eps
    print(("  PASS " if ok else "  FAIL ") + label + f"  (got {actual!r}, want {expected!r})")
    if not ok:
        failures.append(label)
        if body is not None:
            print("        实际响应体: " + _short(body))
    return ok


def check_status(label, status, body, expected):
    return check(label, status, expected, body)


def check_msg(label, body, needle):
    msg = body.get("message", "") if isinstance(body, dict) else ""
    return check(label, needle in str(msg), True, body)


# ------------------------- 身份证 / 手机号（同 verify_m3.py） -------------------------
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
    global _PHONE_SEQ
    _PHONE_SEQ = (_PHONE_SEQ + 1) % 10000
    return "138%08d" % _PHONE_SEQ


# ------------------------------- HTTP 客户端 -------------------------------
class Client:
    """带 Cookie 的浏览器替身：XSRF-TOKEN 从 Cookie 取，写操作回填 X-XSRF-TOKEN 头。"""

    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def cookie(self, name):
        for c in self.jar:
            if c.name == name:
                return c.value
        return None

    def seed_csrf(self):
        return self.get_json("/api/auth/csrf")

    def _request(self, req):
        try:
            r = self.opener.open(req)
            raw = r.read().decode("utf-8", "replace")
            status = r.status
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            status = e.code
        try:
            return status, json.loads(raw)
        except Exception:
            return status, raw

    def _mutate(self, method, path, payload=None):
        data = None
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(BASE + path, data=data, method=method)
        if data is not None:
            req.add_header("Content-Type", "application/json;charset=UTF-8")
        token = self.cookie("XSRF-TOKEN")
        if token:
            req.add_header("X-XSRF-TOKEN", token)
        return self._request(req)

    def get_json(self, path):
        return self._request(urllib.request.Request(BASE + path, method="GET"))

    def post(self, path, payload=None):
        return self._mutate("POST", path, payload)

    def put(self, path, payload=None):
        return self._mutate("PUT", path, payload)

    def delete(self, path, payload=None):
        return self._mutate("DELETE", path, payload)


def login(username, password):
    client = Client()
    client.seed_csrf()
    status, body = client.post("/api/auth/login", {"username": username, "password": password})
    if status != 200:
        raise SystemExit(f"登录失败 {username}: {status} {_short(body)}")
    return client


def login_admin():
    return login(ADMIN_USER, ADMIN_PW)


def find_order(client, order_id):
    status, body = client.get_json("/api/orders/mine")
    if status != 200:
        raise SystemExit(f"我的订单失败: {status} {_short(body)}")
    for order in body.get("data") or []:
        if order.get("id") == order_id:
            return order
    raise AssertionError(f"订单 {order_id} 不在 /api/orders/mine")


def free_seat(client, schedule_id):
    status, body = client.get_json(f"/api/schedules/{schedule_id}/seats")
    if status != 200:
        raise SystemExit(f"座位图失败 schedule={schedule_id}: {status} {_short(body)}")
    for seat in body["data"]["seats"]:
        if seat.get("state") == "可选":
            return seat
    raise AssertionError(f"班次 {schedule_id} 没有可选座位")


def iso(dt):
    return dt.strftime("%Y-%m-%dT%H:%M:%S")


def parse_iso(value):
    """解析 ISO 本地时间；若后端带了时区偏移，统一剥掉 tzinfo 再和本地 naive 时间比较。"""
    dt = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    return dt.replace(tzinfo=None) if dt.tzinfo is not None else dt


def create_schedule(admin, route_id, ship_id, depart_dt, price):
    return admin.post("/api/admin/schedules", {
        "routeId": route_id,
        "shipId": ship_id,
        "departTime": iso(depart_dt),
        "price": price,
    })


def admin_schedule(admin, schedule_id):
    status, body = admin.get_json(f"/api/admin/schedules/{schedule_id}")
    if status != 200:
        raise SystemExit(f"班次详情失败 id={schedule_id}: {status} {_short(body)}")
    data = body["data"]
    # 后端 AdminScheduleDetailView 是 {schedule: row, seats: [...]}；契约文字又写作「row + seats」，
    # 这里两种形状都兼容，统一摊平成「行字段 + seats」。
    if isinstance(data, dict) and isinstance(data.get("schedule"), dict):
        row = dict(data["schedule"])
        row["seats"] = data.get("seats") or []
        return row
    return data


# ------------------------------- 收尾清理 -------------------------------
def cleanup():
    """把本轮创建的班次全部取消，航线改停运、船舶改停用（可重复运行）。"""
    admin = STATE.get("admin")
    if not admin:
        return
    print("\n[收尾] 取消本轮班次 + 航线停运 / 船舶停用")
    try:
        route_id = STATE.get("route_work")
        if route_id:
            status, body = admin.get_json(
                f"/api/admin/schedules?routeId={route_id}&page=1&size=100")
            if status == 200:
                items = (body.get("data") or {}).get("items") or []
                for s in items:
                    if s.get("status") in ("待发布", "售票中"):
                        st, bd = admin.post(f"/api/admin/schedules/{s['id']}/cancel")
                        print(f"  取消班次 {s['id']} -> {st}")
            else:
                print(f"  [WARN] 查询本轮班次失败: {status} {_short(body)}")

        # 有历史班次的航线不能删，改停运（保留 durationMin 原值，否则触发守卫）。
        if route_id and STATE.get("route_work_name"):
            ports = STATE.get("route_work_ports") or ("验收出发港", "验收到达港")
            st, bd = admin.put(f"/api/admin/routes/{route_id}", {
                "routeName": STATE["route_work_name"],
                "startPort": ports[0],
                "endPort": ports[1],
                "durationMin": STATE["route_duration"],
                "description": "M2 验收收尾：停运",
                "status": "停运",
            })
            print(f"  航线 {route_id} 改停运 -> {st}")

        # 无班次的临时航线可以物理删除。
        crud_id = STATE.get("route_crud")
        if crud_id:
            st, bd = admin.delete(f"/api/admin/routes/{crud_id}")
            if st == 200:
                print(f"  临时航线 {crud_id} 已删除")
            STATE["route_crud"] = None

        # 有历史班次的船舶不能删，改停用（保留 totalSeats 原值）。
        ship_id = STATE.get("ship_work")
        if ship_id and STATE.get("ship_work_name"):
            st, bd = admin.put(f"/api/admin/ships/{ship_id}", {
                "shipName": STATE["ship_work_name"],
                "totalSeats": STATE["ship_seats"],
                "shipType": STATE["ship_work_type"],
                "description": STATE["ship_work_desc"],
                "status": "停用",
            })
            print(f"  船舶 {ship_id} 改停用 -> {st}")
    except Exception as e:  # 收尾不许影响主结果
        print(f"  [WARN] 收尾异常: {e}")


# ------------------------------- 主流程 -------------------------------
def main():
    print("=== M2 端到端验收（航线与班次管理） base=%s ===" % BASE)
    print("本轮时间戳后缀: %s" % STAMP)

    admin = login_admin()
    STATE["admin"] = admin

    # ---------- 0. 鉴权 ----------
    print("\n[0] 鉴权：未登录 401 / 乘客·售票员 403 / 管理员放行")
    anon = Client()
    st, body = anon.get_json("/api/admin/routes")
    check_status("未登录访问 /api/admin/routes 401", st, body, 401)
    check("未登录错误码 NOT_LOGGED_IN", (body or {}).get("code") if isinstance(body, dict) else None,
          "NOT_LOGGED_IN", body)

    passenger = login(PASSENGER_USER, PASSENGER_PW)
    st, body = passenger.get_json("/api/admin/routes")
    check_status("乘客访问 /api/admin/routes 403", st, body, 403)
    check("乘客 403 错误码 FORBIDDEN", (body or {}).get("code") if isinstance(body, dict) else None,
          "FORBIDDEN", body)

    seller = login(SELLER_USER, SELLER_PW)
    st, body = seller.get_json("/api/admin/routes")
    check_status("售票员访问 /api/admin/routes 403", st, body, 403)

    st, body = admin.get_json("/api/admin/routes?page=1&size=10")
    check_status("管理员访问 /api/admin/routes 200", st, body, 200)
    items = ((body.get("data") or {}) if isinstance(body, dict) else {}).get("items")
    check("管理员列表返回分页结构", isinstance(items, list), True, body)

    # ---------- 1. 航线 CRUD（无班次可删） ----------
    print("\n[1] 航线：新增 → 名称搜索 → 详情 → 改名 → 删除")
    route_crud_name = "验收航线A" + STAMP
    route_crud_renamed = route_crud_name + "改"
    st, body = admin.post("/api/admin/routes", {
        "routeName": route_crud_name,
        "startPort": "验收出发港",
        "endPort": "验收到达港",
        "durationMin": 60,
        "description": "M2 验收临时航线",
        "status": "正常",
    })
    check_status("新增航线 200", st, body, 200)
    check_msg("新增航线提示", body, "新增航线成功")
    route_crud = (body.get("data") or {}).get("id") if isinstance(body, dict) else None
    STATE["route_crud"] = route_crud
    check("新增航线返回 id", isinstance(route_crud, int), True, body)
    if not isinstance(route_crud, int):
        raise SystemExit("[1] 新增航线没有返回 id，后续用例无法继续")

    st, body = admin.get_json(f"/api/admin/routes?name={urllib.parse.quote(route_crud_name)}&page=1&size=10")
    check_status("按名称搜索航线 200", st, body, 200)
    data = body.get("data") or {} if isinstance(body, dict) else {}
    names = [r.get("routeName") for r in (data.get("items") or [])]
    check("名称搜索命中新增航线", route_crud_name in names, True, body)
    check("分页 total >= 1", int(data.get("total") or 0) >= 1, True, body)

    st, body = admin.get_json(f"/api/admin/routes/{route_crud}")
    check_status("航线详情 200", st, body, 200)
    detail = body.get("data") or {} if isinstance(body, dict) else {}
    check("详情航线名称一致", detail.get("routeName"), route_crud_name, body)
    check("详情含班次列表（此时为空）", detail.get("schedules"), [], body)

    st, body = admin.put(f"/api/admin/routes/{route_crud}", {
        "routeName": route_crud_renamed,
        "startPort": "验收出发港",
        "endPort": "验收到达港",
        "durationMin": 60,
        "description": "M2 验收临时航线（已改名）",
        "status": "正常",
    })
    check_status("改名航线 200", st, body, 200)
    check_msg("改名提示", body, "修改航线成功")
    check("改名后 routeName 已更新", (body.get("data") or {}).get("routeName") if isinstance(body, dict) else None,
          route_crud_renamed, body)

    st, body = admin.post("/api/admin/routes", {
        "routeName": route_crud_renamed,
        "startPort": "验收出发港",
        "endPort": "验收到达港",
        "durationMin": 60,
        "status": "正常",
    })
    check_status("重名新增航线 409", st, body, 409)
    check_msg("重名提示", body, "航线名称已存在")

    st, body = admin.delete(f"/api/admin/routes/{route_crud}")
    check_status("删除无班次航线 200", st, body, 200)
    check_msg("删除航线提示", body, "删除航线成功")
    STATE["route_crud"] = None

    st, body = admin.get_json(f"/api/admin/routes/{route_crud}")
    check_status("删除后查详情 404", st, body, 404)
    check_msg("航线不存在提示", body, "航线不存在")

    # ---------- 2. 船舶 CRUD（先建工作船，供后续班次使用） ----------
    print("\n[2] 船舶：新增 → 船名搜索 → 改描述（建班次前）")
    ship_seats = STATE["ship_seats"]
    ship_name = "验收船" + STAMP
    STATE["ship_work_name"] = ship_name
    STATE["ship_work_type"] = "高速船"
    STATE["ship_work_desc"] = "M2 验收用船"
    st, body = admin.post("/api/admin/ships", {
        "shipName": ship_name,
        "totalSeats": ship_seats,
        "shipType": "高速船",
        "description": "M2 验收用船",
        "status": "正常",
    })
    check_status("新增船舶 200", st, body, 200)
    check_msg("新增船舶提示", body, "新增船舶成功")
    ship_id = (body.get("data") or {}).get("id") if isinstance(body, dict) else None
    STATE["ship_work"] = ship_id
    check("新增船舶返回 id", isinstance(ship_id, int), True, body)
    check("新增船舶 totalSeats 一致", (body.get("data") or {}).get("totalSeats") if isinstance(body, dict) else None,
          ship_seats, body)
    if not isinstance(ship_id, int):
        raise SystemExit("[2] 新增船舶没有返回 id，后续用例无法继续")

    st, body = admin.get_json(f"/api/admin/ships?name={urllib.parse.quote(ship_name)}&page=1&size=10")
    check_status("按船名搜索 200", st, body, 200)
    sdata = (body.get("data") or {}) if isinstance(body, dict) else {}
    ship_names = [s.get("shipName") for s in (sdata.get("items") or [])]
    check("船名搜索命中新增船舶", ship_name in ship_names, True, body)

    st, body = admin.put(f"/api/admin/ships/{ship_id}", {
        "shipName": ship_name,
        "totalSeats": ship_seats,
        "shipType": "高速船",
        "description": "M2 验收用船（描述已改）",
        "status": "正常",
    })
    check_status("改船舶描述 200", st, body, 200)
    check_msg("改船舶提示", body, "修改船舶成功")
    check("描述已更新", (body.get("data") or {}).get("description") if isinstance(body, dict) else None,
          "M2 验收用船（描述已改）", body)
    STATE["ship_work_desc"] = "M2 验收用船（描述已改）"

    st, body = admin.post("/api/admin/ships", {
        "shipName": ship_name,
        "totalSeats": 30,
        "shipType": "普通船",
        "status": "正常",
    })
    check_status("重名新增船舶 409", st, body, 409)
    check_msg("重名船舶提示", body, "船舶名称已存在")

    # ---------- 3. 航线 + 船舶 + 班次（工作资源） ----------
    print("\n[3] 建立工作航线与首班次，验证删除守卫")
    route_work_name = "验收航线B" + STAMP
    STATE["route_work_name"] = route_work_name
    STATE["route_work_ports"] = ("验收出发港", "验收到达港")
    st, body = admin.post("/api/admin/routes", {
        "routeName": route_work_name,
        "startPort": "验收出发港",
        "endPort": "验收到达港",
        "durationMin": STATE["route_duration"],
        "description": "M2 验收工作航线",
        "status": "正常",
    })
    check_status("新增工作航线 200", st, body, 200)
    route_work = (body.get("data") or {}).get("id") if isinstance(body, dict) else None
    STATE["route_work"] = route_work
    if not isinstance(route_work, int):
        raise SystemExit("[3] 新增工作航线没有返回 id，后续用例无法继续")

    today = date.today()
    s1_depart = datetime.combine(today + timedelta(days=5), dtime(9, 0))
    st, body = create_schedule(admin, route_work, ship_id, s1_depart, 150)
    check_status("新增首班次 200", st, body, 200)
    check_msg("新增班次提示", body, "新增班次成功")
    s1_id = (body.get("data") or {}).get("id") if isinstance(body, dict) else None
    check("新增首班次返回 id", isinstance(s1_id, int), True, body)
    if not isinstance(s1_id, int):
        raise SystemExit("[3] 新增首班次没有返回 id，后续用例无法继续")

    # 航线有未完成班次 → 不能删
    st, body = admin.delete(f"/api/admin/routes/{route_work}")
    check_status("航线有未完成班次时删除 409", st, body, 409)
    check_msg("未完成班次删除提示", body, "该航线还有未完成班次，不能删除")

    # 船舶有未完成班次 → 不能删
    st, body = admin.delete(f"/api/admin/ships/{ship_id}")
    check_status("船舶有未完成班次时删除 409", st, body, 409)
    check_msg("未完成班次删船提示", body, "该船舶还有未完成班次，不能删除")

    # 船舶已有班次 → 不能改总座位数
    st, body = admin.put(f"/api/admin/ships/{ship_id}", {
        "shipName": ship_name,
        "totalSeats": ship_seats + 4,
        "shipType": "高速船",
        "description": STATE["ship_work_desc"],
        "status": "正常",
    })
    check_status("已有班次改 totalSeats 409", st, body, 409)
    check_msg("改座位数提示", body, "该船舶已有班次，不能修改总座位数")

    # 取消首班次 → 变成历史班次
    st, body = admin.post(f"/api/admin/schedules/{s1_id}/cancel")
    check_status("取消首班次 200", st, body, 200)
    check("取消返回 refundedTickets=0", (body.get("data") or {}).get("refundedTickets") if isinstance(body, dict) else None,
          0, body)
    check("首班次状态变已取消", admin_schedule(admin, s1_id).get("status"), "已取消")

    # 只剩历史班次 → 航线改停运 / 船舶改停用，删除仍 409
    st, body = admin.delete(f"/api/admin/routes/{route_work}")
    check_status("航线只有历史班次时删除 409", st, body, 409)
    check_msg("历史班次删除提示", body, "该航线已有历史班次，请改为停运")

    st, body = admin.delete(f"/api/admin/ships/{ship_id}")
    check_status("船舶只有历史班次时删除 409", st, body, 409)
    check_msg("历史班次删船提示", body, "该船舶已有历史班次，请将状态改为停用")

    # ---------- 4. 班次核心：新增断言 / 座位 / 重叠 / 发布 / 可见 / 改价 ----------
    print("\n[4] 班次核心：新增断言 → 座位数 → 重叠 409 → 发布 → 重复发布 409 → 乘客可见 → 改票价")
    s2_depart = datetime.combine(today + timedelta(days=6), dtime(9, 0))
    expected_arrive = s2_depart + timedelta(minutes=STATE["route_duration"])
    st, body = create_schedule(admin, route_work, ship_id, s2_depart, 150)
    check_status("新增班次 S2 200", st, body, 200)
    s2 = body.get("data") or {} if isinstance(body, dict) else {}
    s2_id = s2.get("id")
    if not isinstance(s2_id, int):
        raise SystemExit(f"[4] 新增班次 S2 没有返回 id: {_short(body)}")
    check("S2 发船时间一致", parse_iso(s2.get("departTime")), s2_depart, body)
    check("S2 arriveTime = departTime + durationMin", parse_iso(s2.get("arriveTime")), expected_arrive, body)
    check("S2 totalSeats 等于船座位数", s2.get("totalSeats"), ship_seats, body)
    check("S2 状态 待发布", s2.get("status"), "待发布", body)

    detail = admin_schedule(admin, s2_id)
    seats = detail.get("seats") or []
    check("S2 座位数 seats.length == totalSeats", len(seats), int(detail.get("totalSeats") or 0), detail)
    check("S2 座位数 == 船座位数", len(seats), ship_seats, detail)

    # 同船重叠时段再建 → 409
    overlap_depart = s2_depart + timedelta(minutes=30)
    st, body = create_schedule(admin, route_work, ship_id, overlap_depart, 150)
    check_status("同船重叠时段新增 409", st, body, 409)
    check_msg("重叠提示", body, "该船舶在该时段已有班次")

    # 发布：待发布 → 售票中
    st, body = admin.post(f"/api/admin/schedules/{s2_id}/publish")
    check_status("发布班次 200", st, body, 200)
    check_msg("发布提示", body, "班次已发布")
    check("发布后状态 售票中", (body.get("data") or {}).get("status") if isinstance(body, dict) else None,
          "售票中", body)

    st, body = admin.post(f"/api/admin/schedules/{s2_id}/publish")
    check_status("重复发布 409", st, body, 409)
    check_msg("重复发布提示", body, "班次不在待发布状态，不能发布")

    # 乘客端 /api/schedules 能看到
    st, body = passenger.get_json("/api/schedules")
    check_status("乘客端班次列表 200", st, body, 200)
    plist = body.get("data") or [] if isinstance(body, dict) else []
    check("乘客端能看到已发布班次 S2", any(s.get("id") == s2_id for s in plist), True, body)

    # 改票价 200
    st, body = admin.put(f"/api/admin/schedules/{s2_id}", {
        "shipId": ship_id,
        "departTime": iso(s2_depart),
        "price": 200,
    })
    check_status("修改班次票价 200", st, body, 200)
    check_msg("修改班次提示", body, "修改班次成功")
    close("票价改为 200", (body.get("data") or {}).get("price") if isinstance(body, dict) else None, 200, body=body)

    # ---------- 5. 批量生成 ----------
    print("\n[5] 批量生成：3 天 created==3 / 预占跳过 / 超 62 天 400")
    base_a = today + timedelta(days=40)
    st, body = admin.post("/api/admin/schedules/batch", {
        "routeId": route_work,
        "shipId": ship_id,
        "dateFrom": base_a.isoformat(),
        "dateTo": (base_a + timedelta(days=2)).isoformat(),
        "departTime": "06:00:00",
        "price": 150,
    })
    check_status("批量生成 3 天 200", st, body, 200)
    check_msg("批量生成提示", body, "批量生成完成")
    bdata = body.get("data") or {} if isinstance(body, dict) else {}
    check("批量生成 created == 3", bdata.get("created"), 3, body)
    check("批量生成 skipped 为空", len(bdata.get("skipped") or []), 0, body)

    # 预占 1 天同船同时段，再批量生成 → 该天进入 skipped
    base_b = today + timedelta(days=50)
    occupied_day = base_b + timedelta(days=1)
    st, body = create_schedule(admin, route_work, ship_id,
                               datetime.combine(occupied_day, dtime(6, 0)), 150)
    check_status("预占一天班次 200", st, body, 200)

    st, body = admin.post("/api/admin/schedules/batch", {
        "routeId": route_work,
        "shipId": ship_id,
        "dateFrom": base_b.isoformat(),
        "dateTo": (base_b + timedelta(days=2)).isoformat(),
        "departTime": "06:00:00",
        "price": 150,
    })
    check_status("批量生成（含预占）200", st, body, 200)
    bdata = body.get("data") or {} if isinstance(body, dict) else {}
    skipped = bdata.get("skipped") or []
    check("含预占 created == 2", bdata.get("created"), 2, body)
    check("含预占 skipped 长度 1", len(skipped), 1, body)
    if skipped:
        check("skipped 日期命中预占日", skipped[0].get("date"), occupied_day.isoformat(), body)
        check("skipped reason 非空", bool(str(skipped[0].get("reason") or "").strip()), True, body)
    else:
        failures.append("含预占 skipped 长度 1")

    # 超过 62 天 → 400
    base_c = today + timedelta(days=60)
    st, body = admin.post("/api/admin/schedules/batch", {
        "routeId": route_work,
        "shipId": ship_id,
        "dateFrom": base_c.isoformat(),
        "dateTo": (base_c + timedelta(days=63)).isoformat(),
        "departTime": "06:00:00",
        "price": 150,
    })
    check_status("批量生成超过 62 天 400", st, body, 400)
    check_msg("超 62 天提示", body, "62")

    # ---------- 6. 取消班次自动退票 ----------
    print("\n[6] 取消班次自动全额退票")
    seat = free_seat(passenger, s2_id)
    st, body = passenger.post("/api/orders", {
        "scheduleId": s2_id,
        "seats": [{
            "seatNo": seat["seatNo"],
            "passengerName": "验收退票乘客",
            "idCard": next_id_card(),
            "phone": next_phone(),
        }],
    })
    check_status("乘客下单 200", st, body, 200)
    order_id = (body.get("data") or {}).get("id") if isinstance(body, dict) else None
    if not isinstance(order_id, int):
        raise SystemExit(f"[6] 下单没有返回订单 id: {_short(body)}")
    st, body = passenger.post(f"/api/orders/{order_id}/pay")
    check_status("乘客支付 200", st, body, 200)

    order = find_order(passenger, order_id)
    ticket = (order.get("tickets") or [{}])[0]
    ticket_price = ticket.get("price")
    close("订单总额 = 票面价", order.get("totalAmount"), ticket_price, body=order)

    st, body = admin.post(f"/api/admin/schedules/{s2_id}/cancel")
    check_status("管理员取消班次 200", st, body, 200)
    check_msg("取消班次提示", body, "班次已取消并完成退款")
    cdata = body.get("data") or {} if isinstance(body, dict) else {}
    check("refundedTickets == 1", cdata.get("refundedTickets"), 1, body)
    close("refundedAmount == 票价", cdata.get("refundedAmount"), ticket_price, body=body)

    order = find_order(passenger, order_id)
    oticket = None
    for t in order.get("tickets") or []:
        if t.get("id") == ticket.get("id"):
            oticket = t
            break
    check("订单状态 已退票", order.get("status"), "已退票")
    close("订单 total_amount 递减为 0", order.get("totalAmount"), 0, body=body)
    if oticket:
        check("票状态 已退票", oticket.get("status"), "已退票")
        check("票 refundReason == 班次取消", oticket.get("refundReason"), "班次取消", oticket)
        close("票 refundAmount == price", oticket.get("refundAmount"), ticket_price, body=oticket)
    else:
        failures.append("订单里找不到刚退的那张票")

    detail = admin_schedule(admin, s2_id)
    released = [s for s in (detail.get("seats") or []) if s.get("seatNo") == seat["seatNo"]]
    check("取消后原座位回到可选", released[0].get("state") if released else None, "可选", detail)

    print("\n=== 结果 ===")
    if failures:
        print("失败 %d 项:" % len(failures))
        for name in failures:
            print("  -", name)
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    code = 1
    try:
        code = main()
    finally:
        cleanup()
    raise SystemExit(code)
