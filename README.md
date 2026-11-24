# 轮渡码头客运管理系统

数据库实践课程项目。需求见 `数据库实践-需求.html`，实施计划见 `PLAN.md`，术语见 `GLOSSARY.md`。

技术栈：Spring Boot 4.2.0（Java 21）+ MySQL 8，前端 Vue 3 + Vite + Element Plus。

## 目录

| 路径 | 内容 |
|---|---|
| `database.sql` | 建库建表 + 视图 + 存储过程 + 触发器 + 演示数据（一次执行即可） |
| `docs/api.md` | 接口契约，前后端唯一口径 |
| `docs/adr/` | 不可逆决策记录（表名、并发、座位锁、改签） |
| `backend/` | Spring Boot 服务 |
| `frontend/` | Vue 3 前端 |
| `PLAN.md` | M0–M6 构建顺序与裁剪清单 |
| `start.cmd` / `stop.cmd` | Windows 一键启停（后端 8089 + 自建 MySQL 3307） |

## 跑起来

Windows 上先设置 `FERRY_REMEMBER_ME_KEY`（系统/用户环境变量，或启动 `start.cmd` 的终端），再启动整套（MySQL + 后端 8089），用 `stop.cmd` 停掉。
下面是手动步骤，脚本只是把它们串起来。

### 1. 建库

```bash
mysql -u root -p --default-character-set=utf8mb4 < database.sql
```
必须带 `--default-character-set=utf8mb4`，否则 Windows 下客户端默认 GBK，中文枚举值会写坏。

### 2. 起后端（端口 8089）

`start.cmd` 会先探测 3306 / 3307 哪个 MySQL 在跑，必要时拉起工作区自建实例，再起后端。

```powershell
$env:FERRY_REMEMBER_ME_KEY = '<稳定随机值>'
cd backend
mvnw.cmd -B -o compile
mvnw.cmd -B -o dependency:build-classpath -Dmdep.outputFile=target/cp.txt
run.cmd
```

`run.cmd` 直接用 `java` 启动（classpath 走 argfile），因为本机 `~/.m2` 里缺 `spring-boot-maven-plugin` 的离线依赖。
数据库连接读环境变量 `DB_USERNAME`（默认 root）、`DB_PASSWORD`；端口用 `DB_PORT`（默认 3306）。
Remember Me 使用 `FERRY_REMEMBER_ME_KEY` 作为签名密钥，必须设置稳定值；未设置时后端拒绝启动。
`stop.cmd` / `stop.ps1` 一键停后端（8089）和自建 MySQL 实例（3307），不碰系统 MySQL80 服务。

### 3. 起前端（端口 5174）

```bash
cd frontend
npm install
npm run dev
```

Vite 把 `/api` 代理到 `http://localhost:8089`，同源所以 Cookie 和 CSRF 令牌都正常。
生产构建：`npm run build`，产物直接落到 `backend/src/main/resources/static`，后端直接托管。

### 4. 验一验

```bash
cd frontend && npm run test             # 前端用例，条数以命令输出为准
cd backend  && mvnw.cmd -B -o test     # 后端用例，条数以命令输出为准（含退票手续费、改签窗口、订单结清判据）
verify\verify-db.cmd                   # 干净库导入 database.sql 并自检（8表/3视图/2触发器/1存储过程/500座位）
verify\verify-m0.cmd                   # 端到端接口验收：CSRF/注册/登录/鉴权/登出，全 assert
verify\verify-api-doc.cmd              # docs/api.md 里的响应示例 vs 实测响应
verify\verify-m0-browser.cmd           # 真实浏览器：三角色落点 + 登出换账号（需在 frontend 下跑 node e2e/m0-browser.mjs）
verify\verify-m3.cmd                   # 模块三端到端 + 并发：退改签规则、部分退票、双线程并发 + 退票/取消/改签三路并发 + 视图收入断言
verify\verify-m3-browser.cmd           # 真实浏览器：先造 fixture 订单，再真正提交退票与改签并断言 HTTP 200
python verify\concurrency_m3.py        # 模块三并发演示：50 线程抢同一座位 → 1 成功、49 个 409
python verify\concurrency_cross_schedule_m3.py  # 跨班次并发：一单两票先改签一张，再同时退 A/B 两张，总额必须归零
```

脚本要求后端已在 8089 运行、`database.sql` 已导入。浏览器脚本用本机
Chrome / Edge，没设置 `FERRY_CHROME` 时用 Playwright 自带 Chromium。

本地 MySQL 数据目录和所有验收日志/截图都在仓库内的 `temporary/`（见
`temporary/README.md`），不进版本库；`start.cmd` 与各验收脚本默认就写到这里，
所以克隆下来跑一遍不会污染仓库外的任何目录。

常用环境变量：

| 变量 | 用途 | 默认 |
|---|---|---|
| `FERRY_MYSQL_BIN` | `mysql` 客户端完整路径 | PATH 里的 `mysql` |
| `FERRY_REMEMBER_ME_KEY` | Remember Me 签名密钥 | 无（必填，部署时使用稳定随机值） |
| `FERRY_CHROME` | 浏览器可执行文件完整路径 | Playwright 自带 Chromium |
| `DB_PORT` | MySQL 端口 | 自动探测 3306 / 3307 |
| `DB_PASSWORD` | MySQL root 密码 | 无（必填） |
| `FERRY_BASE` | 后端地址 | `http://localhost:8089` |

## 演示账号

| 账号 | 密码 | 角色 |
|---|---|---|
| admin | admin123 | 管理员 |
| seller1 | seller123 | 售票员 |
| 乘客账号 | 123456 | 乘客（演示数据） |
