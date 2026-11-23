-- 轮渡码头客运管理系统 数据库脚本
-- 设计见 PLAN.md §1
-- 执行: mysql -u root -p --default-character-set=utf8mb4 < database.sql

DROP DATABASE IF EXISTS ferry_system;
CREATE DATABASE ferry_system CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE ferry_system;

-- ====================
-- 1. 表定义
-- ====================

-- 用户表
CREATE TABLE user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '登录账号',
    password VARCHAR(255) NOT NULL COMMENT 'BCrypt 密文',
    role ENUM('ADMIN', 'SELLER', 'PASSENGER') NOT NULL DEFAULT 'PASSENGER',
    name VARCHAR(50) NOT NULL COMMENT '真实姓名',
    phone VARCHAR(20) NOT NULL,
    id_card VARCHAR(18) NOT NULL COMMENT '18位身份证',
    status ENUM('PENDING', 'ACTIVE', 'REJECTED', 'DISABLED') NOT NULL DEFAULT 'ACTIVE',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_username (username),
    INDEX idx_id_card (id_card)
) ENGINE=InnoDB COMMENT='用户表';

-- 航线表
CREATE TABLE route (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    route_name VARCHAR(100) NOT NULL COMMENT '航线名称',
    start_port VARCHAR(50) NOT NULL COMMENT '出发港',
    end_port VARCHAR(50) NOT NULL COMMENT '到达港',
    duration_min INT NOT NULL COMMENT '航程时长（分钟）',
    status ENUM('正常', '停运') NOT NULL DEFAULT '正常',
    CHECK (duration_min > 0)
) ENGINE=InnoDB COMMENT='航线表';

-- 船舶表
CREATE TABLE ship (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    ship_name VARCHAR(50) NOT NULL UNIQUE,
    total_seats INT NOT NULL COMMENT '总座位数',
    ship_type ENUM('普通船', '高速船', '豪华船') NOT NULL,
    status ENUM('正常', '维修', '停用') NOT NULL DEFAULT '正常',
    CHECK (total_seats > 0 AND total_seats <= 500)
) ENGINE=InnoDB COMMENT='船舶表';

-- 班次表
CREATE TABLE schedule (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    route_id BIGINT NOT NULL,
    ship_id BIGINT NOT NULL,
    depart_time DATETIME NOT NULL COMMENT '发船时间',
    arrive_time DATETIME NOT NULL COMMENT '到达时间（可早于发船时间表示次日）',
    price DECIMAL(10,2) NOT NULL COMMENT '票价',
    total_seats INT NOT NULL COMMENT '冗余 ship.total_seats 快速查询',
    sold_seats INT NOT NULL DEFAULT 0 COMMENT '已售座位数（冗余列，后端不维护，真相在座位表）',
    status ENUM('待发布', '售票中', '已发船', '已完成', '已取消') NOT NULL DEFAULT '待发布',
    FOREIGN KEY (route_id) REFERENCES route(id),
    FOREIGN KEY (ship_id) REFERENCES ship(id),
    UNIQUE KEY uk_ship_depart (ship_id, depart_time) COMMENT 'ADR-0002: 同船同时刻唯一',
    INDEX idx_schedule_depart (depart_time, status) COMMENT '报表查询加速',
    CHECK (price >= 0),
    CHECK (total_seats > 0),
    CHECK (sold_seats >= 0 AND sold_seats <= total_seats)
) ENGINE=InnoDB COMMENT='班次表';

-- 座位表
CREATE TABLE seat (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    schedule_id BIGINT NOT NULL,
    seat_no VARCHAR(10) NOT NULL COMMENT '座位号 A1, A2, B1...',
    seat_status ENUM('可选', '已售', '锁定') NOT NULL DEFAULT '可选',
    lock_until DATETIME NULL COMMENT '锁定截止时间（跟随订单支付截止，默认 15 分钟）',
    order_id BIGINT NULL COMMENT '下单后写入（15分钟锁）',
    FOREIGN KEY (schedule_id) REFERENCES schedule(id),
    UNIQUE KEY uk_seat (schedule_id, seat_no) COMMENT '同班次同座位号唯一',
    INDEX idx_seat_schedule (schedule_id, seat_status),
    -- 座位号必须是「字母排 + 1~4 号」，防止触发器算错排号生成 [ \ ] ^ 之类的畸形值
    CONSTRAINT ck_seat_no CHECK (seat_no REGEXP '^[A-Z]{1,2}[1-4]$')
) ENGINE=InnoDB COMMENT='座位表';

-- 订单表（原名order改为orders避免保留字冲突，见ADR-0001）
CREATE TABLE orders (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    schedule_id BIGINT NOT NULL,
    passenger_id BIGINT NOT NULL COMMENT '购票人（不一定是乘客）',
    seller_id BIGINT NULL COMMENT '售票员ID（线下代买时填）',
    total_amount DECIMAL(10,2) NOT NULL COMMENT '订单总额（部分退票时递减）',
    status ENUM('待支付', '已支付', '已检票', '已完成', '已取消', '已退票') NOT NULL DEFAULT '待支付',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    pay_time DATETIME NULL,
    expire_at DATETIME NULL COMMENT '待支付订单15分钟过期时间',
    FOREIGN KEY (schedule_id) REFERENCES schedule(id),
    FOREIGN KEY (passenger_id) REFERENCES user(id),
    FOREIGN KEY (seller_id) REFERENCES user(id),
    INDEX idx_orders_passenger (passenger_id, status),
    INDEX idx_orders_expire (status, expire_at),
    CHECK (total_amount >= 0)
) ENGINE=InnoDB COMMENT='订单表';

-- 船票表
CREATE TABLE ticket (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    schedule_id BIGINT NOT NULL,
    seat_id BIGINT NOT NULL,
    passenger_name VARCHAR(50) NOT NULL COMMENT '乘客姓名',
    id_card VARCHAR(18) NOT NULL COMMENT '乘客身份证',
    phone VARCHAR(20) NOT NULL COMMENT '乘客手机号',
    price DECIMAL(10,2) NOT NULL COMMENT '该票票价',
    status ENUM('待支付', '已支付', '已检票', '已完成', '已取消', '已退票') NOT NULL DEFAULT '待支付',
    refund_amount DECIMAL(10,2) NULL COMMENT '实退现金（改签补差价记 0，退差额记差额）',
    refund_reason VARCHAR(200) NULL COMMENT '退票原因',
    refund_time DATETIME NULL,
    changed_from_ticket_id BIGINT NULL COMMENT '改签源票ID（ADR-0004）',
    changed_at DATETIME NULL COMMENT '改签时间',
    FOREIGN KEY (order_id) REFERENCES orders(id),
    FOREIGN KEY (schedule_id) REFERENCES schedule(id),
    FOREIGN KEY (seat_id) REFERENCES seat(id),
    FOREIGN KEY (changed_from_ticket_id) REFERENCES ticket(id),
    INDEX idx_ticket_schedule (schedule_id, status),
    INDEX idx_ticket_idcard (id_card, schedule_id, status) COMMENT '一人一班次一票判重',
    CHECK (price >= 0),
    CHECK (refund_amount IS NULL OR refund_amount >= 0)
) ENGINE=InnoDB COMMENT='船票表';

-- 检票记录表
CREATE TABLE check_in (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    ticket_id BIGINT NOT NULL,
    seller_id BIGINT NOT NULL COMMENT '检票员',
    check_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status ENUM('成功', '失败') NOT NULL,
    fail_reason VARCHAR(50) NULL COMMENT '失败原因：已检票/已过期/非本次班/已退票/票号不存在',
    FOREIGN KEY (ticket_id) REFERENCES ticket(id),
    FOREIGN KEY (seller_id) REFERENCES user(id),
    INDEX idx_checkin_ticket (ticket_id),
    INDEX idx_checkin_seller (seller_id, check_time),
    CONSTRAINT ck_checkin_reason CHECK (status = '失败' OR fail_reason IS NULL)
) ENGINE=InnoDB COMMENT='检票记录表';

-- ====================
-- 2. 视图（加分项）
-- ====================

-- 视图1: 座位实时状态（ADR-0003 惰性判断）
CREATE OR REPLACE VIEW v_seat_state AS
SELECT
    s.id,
    s.schedule_id,
    s.seat_no,
    CASE
        -- 票级状态才是真相：已支付/已检票/已完成 的座位都已经卖出去，不能再卖
        WHEN o.status IN ('已支付', '已检票', '已完成') THEN '已售'
        -- 选座锁（3 分钟）或下单锁（15 分钟）未过期
        WHEN s.lock_until IS NOT NULL AND s.lock_until > NOW() THEN '锁定'
        WHEN o.id IS NOT NULL AND o.status = '待支付' AND o.expire_at > NOW() THEN '锁定'
        ELSE '可选'
    END AS seat_status,
    s.order_id
FROM seat s
LEFT JOIN orders o ON s.order_id = o.id;

-- 视图2: 班次余票
-- 占用 = 已售 + 未过期的锁定，与后端 ScheduleQueryService 同口径；
-- 只数已支付的票会漏掉「已锁定但还没付」的座位，界面显示有票却买不到。
CREATE OR REPLACE VIEW v_schedule_remaining AS
SELECT
    sch.id AS schedule_id,
    sch.total_seats,
    COALESCE(SUM(CASE WHEN st.seat_status = '已售'
                           OR (st.seat_status = '锁定'
                               AND (st.lock_until IS NULL OR st.lock_until > NOW()))
                      THEN 1 ELSE 0 END), 0) AS sold_count,
    sch.total_seats - COALESCE(SUM(CASE WHEN st.seat_status = '已售'
                                            OR (st.seat_status = '锁定'
                                                AND (st.lock_until IS NULL OR st.lock_until > NOW()))
                                       THEN 1 ELSE 0 END), 0) AS remaining_seats
FROM schedule sch
LEFT JOIN seat st ON sch.id = st.schedule_id
GROUP BY sch.id, sch.total_seats;

-- 视图3: 售票员业绩
-- 收入取 SUM(orders.total_amount)：该字段是订单当前实收金额（退票递减、改签改为新票合计），
-- 直接对订单求和即可，不绕票表。订单数与票数用子查询分开统计，避免 JOIN 扇出。
CREATE OR REPLACE VIEW v_seller_performance AS
SELECT
    u.id AS seller_id,
    u.name AS seller_name,
    (SELECT COUNT(*) FROM orders o WHERE o.seller_id = u.id) AS total_orders,
    (SELECT COUNT(*)
       FROM ticket t JOIN orders o2 ON o2.id = t.order_id
      WHERE o2.seller_id = u.id
        AND t.status IN ('已支付', '已检票', '已完成', '已退票')) AS total_tickets,
    (SELECT COALESCE(SUM(o3.total_amount), 0)
       FROM orders o3
      WHERE o3.seller_id = u.id
        AND o3.pay_time IS NOT NULL
        AND o3.status <> '已取消') AS total_revenue
FROM user u
WHERE u.role = 'SELLER';

-- ====================
-- 3. 存储过程（加分项）
-- ====================

DELIMITER //

-- 批量生成班次（需求2.2功能3）
CREATE PROCEDURE sp_generate_schedules(
    IN p_route_id BIGINT,
    IN p_ship_id BIGINT,
    IN p_start_date DATE,
    IN p_end_date DATE,
    IN p_depart_time TIME,
    IN p_price DECIMAL(10,2)
)
BEGIN
    DECLARE v_current_date DATE;
    DECLARE v_depart_datetime DATETIME;
    DECLARE v_arrive_datetime DATETIME;
    DECLARE v_duration_min INT;
    DECLARE v_total_seats INT;

    -- 获取航线时长和船舶座位数
    SELECT duration_min INTO v_duration_min FROM route WHERE id = p_route_id;
    SELECT total_seats INTO v_total_seats FROM ship WHERE id = p_ship_id;

    SET v_current_date = p_start_date;

    WHILE v_current_date <= p_end_date DO
        SET v_depart_datetime = TIMESTAMP(v_current_date, p_depart_time);
        SET v_arrive_datetime = DATE_ADD(v_depart_datetime, INTERVAL v_duration_min MINUTE);

        -- 插入班次（可能因uk_ship_depart冲突而失败，忽略）
        INSERT IGNORE INTO schedule (route_id, ship_id, depart_time, arrive_time, price, total_seats, status)
        VALUES (p_route_id, p_ship_id, v_depart_datetime, v_arrive_datetime, p_price, v_total_seats, '待发布');

        SET v_current_date = DATE_ADD(v_current_date, INTERVAL 1 DAY);
    END WHILE;
END //

DELIMITER ;

-- ====================
-- 4. 触发器（加分项）
-- ====================

DELIMITER //

-- 检票唯一性约束（一张票只能成功检一次）
CREATE TRIGGER trg_checkin_unique
BEFORE INSERT ON check_in
FOR EACH ROW
BEGIN
    DECLARE v_existing_count INT;

    IF NEW.status = '成功' THEN
        SELECT COUNT(*) INTO v_existing_count
        FROM check_in
        WHERE ticket_id = NEW.ticket_id AND status = '成功';

        IF v_existing_count > 0 THEN
            SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '该票已成功检票，不能重复检票';
        END IF;
    END IF;
END //

-- 自动生成座位（班次创建后触发）
CREATE TRIGGER trg_schedule_generate_seats
AFTER INSERT ON schedule
FOR EACH ROW
BEGIN
    DECLARE v_seat_count INT DEFAULT 1;
    DECLARE v_row_letter VARCHAR(2);
    DECLARE v_row_num INT;
    DECLARE v_seat_in_row INT;

    WHILE v_seat_count <= NEW.total_seats DO
        -- 每排4座 A1-A4, B1-B4, C1-C4...
        -- 排号超过 26 时不能直接 CHAR(64+n)：第 27 排会算出 '[' 这种畸形字符，
        -- 这里用两级字母：27~52 排是 AA~AZ，53~78 排是 BA~BZ。
        SET v_row_num = CEIL(v_seat_count / 4);
        SET v_seat_in_row = ((v_seat_count - 1) MOD 4) + 1;
        SET v_row_letter = IF(v_row_num <= 26,
                              CHAR(64 + v_row_num),
                              CONCAT(CHAR(64 + FLOOR((v_row_num - 1) / 26)),
                                     CHAR(65 + MOD(v_row_num - 1, 26))));

        INSERT INTO seat (schedule_id, seat_no, seat_status)
        VALUES (NEW.id, CONCAT(v_row_letter, v_seat_in_row), '可选');

        SET v_seat_count = v_seat_count + 1;
    END WHILE;
END //

DELIMITER ;

-- ====================
-- 5. 演示数据
-- ====================

-- 用户（密码已BCrypt加密：admin123, seller1/seller123, 乘客/123456）
INSERT INTO user (username, password, role, name, phone, id_card, status) VALUES
('admin', '$2a$10$Fr.OoKG44B8HiPyHHT9z1O1XnQYM6N7aVZORh20/epKiA8IFUw8Dq', 'ADMIN', '系统管理员', '13800000001', '440100199001011001', 'ACTIVE'),
('seller1', '$2a$10$ccLqXqcjpnrwyMSSRrkWfudu5bJoEd3DfD6lnMaBzLDXZpfYWQDx2', 'SELLER', '张售票', '13800000002', '440100199002021002', 'ACTIVE'),
('seller2', '$2a$10$ccLqXqcjpnrwyMSSRrkWfudu5bJoEd3DfD6lnMaBzLDXZpfYWQDx2', 'SELLER', '李售票', '13800000003', '440100199003031003', 'ACTIVE'),
('passenger1', '$2a$10$NF24UCxyi1QMVBGU/JdAnuWLRh7UCCcmBww4YhwiEV1G.9BNgiwjW', 'PASSENGER', '王乘客', '13800000004', '440100199004041004', 'ACTIVE'),
('passenger2', '$2a$10$NF24UCxyi1QMVBGU/JdAnuWLRh7UCCcmBww4YhwiEV1G.9BNgiwjW', 'PASSENGER', '赵乘客', '13800000005', '440100199005051005', 'ACTIVE');

-- 航线
INSERT INTO route (route_name, start_port, end_port, duration_min, status) VALUES
('广州-香港', '广州港', '香港港', 120, '正常'),
('广州-澳门', '广州港', '澳门港', 90, '正常'),
('深圳-珠海', '深圳港', '珠海港', 150, '正常');

-- 船舶
INSERT INTO ship (ship_name, total_seats, ship_type, status) VALUES
('粤港01', 120, '高速船', '正常'),
('粤港02', 100, '普通船', '正常'),
('粤澳01', 80, '豪华船', '正常');

-- 班次（插入后触发器自动生成座位）
-- 时刻用相对 NOW() 的日期，否则演示数据过一天就全部过期、列表为空
INSERT INTO schedule (route_id, ship_id, depart_time, arrive_time, price, total_seats, status) VALUES
(1, 1, TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL 1 DAY), '08:00:00'), TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL 1 DAY), '10:00:00'), 150.00, 120, '售票中'),
(1, 1, TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL 1 DAY), '14:00:00'), TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL 1 DAY), '16:00:00'), 150.00, 120, '售票中'),
(2, 3, TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL 2 DAY), '09:00:00'), TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL 2 DAY), '10:30:00'), 200.00, 80, '售票中'),
(2, 3, TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL 2 DAY), '15:00:00'), TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL 2 DAY), '16:30:00'), 200.00, 80, '售票中'),
(3, 2, TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL 3 DAY), '07:00:00'), TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL 3 DAY), '09:30:00'), 120.00, 100, '售票中');

-- ====================
-- 6. 验证
-- ====================

-- 表数量：8
SELECT '表数量检查' AS 检查项, COUNT(*) AS 实际值, 8 AS 期望值
FROM information_schema.tables
WHERE table_schema = 'ferry_system' AND table_type = 'BASE TABLE';

-- 视图：3
SELECT '视图检查' AS 检查项, COUNT(*) AS 实际值, 3 AS 期望值
FROM information_schema.views
WHERE table_schema = 'ferry_system';

-- 触发器：2
SELECT '触发器检查' AS 检查项, COUNT(*) AS 实际值, 2 AS 期望值
FROM information_schema.triggers
WHERE trigger_schema = 'ferry_system';

-- 存储过程：1
SELECT '存储过程检查' AS 检查项, COUNT(*) AS 实际值, 1 AS 期望值
FROM information_schema.routines
WHERE routine_schema = 'ferry_system' AND routine_type = 'PROCEDURE';

-- 座位是否按班次座位数自动生成：实际座位行数 vs 期望座位数（应为 500 / 500）
SELECT '座位自动生成检查' AS 检查项,
       (SELECT COUNT(*) FROM seat) AS 实际值,
       (SELECT SUM(total_seats) FROM schedule) AS 期望值;

-- 座号格式：畸形座号（多位数排号、非法数字位）应为 0
SELECT '座号格式检查' AS 检查项,
       (SELECT COUNT(*) FROM seat WHERE seat_no NOT REGEXP '^[A-Z]{1,2}[1-4]$') AS 实际值,
       0 AS 期望值;

-- 每个班次的座位数是否都等于 total_seats（应返回 0 行）
SELECT '班次座位数一致性检查' AS 检查项, s.id AS schedule_id, s.total_seats, COUNT(se.id) AS 实际座位数
FROM schedule s
LEFT JOIN seat se ON se.schedule_id = s.id
GROUP BY s.id, s.total_seats
HAVING COUNT(se.id) <> s.total_seats;

-- 座号抽查：第 1/2/26/27/30 排
SELECT '座号抽查' AS 检查项, seat_no FROM seat
WHERE schedule_id = 1 AND (seat_no REGEXP '^[A-Z]{1,2}1$')
ORDER BY seat_no;

-- 三个视图的数据
SELECT '=== v_schedule_remaining ===' AS 标题;
SELECT * FROM v_schedule_remaining LIMIT 5;

SELECT '=== v_seller_performance ===' AS 标题;
SELECT * FROM v_seller_performance;

SELECT '=== v_seat_state 前10条 ===' AS 标题;
SELECT * FROM v_seat_state LIMIT 10;
