# -*- coding: utf-8 -*-
"""
模块三跨班次并发验收：同一订单的两张票，先把一张改签到班次 B，
再并发「退班次 A 剩下的那张」与「退班次 B 的新票」。

两张票属于不同班次 → 班次锁只能串行化 A 组与 B 组，挡不住同单两票争
`orders.total_amount`。能通过说明靠的是订单行锁（`OrdersRepository.findByIdForUpdate`），
删掉锁就会丢失更新 —— 这是 `verify/concurrency_refund_m3.py`（两票同班次）测不到的场景。

用法：先跑 verify/m3-seed.sql，起后端 8089，然后
    python verify/concurrency_cross_schedule_m3.py
可用 FERRY_BASE / ROUNDS 覆盖。
"""
import threading

from concurrency_refund_m3 import (CREATED_ORDERS, login, future_schedule_with_seats,
                                    next_id_card, next_phone)

# 本轮自己创建的订单，退出时清掉（P2-7）。
from m3_teardown import teardown as teardown_orders
import atexit

atexit.register(lambda: teardown_orders(CREATED_ORDERS))

try:
    import sys
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

import os

ROUNDS = int(os.environ.get("ROUNDS", "5"))


def run_round(index):
    """一轮：下单两票 → 支付 → 把第二张改签到另一班次 → 并发退剩余两张 → 校验。"""
    buyer = login()
    schedule_a, seats_a = future_schedule_with_seats(buyer, 2)
    schedule_b, seats_b = future_schedule_with_seats(buyer, 1, exclude_id=schedule_a["id"])

    payload = {
        "scheduleId": schedule_a["id"],
        "seats": [
            {"seatNo": seats_a[0]["seatNo"], "passengerName": "跨班并发%dA" % index,
             "idCard": next_id_card(), "phone": next_phone()},
            {"seatNo": seats_a[1]["seatNo"], "passengerName": "跨班并发%dB" % index,
             "idCard": next_id_card(), "phone": next_phone()},
        ],
    }
    status, body = buyer.post("/api/orders", payload)
    if status != 200:
        return "第 %d 轮下单失败 %s %s" % (index, status, body)
    order_id = body["data"]["id"]
    CREATED_ORDERS.append(order_id)

    status, body = buyer.post("/api/orders/%d/pay" % order_id)
    if status != 200:
        return "第 %d 轮支付失败 %s %s" % (index, status, body)

    status, listing = buyer.get("/api/orders/mine")
    order = next((o for o in listing["data"] if o["id"] == order_id), None)
    if not order or len(order["tickets"]) != 2:
        return "第 %d 轮订单票数不对：%s" % (index, order)
    keep_id, move_id = [t["id"] for t in order["tickets"]]

    # 改动第二张：改签到班次 B（同航线由后端校验，seed 保证有同航线候选）
    status, body = buyer.post("/api/tickets/%d/rebook" % move_id,
                              {"targetScheduleId": schedule_b["id"],
                               "targetSeatId": seats_b[0]["seatId"]})
    if status != 200:
        return "第 %d 轮改签失败 %s %s" % (index, status, body)
    new_ticket_id = body["data"]["id"]

    clients = [login(), login()]
    barrier = threading.Barrier(2)
    results = []
    lock = threading.Lock()

    def refund(slot):
        ticket_id = keep_id if slot == 0 else new_ticket_id
        barrier.wait()
        status, body = clients[slot].post("/api/tickets/%d/refund" % ticket_id)
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
    order = next((o for o in body["data"] if o["id"] == order_id), None)
    if order is None:
        return "第 %d 轮订单查不到" % index

    # 两张票都是发船 2 小时之外的全额退 → 总额必须正好归零
    if abs(float(order["totalAmount"])) > 0.001:
        return "第 %d 轮丢失更新：总额 %s，应为 0.00" % (index, order["totalAmount"])
    if order["status"] != "已退票":
        return "第 %d 轮订单状态是 %s，应为 已退票" % (index, order["status"])
    # 改签会留下原票（已退票·改签）+ 新票，所以票数是 3；要求不能剩下任何有效票
    active = [t["status"] for t in order["tickets"] if t["status"] in ("待支付", "已支付", "已检票", "已完成")]
    if active:
        return "第 %d 轮还有有效票 %s" % (index, active)
    return None


def main():
    problems = []
    for index in range(ROUNDS):
        problem = run_round(index)
        print(("  PASS 跨班次第 %d 轮" % index) if problem is None else ("  FAIL " + problem))
        if problem:
            problems.append(problem)

    print("\n跨班次并发退票 %d 轮，失败 %d 轮" % (ROUNDS, len(problems)))
    if problems:
        for problem in problems[:5]:
            print("  -", problem)
        raise SystemExit(1)
    print("全部通过")


if __name__ == "__main__":
    main()