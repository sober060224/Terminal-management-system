-- M3 验收种子：相对 NOW() 造 4 个测试班次（航线 1），座位由
-- trg_schedule_generate_seats 自动生成。每次运行追加一批，脚本按
-- 「route 1 + price 100/120 + 最大 id」挑最新一批，旧批次不影响断言。
--   FULL   : +3 天，票价 100 —— 预计全额退 100
--   WITHIN : +90 分钟，票价 100 —— 预计扣 20% 退 80
--   PAST   : -1 小时，票价 100 —— 固定 id 999901，发船班次不出现在列表、下单被拒
--   REBOOK : +3 天 1 小时，票价 120 —— 改签目标（同航线，补差价 +20）
--   NEARBY : +30 分钟，票价 130 —— 距发船不足 1 小时，改签窗口边界的靶子
USE ferry_system;

INSERT INTO schedule (route_id, ship_id, depart_time, arrive_time, price, total_seats, sold_seats, status)
VALUES
    (1, 1, NOW() + INTERVAL 3 DAY,
            NOW() + INTERVAL 3 DAY + INTERVAL 2 HOUR, 100.00, 32, 0, '售票中'),
    (1, 1, NOW() + INTERVAL 90 MINUTE,
            NOW() + INTERVAL 90 MINUTE + INTERVAL 2 HOUR, 100.00, 32, 0, '售票中'),
    (1, 1, NOW() + INTERVAL 3 DAY + INTERVAL 1 HOUR,
            NOW() + INTERVAL 3 DAY + INTERVAL 3 HOUR, 120.00, 32, 0, '售票中'),
    (1, 1, NOW() + INTERVAL 30 MINUTE,
            NOW() + INTERVAL 2 HOUR + INTERVAL 30 MINUTE, 130.00, 32, 0, '售票中');

-- 已发船班次用固定 id，因为 /api/schedules 会过滤掉它，脚本拿不到 id。
INSERT INTO schedule (id, route_id, ship_id, depart_time, arrive_time, price, total_seats, sold_seats, status)
VALUES (999901, 1, 1, NOW() - INTERVAL 1 HOUR,
        NOW() + INTERVAL 1 HOUR, 100.00, 32, 0, '售票中')
ON DUPLICATE KEY UPDATE
    depart_time = VALUES(depart_time),
    arrive_time = VALUES(arrive_time),
    status = '售票中';
