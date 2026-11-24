# 0003 座位占用靠 `seat.order_id` 和视图 `v_seat_state`，不做两个时钟

需求里原来有两条锁定时限：选座后锁 3 分钟、下单后锁 15 分钟。M3 实现把两步合成一步：**下单时同时锁座**，`seat.lock_until` 直接写订单的 `expire_at`（`ferry.order-pay-minutes`，默认 15 分钟），只有一个时钟，不存在"3 分钟到了把还活着的订单座位释放掉"的超卖。`seat.order_id` 表示座位已被订单占用，订单是否到期只看 `orders.expire_at`。座位三种状态（可选 / 锁定 / 已售）由服务层按票级状态 + `lock_until` 惰性算出，SQL 侧另有视图 `v_seat_state` 供报表使用。

## Status

已采纳。

## Consequences

`seat.seat_status` 列保留（需求附录有），但降级为冗余 + 供 CHECK 约束用；后端判断以 `ticket` 状态 + `seat.lock_until` 为准。超时不需要"扫座位"：查询时按 `now()` 判断即可，`@Scheduled` 只做状态清理（超时订单置已取消、座位释放），晚跑不产生错误结果。
