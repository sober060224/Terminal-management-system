# 轮渡码头客运管理系统 —— 实施计划

需求见 `数据库实践-需求.html`（V1.0）。技术栈：Spring Boot 4.2.0 + Vue 3。
术语统一见 `GLOSSARY.md`；三个不可逆决策见 `docs/adr/0001`~`0004`。

不排人数，不排工期。下面的 M0–M6 是**构建顺序**，不是日历；每一档做完都能独立演示。

---

## 0. 已定决策

**D1 技术栈**
后端 **Spring Boot 4.2.0**（2026-11-19 发布的最新稳定版），Java 21，Maven；
Spring Data JPA / Hibernate 7.4 / Spring Security 7.2（session + CSRF）/ Jackson 3.1 / MySQL 8 + mysql-connector-j 26.10。
前端沿用同级 `../Software_Construction` 的 Vue 3.5 / Vite / Pinia / Vue Router / axios / ECharts，再加 `element-plus`
（需求 2.1 要求；乘客端用同一套组件，只是大按钮、大卡片，按需求 2.2 末尾"人力有限"的退路）。

Boot 4 与 3.x 的四个坑，写码前先知道：
- **Jackson 3**：`ObjectMapper` 在 `tools.jackson.databind`（旧 `com.fasterxml.jackson.databind` 不再有）；
  注解仍是旧包 `com.fasterxml.jackson.annotation.*`（jackson-annotations 2.21），`@JsonIgnore` / `@JsonFormat` 照旧。
  我们不直接 new ObjectMapper，影响为零。
- **Spring Security 7**：`../Software_Construction/config/SecurityConfig.java` 是 Security 6 写的，**不能直接拷**。
  照它的思路重写（约 1 小时）：`authorizeHttpRequests` + lambda DSL 不变，`and()` 彻底删除，其余按编译错误改。
- **Hibernate 7**：`@MappedSuperclass` / `@Column` / `@PrePersist` 不变；方言自动探测，不写 `spring.jpa.database-platform`。
- **starter 命名**：`spring-boot-starter-web` 仍可用（也新增 `spring-boot-starter-webmvc`，两者等价，别纠结）。
- 回退方案：出问题就把 parent 从 `4.2.0` 改成 `4.1.3`（上一 minor，同为 Security 7 / Jackson 3），配置与代码零改动。

**D2 并发控制只做一种：悲观锁。** 所有改座位的操作，事务第一句先 `SELECT ... FROM schedule WHERE id=? FOR UPDATE`
锁住该班次，再动 `seat` / `orders` / `ticket`。全系统锁顺序固定为 schedule → seat → orders → ticket，不会死锁。
理由：一条 SQL 就正确，50 线程并发脚本能现场演示"只有 1 个 201、49 个 409"；乐观锁要处理重试，
课程项目里是纯负担。需求 3.4 允许"事务或乐观锁"，这算事务方案。见 ADR-0002。

**D3 状态用字符串 + CHECK，不用数字码。** 答辩时表里直接看得懂。

**D4 一个 Vue 应用、三个 layout。** 单个应用按角色跳 `/admin`、`/seller`、`/`；座位图、订单卡片、检票面板
三个组件三端复用，不拆成三个工程。

**D5 表名 `order` → `orders`。** `ORDER` 是 MySQL 保留字，改后所有 SQL 与 JPA 映射免掉反引号；`user` 不是保留字，不动。
见 ADR-0001。

**D6 不排工期。** 只按 §3 的顺序推进，每个里程碑独立可演示；没有硬截止，所以裁剪清单里只砍没人看得见的功能。

**D7 业务口径**（第 2–3 轮评审定死，细节见 §1.1，写码时照这个，别临时改）：
- 一人一班次一票：判重键是 `ticket.id_card + schedule_id`，**不按账号**（帮家人买、代买都要成立）。
- 取消班次：`已支付 → 已退票`（`refund_amount`=原价、`refund_reason='班次取消'`）；只有未支付的才进 `已取消`。
  `已取消` 只代表"没付过钱"。
- 退票手续费基准：实时比票所属 `schedule.depart_time` 与服务端 `now()`，不存快照；改签后自然按新班次算。
- 收入 = 区间内订单 `orders.total_amount` 的合计。该字段是订单**当前实收/应收金额**：支付时写票面合计，退票递减实退，改签改为新票合计；
  "退票损失"统计必须排除改签（`ticket.refund_reason <> '改签'`）。
- 座位图 30 秒轮询 `v_seat_state`，不推 WebSocket；提交撞上已被占的座位返回 409 并高亮。
- 改签可链式（A→B→C），但只能从**链上最新那张票**改——旧票已是"已退票"，天然改不动。

---

## 1. 数据库（M0 之前必须定稿）

8 张表照需求附录，追加的列只在 3 张表上，每列都是业务规则逼出来的：

| 表 | 需求字段 | 追加 | 为什么必须加 |
|---|---|---|---|
| `user` | id, username, password, role, name, phone, id_card, status | `create_time` | 需求 1.4 要求"注册时间" |
| `route` | id, route_name, start_port, end_port, duration_min, status | — | |
| `ship` | id, ship_name, total_seats, ship_type, status | — | |
| `schedule` | id, route_id, ship_id, depart_time, arrive_time, price, total_seats, sold_seats, status | — | `arrive_time` 由 `depart_time + route.duration_min` 算出后落库，不实时算 |
| `seat` | id, schedule_id, seat_no, seat_status | `lock_until`, `order_id` | `lock_until` = 需求 3.4-1「选座锁 3 分钟」；`order_id` 下单时写入，座位是否被占由它决定（ADR-0003） |
| `orders` | id, schedule_id, passenger_id, seller_id, total_amount, status, create_time, pay_time | `expire_at` | 需求 3.4-1「下单锁 15 分钟」；表名从 `order` 改来，见 ADR-0001 |
| `ticket` | id, order_id, schedule_id, seat_id, passenger_name, id_card, price, status | `refund_amount`, `refund_reason`, `refund_time`, `changed_from_ticket_id`, `changed_at` | 前三列 = 需求 3.2-功能4；后两列 = 改签日志，因此不开第 9 张表（ADR-0004） |
| `check_in` | id, ticket_id, seller_id, check_time, status, fail_reason | — | 需求 4.4 已含 |

必须建立的约束（这是"数据库实践"课的得分点，别只靠 Java 校验）：

```sql
-- 座位：同一班次同一座位号唯一；状态取值受限
UNIQUE KEY uk_seat (schedule_id, seat_no)
CHECK (seat_status IN ('可选','已售','锁定'))

-- 检票：一张票只能成功检一次（需求 4.2「是否已检票」的数据库级保证）
-- 不能写 UNIQUE(ticket_id)：失败记录也要入库，同一张票可能失败多次。用触发器拦，见 §5 第 3 条。

-- 班次：同船同时刻不能有两个班次（需求 2.3-1）
UNIQUE KEY uk_ship_depart (ship_id, depart_time)   -- 只能拦住"完全相同时刻"，
                                                   -- 区间重叠仍需应用层校验，见 §6
-- 索引（统计报表与列表页都要用，答辩会问"为什么加这个索引"）
KEY idx_schedule_depart (depart_time, status)
KEY idx_orders_passenger (passenger_id, status)
KEY idx_ticket_schedule (schedule_id, status)
KEY idx_checkin_ticket (ticket_id)
```

一人一班次一票**不做唯一索引**（MySQL 8 没有部分唯一索引，且退票后本就该允许再买）：
事务内 `SELECT ... WHERE schedule_id=? AND id_card=? AND status IN ('已支付','已检票') FOR UPDATE`，
此时 schedule 行已被锁，检查是安全的。

方案设计阶段就要定的判断，落在这里免得漂移：座位三态由视图 `v_seat_state` 统一算出（`已售` 看 `orders.status`，
`锁定` 看 `lock_until`，其余为 `可选`），前后端都查它，不各写一遍 `CASE WHEN`。

### 1.1 业务细则（写码前定死，不留"到时候再说"）

| 项 | 定法 |
|---|---|
| `check_in.fail_reason` | 固定五值：`已检票` / `已过期` / `非本次班` / `已退票` / `票号不存在`。成功时 `status='成功'`、`fail_reason` 为 NULL |
| 跨零点班次 | `arrive_time` 允许早于 `depart_time`；前端判 `arrive < depart` 就显示 `+1天`，不支持跨两日 |
| 模拟支付 | `POST /api/orders/{id}/pay` 直接置"已支付" + 写 `pay_time`，不校验金额（需求写明"模拟"） |
| 退票金额 | 按**单张票**的 `ticket.price` 算手续费，不按订单总额；一单多票可只退其中几张 |
| 部分退票 | `orders.status` 保持"已支付"，`total_amount` 递减已退金额，前端打"部分退款"标签。票级状态是真相，订单只是汇总 |
| `schedule.sold_seats` | 建表保留该列但**不维护**（易与座位表脱节）；余票与占座一律按座位表实时统计（ADR-0003） |
| "未完成班次" | `status IN ('待发布','售票中')`；删航线/船舶遇这两种班次就拦 |
| 座位号生成 | 每排 4 座 `A1..A4, B1..B4, …`，末排不足 4 个就少几个，`total_seats` 决定行数 |
| 乘客取消"已支付"订单 | 不允许，只能走退票；"取消"是待支付专属 |
| 时区 | 全系统 `Asia/Shanghai`（`spring.jackson.time-zone` 与 MySQL `serverTimezone` 一致），存不带时区的 `DATETIME` |
| 身份证 | 18 位正则 + 校验位，服务端校验；前端只提示长度 |
| 操作日志 | **不建**第 9 张表；留痕靠 `check_in` + `ticket` 的改签字段 |
| 检票输入订单号 | 该单所有**未检票**的票一次性验完（一家四口一次放行）；输票号只验那一张 |
| 改签范围 | **限同航线**（起止港不变）；差价 = 新班次票价 − 原票价格，**多退少补**（贵则补、便宜则退差额）；跨航线不改签 |

---

## 2. 职责分组（不按人数摊派）

按**能力**分组，几个人都能映射；一个人做就是 A→B→C→D 串行，接口契约仍然必须先定。
表格里的字母是"这摊活"，不是"这五个人"。

| 组 | 负责 | 交付物 |
|---|---|---|
| **A** | 数据库 + 后端基础设施 | `database.sql`、实体/仓库、统一响应体、异常处理 |
| **B** | 交易核心（订单 / 座位 / 退票 / 改签） | 事务与锁、并发演示脚本 |
| **C** | 检票 + 统计报表 | 检票接口、5 张图的聚合 SQL、导出 |
| **D** | 前端三端 + 联调 | 三套 layout、座位图组件、ECharts 封装 |
| **E** | 测试 / 文档 / 答辩 | 并发演示脚本、ER 图、数据字典、PPT、演示数据 |

**接口先定，各自并行。** 骨架动工前先出 `docs/api.md`：每个接口的方法、路径、请求/响应 JSON、错误码。
后端照契约写，前端照契约 mock，第一版座位图跑通时联调一次。

---

## 3. 构建顺序（不排天数）

按"每一步都能独立跑起来、独立演示"的顺序推进；同一档里的东西可以并行，不并行也能一条线做完。

| 档 | 里程碑 | 做完就能演示这件事 |
|---|---|---|
| M0 | 骨架 | `database.sql` 执行 → 后端启动 → 前端登录页 → 三个角色各自跳对页面 |
| M1 | 基础数据 | 管理员建航线 → 船舶 → 班次 → 座位自动生成 → 座位图可看 |
| M2 | 能买票 | 选座 → 下单即锁座（15 分钟）→ 模拟支付 → 座位变"已售"；不付 15 分钟自动释放 |
| M3 | 能退改 | 退票三档手续费、改签多退少补、售票员代买代退、我的订单 |
| M4 | 能检票 | 检票（四种失败提示）+ 检票记录 + 我的船票 |
| M5 | 能看数 | 五张图 + 上座率标红 + Excel 导出 |
| M6 | 能答辩 | 视图 / 存储过程 / 触发器 / EXPLAIN / 并发演示脚本 / ER 图 / 数据字典 |

**M0 验收怎么跑**（`verify/verify-m0.cmd` 后端接口 + `frontend/e2e/m0-browser.mjs` 真实浏览器）：先 `mysql < database.sql` 建库，再起后端 8089，再 `npm run build` 把前端产物放进后端静态目录，最后跑这两个脚本——都要全绿才算 M0 过。

**起点是 `database.sql` + `docs/api.md` 同时定稿**——这两份不定，后面的活全是返工。M0→M2 是主链，M3 以后是加宽。

**裁剪顺序（时间不够时从上往下砍，都是需求里标"可选/模拟"的）：**
Excel 导出 → 打印预览 → 记住登录 7 天 → 密码错 5 次锁定 → 批量生成班次 → 真扫码（改输入票号，需求已写"模拟"）。
**绝不砍：** 并发控制、一人一票、退票三档手续费、检票校验、班次状态流转。

---

## 4. 每个模块的"做完"标准

- **模块一**：三个角色登录后各自跳到 `/admin`、`/seller`、`/`；乘客越权访问 `/admin` 返回 403；
  管理员能禁用账号且被禁账号立刻登不上（会话也要失效）。
- **模块二**：新建班次时，同船同时间段再建一个 → 报错；删除有未完成班次的航线 → 报错；
  班次建好后 `seat` 表自动出现 `total_seats` 行。
- **模块三**：① 并发脚本 50 线程抢同一座位 → 1 成功 49 失败；② 同一身份证同班次第二次购票 → 拒绝；
  ③ 下单不支付等 15 分钟 → 订单变"已取消"、座位回"可选"；④ 退票三种时间点 → 实退分别为 100%/80%/拒绝（发船后不落流水）。
- **模块四**：已检票再检 → "已检票"；非本班次 → "非本次班"；已退票 → "已退票"；发船后 → "已过期"。
  四种失败各有提示（需求 4.2）。
- **模块五**：5 张图数据与手写 SQL 核对一致；上座率 <50% 的班次标红。

---

## 5. 数据库课的加分项（答辩会问，M6 档做）

这门课叫**数据库实践**，老师看的是 SQL 本身，不只是功能能跑：

1. **视图 2 个**：`v_schedule_remaining`（班次余票 = total_seats − 已售）、`v_seller_performance`（售票员业绩聚合），
   外加 §1 的 `v_seat_state`。报表直接查视图。
2. **存储过程 1 个**：`sp_generate_schedules(route_id, ship_id, start_date, end_date, price)` ——
   需求 2.2 功能3 的"批量生成班次"，一行调用。
3. **触发器 1 个**：`trg_checkin_unique` —— 检票成功插入时若该 `ticket_id` 已有成功记录则报错
   （`uk_checkin` 的替代实现，同时演示"触发器"这个考点）。
4. **索引 + EXPLAIN**：对 `idx_schedule_depart` 做一次 `EXPLAIN` 前后对比，截图进报告。
5. **事务演示脚本**：`temporary/concurrency-demo.sh`（或 JMeter/python 脚本）50 并发抢座，展示 InnoDB 行锁。
6. **文档**：ER 图、数据字典、三范式说明、`CHECK`/`FK`/`UNIQUE` 的设计理由各一句话。

---

## 6. 已知风险

| 风险 | 应对 |
|---|---|
| 班次时间**区间重叠**无法用唯一索引拦（只能拦完全相同时刻） | 应用层在事务内查 `WHERE ship_id=? AND NOT (arrive_time<=? OR depart_time>=?)`，并在报告里写明"数据库约束表达不了区间不重叠，故在应用层校验"——这反而是加分回答 |
| 超时释放写成"扫全表" | 座位可见性用**惰性判断**（ADR-0003）：视图 `v_seat_state` 按 `now()` 实时算；`@Scheduled(fixedDelay=60s)` 只做状态清理（过期订单置"已取消"、过期 `seat.order_id` 置空），晚跑不影响正确性，不做延迟队列 |
| 5 张统计图写 5 段重复 SQL | 一段 `GROUP BY` + 前端按需取字段；或直接查视图 |
| 前端三端工作量最大 | 只做一套组件，用布局差异（侧边栏 / 底部 Tab / 大卡片）区分；座位图组件三端复用 |
| 联调时字段名对不上 | `docs/api.md` 是唯一契约；改契约必须全员同步 |

---

## 7. 可复用的现成代码（GitHub 检索结论）

- **骨架**：`../Software_Construction/backend/src/main/java/com/gdou/marinebio/` 的
  `common/{Result,PageResult,BaseEntity,BizException,GlobalExceptionHandler,LoginUser}.java`，以及前端
  `api/request.js`、`store/user.js`、`layouts/MainLayout.vue`、`components/{ChartBox,Pagination}.vue`
  —— 改包名 + 把 `Role` 枚举改成 `ADMIN/SELLER/PASSENGER` 即可，前端那四个文件原样能用。
  **不能直接拷的是 `config/SecurityConfig.java` 和 `config/SessionFreshnessFilter.java`**（编译在 Boot 3.5.6 / Security 6 之上），
  照思路重写。`pom.xml` 只抄依赖列表，`<parent>` 按 D1 写 `4.2.0`。
- **业务逻辑**（照着重写，该仓库无 license，不要整段复制）：`lmf06/Station-ticketing-management-system` 的
  `backend/app/services.py` —— `purchase_ticket`（锁班次→查重→自动分配座位）、`refund_ticket`（台账式记录）、
  `exchange_ticket`（新座位 + 差价 + 双向台账）、`sales_stats`。
- **座位状态机**（MIT，可复制，文件头注明来源）：`srujanmalakpata/seatlock` 的 `domain/Seat.java`
  （`holdFor/book/releaseFrom`）、`domain/Hold.java`（TTL + confirm/release/expire）、`service/HoldExpiryJob.java`。
  PostgreSQL 语法需改 MySQL：UUID→BIGINT、TIMESTAMPTZ→DATETIME、部分索引 `WHERE status='ACTIVE'`→普通索引。
- **检票**：无 MIT 现成件，自己写（半天）。形态可参考 `zhuatech-han/zhuatech-ticketdesk` 的 `ticket_scan` 表与
  判定码，但**不要复制代码**（非商用许可）。

---

## 8. 目录结构

```
Database_Practice/
├── database.sql              # 建库建表 + 视图/存储过程/触发器 + 演示数据（唯一来源）
├── docs/
│   ├── api.md                # 接口契约
│   ├── adr/                  # 决策记录（0001-0004）
│   ├── review-m0.md          # M0 审查报告
│   ├── er.drawio / er.svg    # ER 图
│   └── 数据字典.md
├── verify/                   # 验收脚本（不进 docs，命令入口 .cmd + python/node 断言）
├── backend/                  # SpringBoot，包名 com.gdou.ferry
├── frontend/                 # Vue 3 + Element Plus
├── start.cmd / stop.cmd      # Windows 一键启停
└── PLAN.md / GLOSSARY.md / README.md
```
