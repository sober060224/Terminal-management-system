# M0 骨架里程碑审查报告

审查对象：`University/three/Database_Practice/` 的 M0 里程碑（`database.sql` + `docs/api.md` + 可启动的后端 + 三角色可登录的前端）。
审查基线：独立审计两份驳回报告（`.pi/goals/goal_events.jsonl` 中 auditId `252bcb7b-…` 与 `8fb08e2e-…` 的 `report` 字段）逐条核查。
结论：**五项成功标准全部满足**；两份驳回报告里点名的缺陷已全部修掉，其中「登出后换账号登录必 403」这一阻断项有专门的失败-通过对照实验。
证据文件全部在 `temporary/dbpractice-m0/`，可直接重跑。

---

## 1. 成功标准逐条核查

### 标准 1：`database.sql` 可执行，8 表 / 3 视图 / 存储过程 / 触发器 / 种子数据符合 PLAN 语义

- 实现：`database.sql:1-400`（DROP/CREATE `ferry_system` → 8 表 → 3 视图 → 1 存储过程 → 2 触发器 → 种子数据 → 自检段）。
- 语义对齐（驳回报告指出的三处错误已修）：
  - 已检票/已完成座位不再判为「可选」：`database.sql` 中 `v_seat_state` 的 `WHEN o.status IN ('已支付','已检票','已完成') THEN '已售'`。
  - 售票员业绩不再被 JOIN 扇出放大：`v_seller_performance` 改为三个标量子查询（`total_orders` / `total_tickets` / `total_revenue` 按 `ticket.price` 求和）。
  - 座号不再出现 `[ \ ] ^`：触发器 `trg_schedule_generate_seats` 两级排号 `IF(v_row_num<=26, CHAR(64+v_row_num), CONCAT(CHAR(64+FLOOR((v_row_num-1)/26)), CHAR(65+MOD(v_row_num-1,26))))`，并由 `ck_seat_no CHECK (seat_no REGEXP '^[A-Z]{1,2}[1-4]$')` 兜底。
  - 自检段不再自证：座位数用 `COUNT(*)` 比 `SUM(total_seats)`，另有「座号格式检查 实际值/期望值 = 0」与「每班次座位数一致性 HAVING」两项独立判据（`database.sql` 末尾）。
- 证据：`temporary/dbpractice-m0/verify-db.log`（重跑命令 `verify/verify-db.cmd`，exit=0）
  - 表数量检查 8/8、视图检查 3/3、触发器检查 2/2、存储过程检查 1/1、座位自动生成检查 500/500、座号格式检查 0/0
  - 座号抽查 `A1 … Z1 → AA1 … AD1`（一级 26 排 + 二级排号正确）

### 标准 2：`docs/api.md` 覆盖 M0 全部接口契约（含 CSRF 会话流程），字段/错误码与后端一致

- 契约内容：`docs/api.md:9` 统一响应体、`:47` 错误码表、`:75` 路径前缀与角色、`:92` CSRF 令牌、`:122` 注册、`:194` 登录、`:262` 当前用户、`:304` 登出、`:330` UserDTO、`:348` CSRF 防护、`:364` 令牌生命周期（登出会 `deleteCookies`，必须重取）、`:376` 前端路由约定。
- 与实现一致性的自动核对：`verify/verify_api_doc.py`（重跑 `verify/verify-api-doc.cmd`，exit=0）逐个把文档里的 JSON 示例与真实响应做字段集合比对，结论「文档示例与实际响应一致」。
  - 顺带修掉一处真实不一致：注册响应带纳秒、登录响应不带。`backend/src/main/java/com/gdou/ferry/common/BaseEntity.java:21` 改为 `LocalDateTime.now().withNano(0)`，与库列 `DATETIME(0)` 对齐，文档 `:330` 写明格式为秒级。
- 证据：`temporary/dbpractice-m0/verify-api-doc.log`。

### 标准 3：后端能启动，登录、会话、角色鉴权可用

- 启动：`backend/run.cmd` → `java @target/run.args`（离线构建产物），Spring Boot 4.2.0 / Tomcat 8089 / Hibernate 7.4.15 `ddl-auto=validate`；语料见 `temporary/dbpractice-m0/verify-m0.log` 头部（后端已就绪）。
- 鉴权代码：`backend/src/main/java/com/gdou/ferry/config/SecurityConfig.java:104` 登出删 `JSESSIONID` 与 `XSRF-TOKEN`；角色过滤 `/api/admin/**` 仅 `ADMIN`、`/api/seller/**` `SELLER`+`ADMIN`；401/403 走 `ErrorCodes.NOT_LOGGED_IN` / `FORBIDDEN`。
- 证据：`temporary/dbpractice-m0/verify-m0.log`（`verify/verify-m0.cmd`，exit=0，39 项断言全 PASS，含：三角色登录 200 + 角色断言、错误密码 401 `AUTH_FAILED`、未登录 401 `NOT_LOGGED_IN`、乘客/售票员访问 `/api/admin/users` 403 `FORBIDDEN`、管理员访问同路径 **404**（M0 没有该接口，是角色过滤放行后才落到 404）、登出后再登录、CSRF 缺失 403）。
- 单测：`backend/src/test/java/com/gdou/ferry/{common,service}/*Test.java`，`mvnw.cmd -B -o test` → `Tests run: 14, Failures: 0` / `BUILD SUCCESS`。

### 标准 4：前端在真实浏览器中完成「登录页 → 三角色各自落到 PLAN 指定页面」

- 落点定义：`PLAN.md:35`、`PLAN.md:162`（`/admin`、`/seller`、`/`）；实现 `frontend/src/router/index.js:74` 的 `LANDING`（`PASSENGER: '/'`）与 `:27` 的根路由 `meta.roles`。
- 证据：`temporary/dbpractice-m0/verify-m0-browser.log`（重跑 `verify/verify-m0-browser.cmd`，exit=0，18 项断言全 PASS），用本机 Chrome（`frontend/e2e/m0-browser.mjs`）：
  - 管理员 → `#/admin` 且 `.page-title` 文本为「管理后台」；售票员 → `#/seller` /「售票工作台」；乘客 → `#/` /「乘客服务」
  - 未登录访问 `#/admin` → 被踢回 `#/login` 且带 `redirect` 参数
  - 每个角色断言「浏览器无 JS 报错 / 无 ≥400 响应」（favicon 已用 `<link rel="icon" href="data:,">` 消掉）
- 前端单测：`npm run test -- --run` → 3 文件 / 17 用例通过，其中 `frontend/src/api/request.test.js` 5 用例专测 CSRF 令牌时序，`frontend/src/router/guard.test.js` 直接测 `router/index.js` 导出的 `checkAccess`。

### 标准 5：端到端验收可复现，不是只靠打印或预置脏数据

- 四个 `verify/*.cmd` 入口，输出全部留档：`verify-db.cmd` / `verify-m0.cmd` / `verify-m0-browser.cmd` / `verify-api-doc.cmd`。
- `verify/verify_m0.py` 的账号全部按时间戳现造（`m0p<ts>` 等），不依赖种子用户；断言用 `check(名称, 实际, 期望)`，任何不符即非零退出；`verify/verify_m0.py:173` 起是角色过滤判据（管理员 404 / 其他角色 403 成对比较）。
- 数据库侧 `verify/verify-db.cmd` 先 `DROP DATABASE` 再整份导入，因此上面的日志都是「干净库」上跑出来的（导入时间见 `verify-db.log` 的 `Created` 列）。

---

## 2. 已核实事实（本轮修掉的缺陷）

| # | 缺陷（来自驳回报告） | 修复位置 | 验证方式 |
|---|----------------------|----------|----------|
| 1 | **阻断**：`request.js` 缓存了已解决的 CSRF Promise，登出删 Cookie 后换账号登录必 403 | `frontend/src/api/request.js:64-77`（`csrfPending` 只在请求进行中复用，`finally` 里清空；写操作前 `hasCsrfCookie()` 判 Cookie） | `frontend/src/api/request.test.js` 5 用例；**反向验证**：临时改回 `csrfReady` 旧写法跑同一套用例 → 2 项失败（「登出删掉 Cookie 后再登录，必须重新取令牌」「并发的写操作只取一次令牌」），改回新写法 17 用例全过。浏览器侧另有 e2e「退出登录 → 换账号登录」4 项断言 |
| 2 | 乘客落点与 PLAN 矛盾（代码是 `/passenger`，PLAN 写 `/`） | `frontend/src/router/index.js:27,74`；`frontend/src/layouts/` 三套 layout | e2e 断言 `#/` + 「乘客服务」 |
| 3 | 只有 `MainLayout.vue`，没有「三套 layout」 | `frontend/src/layouts/{LayoutShell,AdminLayout,SellerLayout,PassengerLayout}.vue`（共用外壳 + 三套配色/菜单） | 构建产物与 e2e 三端页面标题 |
| 4 | `docs/api.md` 成功示例缺 `code` / `createTime` | `docs/api.md:122-330` 四个示例补齐；`:330` 写明时间格式 | `verify-api-doc.log` |
| 5 | 验收脚本用不存在的 `/api/admin/users` 断言「不是 403」＝空验证 | `verify/verify_m0.py:173` 改成「管理员必须 404、其他角色必须 403」成对比较 | `verify-m0.log` 对应两条断言 |
| 6 | 「14 项断言全 PASS」没有留档 | 四个 `.cmd` 全部 `> log` + `type log`，`exit /b %ERRORLEVEL%` | `temporary/dbpractice-m0/*.log` 四个文件 |
| 7 | 前端 CSRF 从未初始化（第一份报告） | 网络层请求拦截器自动补取；登录页不再依赖「首屏调一次」的约定 | 干净浏览器 e2e 首次写操作成功 |
| 8 | 注册响应时间戳带纳秒、登录不带（本轮新发现） | `backend/src/main/java/com/gdou/ferry/common/BaseEntity.java:21` | `verify-api-doc.log` 实测 `createTime` 均为秒级 |

---

## 3. 未覆盖项与已知限制

1. **业务接口尚未实现（M0 范围之外）**：`/api/admin/**`、`/api/seller/**` 在 M0 没有任何业务方法，所以「管理员 404」是**预期行为**而不是缺陷；航线/班次/售票/检票/统计接口从 M1 起加，`docs/api.md:75` 的路径前缀表已写明。
2. **并发与锁**：座位锁（3 分钟 / 15 分钟）、`FOR UPDATE` 串行化、`v_seat_state` 的惰性判定都只在 `PLAN.md` 与 `database.sql` 的视图层落地，M0 没有写入口，无法在端到端里验证抢座行为；这属于 M1/M2 的验收范围。
3. **触发器/存储过程只走了正常路径**：`trg_checkin_unique`（重复检票应 `SIGNAL` 45000）、`sp_generate_schedules`（批量生成班次）没有负向用例；`verify-db.log` 只证明它们存在且座位生成正确。
4. **后端单测是纯单元测试**（`mvnw -o test` 不加载 Spring 上下文），`SecurityConfig` 的过滤链正确性只由端到端断言覆盖，没有 `MockMvc` 层测试。
5. **MySQL 实例不是系统服务**：官方 `MySQL80` 服务当前是 Stopped（当前账号也没有启动它的权限），验收用的是 `temporary/mysql-local/data` 上的自建实例（端口 3307，`mysqlx=OFF`）。这影响「换机器能否一键跑」，不影响上述结论；`start.cmd` 与 `verify/verify-db.cmd` 会自动探测 3306 / 3307，`DB_PORT` / `MYSQL_BIN` 都是可覆盖变量。
6. **浏览器验收只用 Chrome**：`frontend/e2e/m0-browser.mjs` 优先用 `FERRY_CHROME` / `CHROME_PATH`，否则在常见安装位置找 Chrome，都找不到才用 Playwright 自带 Chromium；没有 Firefox/Safari/移动端视口验证。
7. **没有 CI**：四个 `.cmd` 手动执行；仓库里没有 workflow 文件。

---

## 4. 复现步骤

```bat
rem 1) 起库：直接跑 start.cmd（自动探测 3306 / 3307，系统 MySQL80 没起就用自建实例）
University\three\Database_Practice\start.cmd

rem 2) 导入并自检 database.sql
University\three\Database_Practice\verify\verify-db.cmd


rem 3) 后端已由 start.cmd 一起起了（8089）；若单独跑：
cd University\three\Database_Practice\backend
run.cmd

rem 4) 三份验收
verify\verify-m0.cmd            rem 接口/会话/角色
verify\verify-api-doc.cmd       rem 文档示例 vs 实测响应
verify\verify-m0-browser.cmd    rem 真实浏览器三角色落点 + 登出换账号
```

单元测试：`backend\mvnw.cmd -B -o test`（14 用例）、`cd frontend && npm run test -- --run`（17 用例）。
所有日志会覆盖写到 `temporary/dbpractice-m0/`（该目录按仓库约定保留，不入版本库）。