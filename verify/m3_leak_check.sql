-- 泄漏计数：验收连跑两遍后逐行对比，这些数字不该增长（seed 班次与座位除外）。
-- 单独成文件是因为中文状态值经 cmd 命令行传参会被 GBK 破坏，重定向进 mysql 才稳。
USE ferry_system;
SELECT
    (SELECT COUNT(*) FROM orders) AS orders_total,
    (SELECT COUNT(*) FROM ticket WHERE status IN ('待支付', '已支付', '已检票')) AS open_tickets,
    (SELECT COUNT(*) FROM seat WHERE seat_status = '锁定') AS locked_seats,
    (SELECT COUNT(*) FROM seat WHERE seat_status = '已售') AS sold_seats,
    (SELECT COUNT(*) FROM user WHERE username REGEXP '^b[0-9]{10}$') AS fixture_users;