# 0003 座位占用靠 `seat.order_id` 和视图 `v_seat_state`，不做两个时钟

需求里有两条锁定时限：选座后锁 3 分钟、下单后锁 15 分钟。若两者都写 `seat.lock_until`，下单后第 3 分钟座位就会被"锁过期"释放掉，而订单还活着——超卖。所以拆成两件事：`seat.lock_until` 只表示**纯选座锁**（还没下单），`seat.order_id` 表示**已被订单占用**；订单到期看 `orders.expire_at`，座位是否真的被占只看订单状态。三种状态由视图 `v_seat_state` 统一算出，前后端都查它，不各写一遍 `CASE WHEN`。

## Status

已采纳。

## Consequences

`seat.seat_status` 列保留（需求附录有），但它降级为冗余 + 供 CHECK 约束用，判断一律以视图为准，避免两处逻辑漂移。超时不再需要"扫座位"：查询时按 `now()` 判断即可，`@Scheduled` 只做状态清理，晚跑不产生错误结果。