# 0004 改签不开第 9 张表，用票上的两个字段指回原票

需求 3.2 要求"改签后记录操作日志"，最容易的做法是加一张 `ticket_change` 表——但需求附录钉死了"8 张表"，多一张就要在数据字典和 ER 图上解释。做法：改签 = 原票置"已退票"（`refund_reason = '改签'`，`refund_amount` 记**实际退回的现金**：补差价记 0、退差额记差额）+ 新票插入，新票的 `changed_from_ticket_id` 指回原票、`changed_at` 记时间。订单 `total_amount` 同步改为**新票票面合计**，收入统计直接 `SUM(orders.total_amount)`。两张票都在 `ticket` 表里，一次改签就是一次票面状态迁移，日志从"顺着指针往上爬"就能还原。

## Status

已采纳。

## Consequences

改签链要自己去重（A→B→C 时只允许从最新那张改）；统计"退票损失"必须 `WHERE refund_reason <> '改签'`，否则改签会被算成退票损失；"改签日志"页面是 `ticket` 表的自查询，不是独立表。

补差价时 `refund_amount = 0`、订单 `total_amount` 只增不减，所以不能再拿"票面合计 − 退款额合计"当收入公式——收入一律取 `SUM(orders.total_amount)`。
