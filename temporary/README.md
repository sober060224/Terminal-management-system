# temporary/

本目录放**可复现的本地环境与验收产物**，内容不进版本库（`.gitignore` 里只放行本文件），
但目录结构按仓库根固定，任何人在克隆后的仓库里跑脚本都会落到这里，不再依赖仓库外的路径。

| 子目录 | 内容 | 怎么产生 |
|---|---|---|
| `mysql-local/data/` | 本地自建 MySQL 实例的数据目录（端口 3307） | 首次由 `start.cmd` 使用；没有数据目录时先初始化：`mysqld --initialize-insecure --datadir=temporary\mysql-local\data` |
| `dbpractice-m0/` | M0 验收日志：`verify-db.log`、`verify-api-doc.log`、`verify-m0.log`、`verify-m0-browser.log` | `verify\verify-db.cmd`、`verify\verify-api-doc.cmd`、`verify\verify-m0.cmd`、`verify\verify-m0-browser.cmd` |
| `dbpractice-m3/` | M3 验收日志、浏览器截图（`shots/`）、`fixture.json` | `verify\verify-m3.cmd`、`verify\verify-m3-browser.cmd`、`verify\m3_fixture.py` |
| `plan-pr1-fix/`、`plan-pr2/` | PR 评审修复计划（过程资料） | 手工维护 |

## 从零到跑通

```cmd
REM 1. 起 MySQL(3307) + 后端(8089)；mysqld 依次从 FERRY_MYSQL_HOME / PATH / 常见安装目录找
start.cmd

REM 2. 另一个窗口：建表 + 造数
verify\verify-db.cmd
verify\verify-m3.cmd
verify\verify-m3-browser.cmd
```

需要的环境变量都能覆盖默认值：`FERRY_MYSQL_HOME`（MySQL 安装目录）、`FERRY_MYSQL_BIN`
（mysql 客户端）、`MYSQL_DATA_DIR`（数据目录）、`DB_PORT`（数据库端口）、
`FERRY_ARTIFACTS`（验收产物目录）、`FERRY_CHROME` / `CHROME_PATH`（浏览器路径）、
`FERRY_BASE`（前端验收访问的后端地址）。

删掉本目录下的产物再跑一遍，脚本会自己重建。
