"""对照 docs/api.md 里的响应示例与实际响应：字段集合必须一致。"""
import http.cookiejar
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8089"
DOC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "api.md")


def new_client():
    jar = http.cookiejar.CookieJar()
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar)), jar


def csrf(op, jar):
    op.open(BASE + "/api/auth/csrf")
    for c in jar:
        if c.name == "XSRF-TOKEN":
            return c.value
    return None


def post(op, jar, path, payload):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        method="POST",
    )
    req.add_header("Content-Type", "application/json;charset=UTF-8")
    tok = [c.value for c in jar if c.name == "XSRF-TOKEN"]
    if tok:
        req.add_header("X-XSRF-TOKEN", tok[0])
    try:
        r = op.open(req)
        return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


def get(op, path):
    try:
        r = op.open(BASE + path)
        return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


def doc_examples():
    text = open(DOC, encoding="utf-8").read()
    blocks = []
    for m in re.finditer(r"```json\n(.*?)```", text, re.S):
        raw = m.group(1)
        try:
            blocks.append(json.loads(raw))
        except json.JSONDecodeError:
            pass
    return blocks


def keyset(obj):
    if isinstance(obj, dict):
        return tuple(sorted(obj.keys()))
    return ("<non-object>",)


def main():
    op, jar = new_client()
    token = csrf(op, jar)
    suffix = str(int(time.time()))[-6:]
    results = []

    st, body = post(op, jar, "/api/auth/login", {"username": "admin", "password": "admin123"})
    results.append(("登录成功", body))
    st, body = get(op, "/api/auth/current")
    results.append(("当前用户", body))

    st, body = post(
        op,
        jar,
        "/api/auth/register",
        {
            "username": "chk" + suffix,
            "password": "123456",
            "name": "核对",
            "phone": "13800138000",
            "idCard": "440101199001011234",
        },
    )
    results.append(("注册成功", body))

    st, body = post(op, jar, "/api/auth/register", {"username": "admin", "password": "123456", "name": "x", "phone": "13800138000", "idCard": "440101199001011234"})
    results.append(("用户名已存在", body))

    st, body = post(op, jar, "/api/auth/login", {"username": "admin", "password": "wrongpass"})
    results.append(("登录失败", body))

    st, body = post(op, jar, "/api/auth/logout", {})
    results.append(("登出成功", body))

    st, body = get(op, "/api/auth/current")
    results.append(("未登录", body))

    examples = doc_examples()
    print("文档中的 JSON 示例数:", len(examples))
    ok = True
    for name, actual in results:
        target = keyset(actual)
        matches = [e for e in examples if keyset(e) == target]
        # 成功示例 data 是用户对象，失败示例 data 为 null
        if name in ("登录成功", "当前用户", "注册成功"):
            cands = [e for e in examples if e.get("success") is True and isinstance(e.get("data"), dict) and "id" in e["data"] and keyset(e["data"]) == keyset(actual["data"])]
            hit = bool(cands)
        elif name == "用户名已存在":
            cands = [e for e in examples if e.get("success") is False and e.get("code") == "USERNAME_EXISTS"]
            hit = bool(cands)
        elif name == "登录失败":
            cands = [e for e in examples if e.get("success") is False and e.get("code") == "AUTH_FAILED"]
            hit = bool(cands)
        elif name == "未登录":
            cands = [e for e in examples if e.get("success") is False and e.get("code") == "NOT_LOGGED_IN" and e.get("message") == "请先登录"]
            hit = bool(cands)
        elif name == "登出成功":
            cands = [e for e in examples if e.get("success") is True and e.get("data") is None]
            hit = bool(cands)
        else:
            cands = matches
            hit = bool(matches)
        print(f"{'OK ' if hit else 'MISS'} {name}: 实际 {actual.get('code')} / {keyset(actual)}")
        if not hit:
            ok = False
        if name in ("登录成功", "注册成功"):
            print("    实际 data 字段:", list(actual["data"].keys()), "->", actual["data"].get("createTime"))
    print("结论:", "文档示例与实际响应一致" if ok else "存在不一致，需要修改文档")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())