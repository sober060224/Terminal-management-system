# 0002 一个班次的所有写操作都拿同一把悲观锁

下单、退票、检票、取消班次——这些要动 `seat` / `orders` / `ticket` 的入口在事务第一句执行 `SELECT ... FROM schedule WHERE id = ? FOR UPDATE`，再动后面的表。锁顺序固定为 schedule → seat → orders → ticket，只有一个 `ScheduleLock.lock(scheduleId)` 入口，不分散写。改签要写两个班次，用 `ScheduleLock.lockAll(from, to)` 按 id 升序一次锁两个，避免两个方向相反的改签互相等锁。理由：一条 SQL 就正确，50 线程抢同一座位的演示脚本能现场跑出"1 个成功、49 个 409"；需求 3.4 允许"事务方案"，乐观锁要处理重试，课程项目里是纯负担。锁粒度粗到班次，被牺牲的是同班次不同座位的并行度，300 座的小船无所谓。

例外：支付、取消订单、超时清理只改本订单和它自己的票，不持班次锁，改用限定原状态的条件更新（`update orders set status='已支付' where id=? and status='待支付'`，按受影响行数判定成功与否）。退票/改签动票时同样用 `TicketRepository.markRefunded` 带上 `status='已支付'` 条件，重复提交只有一次能改到行。这样既不引入班次锁的排队，也不会出现"两个请求都读到待支付、都执行成功"。

**订单行也要单独加锁**（2026-11 补）。退票和改签都会改 `orders.total_amount`，而班次锁挡不住"同一订单的两张票并发退"：两张票属于不同班次，两个事务锁的是不同班次，`markRefunded` 的条件更新也只覆盖 `ticket.status`，于是两边都读到同一个旧总额、各自减一次，后提交的把前者覆盖掉（丢失更新）。所以取锁顺序里补上订单行：`OrdersRepository.findByIdForUpdate`（`@Lock(PESSIMISTIC_WRITE)` + `select o from Orders o where o.id = :id`），在班次锁之后、动座位之前取一次，锁顺序固定为 **schedule → orders → seat → ticket**。加 `@Version` 也能挡住，但要配重试，理由同下面的 Considered Options：课程项目里是纯负担，行锁只有一句 SQL。

## Status

已采纳。

## Considered Options

- 乐观锁（`version` 列 + 重试）：并发正确性依赖重试次数，演示时会出现"偶发失败"，答辩解释成本高。
- 只锁被选的座位行：两个乘客同时买不同座位时仍可能交叉更新 `sold_seats` 计数器，且退票 + 支付会争同一班次的两个不同行，锁顺序容易写错。

## Consequences

同一班次上的操作串行；压测 QPS 上限就是锁的吞吐，但演示场景只有几十个并发。所有涉及班次座位的写代码必须记住先 `ScheduleLock.lock`，漏一处就有幽灵并发；只动本订单状态的写操作用条件更新，不要用"先查再写"。
