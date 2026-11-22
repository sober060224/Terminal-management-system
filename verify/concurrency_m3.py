# -*- coding: utf-8 -*-
"""
模块三并发演示：50 个线程同时抢同一个座位，应该只有 1 个成功、49 个 409。

用法：先起后端 8089、确认库里有「售票中」班次且有可选座位，然后
    python verify/concurrency_m3.py
可用 FERRY_BASE 覆盖 baseURL。
"""
import http.cookiejar
import json
import os
import sys
import threading
import urllib.error
import urllib.request

BASE = os.environ.get("FERRY_BASE", "http://localhost:8089")
THREADS = 50
USER = ("passenger1", "123456")
ID_WEIGHTS = (7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
ID_CHECK = "10X98765432"


def id_card(seq):
    """GB 11643 校验位；seq 不同即身份证不同，避免一人一票判重。"""
    body = "440100199001%05d" % seq
    return body + ID_CHECK[sum(int(d) * w for d, w in zip(body, ID_WEIGHTS)) % 11]


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
        data = None if body is None else json.dumps(body).encode("utf-8")
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


def main():
    probe = login()
    status, body = probe.get("/api/schedules")
    assert status == 200 and body.get("data"), "没有售票中的班次，先跑 verify/m3-seed.sql"
    schedule = body["data"][0]
    status, body = probe.get("/api/schedules/%d/seats" % schedule["id"])
    assert status == 200, body
    free = next((seat for seat in body["data"]["seats"] if seat["state"] == "可选"), None)
    assert free, "班次 %d 没有可选座位" % schedule["id"]

    print("目标班次 %d（%s），座位 %s，并发 %d" % (schedule["id"], schedule["routeName"], free["seatNo"], THREADS))

    clients = [login() for _ in range(THREADS)]
    barrier = threading.Barrier(THREADS)
    results = []
    lock = threading.Lock()

    def buy(index):
        payload = {
            "scheduleId": schedule["id"],
            "seats": [{
                "seatNo": free["seatNo"],
                "passengerName": "并发%02d" % index,
                "idCard": id_card(index),
                "phone": "138%08d" % index,
            }],
        }
        barrier.wait()
        status, body = clients[index].post("/api/orders", payload)
        with lock:
            results.append((index, status, body))

    threads = [threading.Thread(target=buy, args=(i,)) for i in range(THREADS)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    ok = [r for r in results if r[1] == 200]
    conflict = [r for r in results if r[1] == 409]
    other = [r for r in results if r[1] not in (200, 409)]
    print("成功 %d，409 %d，其他 %d" % (len(ok), len(conflict), len(other)))
    for index, status, body in other[:5]:
        print("  异常 #%d -> %s %s" % (index, status, body.get("message")))

    passed = len(ok) == 1 and len(conflict) == THREADS - 1 and not other
    if ok:
        winner = ok[0]
        order_id = winner[2]["data"]["id"]
        status, body = clients[winner[0]].get("/api/schedules/%d/seats" % schedule["id"])
        state = next(s["state"] for s in body["data"]["seats"] if s["seatNo"] == free["seatNo"])
        print("胜出订单 %d，座位当前状态 %s" % (order_id, state))
        passed = passed and state == "锁定"
        status, _ = clients[winner[0]].post("/api/orders/%d/cancel" % order_id)
        print("清理：取消胜出订单 -> %d" % status)

    print("=== 结果 ===")
    print("PASS 1 成功 %d / 409 %d" % (len(ok), len(conflict)) if passed else "FAIL")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
