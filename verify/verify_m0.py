"""M0 端到端验收：全部用 assert，可重复运行。

模拟真实浏览器流程：
  1. GET /api/auth/csrf  -> 服务端种下 XSRF-TOKEN Cookie
  2. 写操作时只带 Cookie 里的 XSRF-TOKEN 值做 X-XSRF-TOKEN 头
     （axios 的行为：不额外调后端拿 token）
干净库可重复运行：用户名带时间戳，不依赖预置用户。
"""

import http.cookiejar
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

BASE = os.environ.get("FERRY_BASE", "http://localhost:8089")
STAMP = str(int(time.time() * 1000))[-9:]
PASSWORD = "Pass123456"

PASSENGER = "m0p" + STAMP
DUP = "m0d" + STAMP
BADPHONE = "m0b" + STAMP
SHORTPW = "m0s" + STAMP
BADNAME = "m0x"

failures = []


def check(label, actual, expected):
    ok = actual == expected
    print(("  PASS " if ok else "  FAIL ") + label + f"  (got {actual!r}, want {expected!r})")
    if not ok:
        failures.append(label)
    return ok


class Client:
    """带 Cookie 的浏览器替身。"""

    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def cookie(self, name):
        for c in self.jar:
            if c.name == name:
                return c.value
        return None

    def drop_cookie(self, name):
        for c in list(self.jar):
            if c.name == name:
                self.jar.clear(c.domain, c.path, c.name)

    def csrf_cookie(self):
        return self.cookie("XSRF-TOKEN")

    def seed_csrf(self):
        """前端首屏会调 /api/auth/csrf；这一步负责种 Cookie。"""
        status, body = self.get_json("/api/auth/csrf")
        return status, body

    def _request(self, req):
        try:
            r = self.opener.open(req)
            raw = r.read().decode("utf-8", "replace")
            try:
                return r.status, json.loads(raw)
            except Exception:
                return r.status, raw
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            try:
                return e.code, json.loads(raw)
            except Exception:
                return e.code, raw

    def get_json(self, path):
        return self._request(urllib.request.Request(BASE + path, method="GET"))

    def post(self, path, payload=None):
        data = json.dumps(payload or {}, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(BASE + path, data=data, method="POST")
        req.add_header("Content-Type", "application/json;charset=UTF-8")
        # axios 的做法：token 只从 Cookie 取，没有 Cookie 时它会先补一次 /auth/csrf
        token = self.csrf_cookie()
        if token:
            req.add_header("X-XSRF-TOKEN", token)
        return self._request(req)

    def post_without_csrf(self, path, payload=None):
        data = json.dumps(payload or {}, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(BASE + path, data=data, method="POST")
        req.add_header("Content-Type", "application/json;charset=UTF-8")
        return self._request(req)


def new_passenger():
    return {
        "username": PASSENGER,
        "password": PASSWORD,
        "name": "验收乘客",
        "phone": "138" + STAMP[-8:],
        "idCard": "44030119900101" + STAMP[-3:].rjust(3, "0") + "X",
    }


print("=== M0 端到端验收 base=%s ===" % BASE)

# --- 0. CSRF: 不种 Cookie 直接 POST 应被拒 ---
print("\n[0] CSRF 防护")
op = Client()
st, body = op.post_without_csrf("/api/auth/login", {"username": "admin", "password": "x"})
check("未带 CSRF token 的 POST 被拒", st, 403)

op = Client()
st, body = op.seed_csrf()
check("GET /api/auth/csrf 返回 200", st, 200)
check("csrf 响应带 token", bool(body.get("data", {}).get("token")), True)
check("csrf 响应带 ready", body.get("data", {}).get("ready"), True)
check("csrf 之后 XSRF-TOKEN Cookie 已种下", op.csrf_cookie() is not None, True)

# --- 1. 注册 ---
print("\n[1] 注册")
op = Client()
op.seed_csrf()
payload = new_passenger()
st, body = op.post("/api/auth/register", payload)
check("注册新乘客 200", st, 200)
check("注册返回 success", body.get("success"), True)
check("注册返回角色 PASSENGER", body.get("data", {}).get("role"), "PASSENGER")
check("注册不返回密码", "password" in json.dumps(body.get("data", {})), False)

st, body = op.post("/api/auth/register", payload)
check("重名注册 409", st, 409)
check("重名错误码 USERNAME_EXISTS", body.get("code"), "USERNAME_EXISTS")

op = Client()
op.seed_csrf()
bad = new_passenger()
bad["username"] = BADNAME
st, body = op.post("/api/auth/register", bad)
check("3 字符用户名 400", st, 400)
check("用户名长度错误码 VALIDATION_ERROR", body.get("code"), "VALIDATION_ERROR")

op = Client()
op.seed_csrf()
st, body = op.post("/api/auth/login", {"username": BADNAME, "password": PASSWORD})
check("3 字符用户名登录 400", st, 400)
check("登录用户名长度错误码 VALIDATION_ERROR", body.get("code"), "VALIDATION_ERROR")

op = Client()
op.seed_csrf()
bad = new_passenger()
bad["username"] = BADPHONE
bad["phone"] = "123"
st, body = op.post("/api/auth/register", bad)
check("手机号非法 400", st, 400)
check("校验失败错误码 VALIDATION_ERROR", body.get("code"), "VALIDATION_ERROR")

op = Client()
op.seed_csrf()
bad = new_passenger()
bad["username"] = SHORTPW
bad["password"] = "123"
st, body = op.post("/api/auth/register", bad)
check("密码过短 400", st, 400)

# --- 2. 登录与会话 ---
print("\n[2] 登录 / 会话")
op = Client()
op.seed_csrf()
st, body = op.post("/api/auth/login", {"username": "admin", "password": "admin123"})
check("管理员登录 200", st, 200)
check("管理员角色 ADMIN", body.get("data", {}).get("role"), "ADMIN")

st, body = op.get_json("/api/auth/current")
check("带会话查 current 200", st, 200)
check("current 返回用户名", body.get("data", {}).get("username"), "admin")
check("current 返回身份证号", body.get("data", {}).get("idCard"), "440100199001011001")

# 判据不是「不等于 403」：M0 没有 /api/admin/** 的业务接口，管理员拿到的是 404
# （Spring 静态资源处理器），乘客/售票员拿到的是 403（被安全过滤链拦下）。
# 只有「管理员 404 + 其他角色 403」才证明角色过滤真的生效。
st, body = op.get_json("/api/admin/users")
check("管理员通过角色过滤（404=没有该接口，不是 403）", st, 404)

op = Client()
op.seed_csrf()
st, body = op.post("/api/auth/login", {"username": "seller1", "password": "seller123"})
check("售票员登录 200", st, 200)
check("售票员角色 SELLER", body.get("data", {}).get("role"), "SELLER")

op = Client()
op.seed_csrf()
st, body = op.post("/api/auth/login", {"username": "admin", "password": "wrongpass"})
check("错误密码 401", st, 401)
check("登录失败错误码 AUTH_FAILED", body.get("code"), "AUTH_FAILED")

op = Client()
op.seed_csrf()
st, body = op.post("/api/auth/login", {"username": "admin", "password": "admin123"})
check("失败重登录前置登录 200", st, 200)
st, body = op.post("/api/auth/login", {"username": "admin", "password": "wrongpass"})
check("失败重登录 401", st, 401)
st, body = op.get_json("/api/auth/current")
check("失败重登录后旧 Session 401", st, 401)
check("失败重登录后错误码 NOT_LOGGED_IN", body.get("code"), "NOT_LOGGED_IN")

# --- 2.1 Remember Me ---
print("\n[2.1] Remember Me")
op = Client()
op.seed_csrf()
st, body = op.post("/api/auth/login", {
    "username": "admin",
    "password": "admin123",
    "rememberMe": True,
})
check("记住我登录 200", st, 200)
remember = next((c for c in op.jar if c.name == "FERRY_REMEMBER_ME"), None)
check("记住我 Cookie 已签发", remember is not None, True)
check("记住我 Cookie 带过期时间", remember is not None and remember.expires is not None, True)
if remember is not None and remember.expires is not None:
    remaining = remember.expires - time.time()
    check("记住我 Cookie 约为 7 天", 7 * 24 * 3600 - 120 <= remaining <= 7 * 24 * 3600 + 120, True)

op.drop_cookie("JSESSIONID")
check("测试客户端已删除 JSESSIONID", op.cookie("JSESSIONID") is None, True)
st, body = op.get_json("/api/auth/current")
check("Session 删除后可由记住我恢复", st, 200)
check("恢复后用户名正确", body.get("data", {}).get("username"), "admin")

st, body = op.post("/api/auth/logout")
check("记住我登录后登出 200", st, 200)
check("登出后 JSESSIONID 已删除", op.cookie("JSESSIONID") is None, True)
check("登出后 FERRY_REMEMBER_ME 已删除", op.cookie("FERRY_REMEMBER_ME") is None, True)

op = Client()
op.seed_csrf()
st, body = op.post("/api/auth/login", {
    "username": "admin",
    "password": "admin123",
    "rememberMe": False,
})
check("未勾选记住我登录 200", st, 200)
check("未勾选不签发 FERRY_REMEMBER_ME", op.cookie("FERRY_REMEMBER_ME") is None, True)
op.drop_cookie("JSESSIONID")
st, body = op.get_json("/api/auth/current")
check("未勾选时删除 Session 后 current 401", st, 401)

# --- 3. 角色鉴权 ---
print("\n[3] 角色鉴权")
op = Client()
op.seed_csrf()
op.post("/api/auth/login", {"username": PASSENGER, "password": PASSWORD})
st, body = op.get_json("/api/auth/current")
check("新乘客登录后 current 200", st, 200)
check("新乘客角色 PASSENGER", body.get("data", {}).get("role"), "PASSENGER")
st, body = op.get_json("/api/admin/users")
check("乘客访问管理接口 403", st, 403)
check("403 错误码 FORBIDDEN", body.get("code"), "FORBIDDEN")

op = Client()
op.seed_csrf()
op.post("/api/auth/login", {"username": "seller1", "password": "seller123"})
st, body = op.get_json("/api/admin/users")
check("售票员访问管理接口 403", st, 403)

# --- 4. 登出 ---
print("\n[4] 登出")
op = Client()
op.seed_csrf()
op.post("/api/auth/login", {"username": "admin", "password": "admin123"})
st, body = op.post("/api/auth/logout")
check("登出 200", st, 200)
# 立刻查 Cookie：任何后续请求都会让 CsrfFilter 重新种一个 XSRF-TOKEN
check("登出后 XSRF-TOKEN Cookie 已被删除", op.csrf_cookie() is None, True)
# 此刻正是浏览器里点过「退出登录」后的真实状态：没有会话、没有令牌。
# 不带令牌直接登录后端必须拒绝 —— 证明令牌确实是必需的。
st, body = op.post("/api/auth/login", {"username": "seller1", "password": "seller123"})
check("无令牌直接登录 403", st, 403)
check("403 错误码 FORBIDDEN", body.get("code"), "FORBIDDEN")
# 前端拦截器这时补一次 /auth/csrf，同一个 SPA 里换账号必须能登录成功
op.seed_csrf()
st, body = op.post("/api/auth/login", {"username": "seller1", "password": "seller123"})
check("重新取令牌后换账号登录 200", st, 200)
check("换账号后角色为 SELLER", body.get("data", {}).get("role"), "SELLER")

st, body = op.post("/api/auth/logout")
check("再次登出 200", st, 200)
st, body = op.get_json("/api/auth/current")
check("登出后 current 401", st, 401)
check("401 错误码 NOT_LOGGED_IN", body.get("code"), "NOT_LOGGED_IN")

# --- 5. 前端静态资源 ---
print("\n[5] 前端产物")
op = Client()
st, body = op.get_json("/")
check("GET / 返回 200", st, 200)
st, body = op.get_json("/index.html")
check("GET /index.html 返回 200", st, 200)

print("\n=== 结果 ===")
if failures:
    print("失败 %d 项:" % len(failures))
    for f in failures:
        print("  -", f)
    raise SystemExit(1)
print("全部通过")
