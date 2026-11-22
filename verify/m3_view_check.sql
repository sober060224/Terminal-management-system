-- 视图收入断言：100 元票、退票扣 20%，订单实收 20 元要计入售票员业绩。
-- 自包含：造一个临时售票员 + 已退票订单，断言后删掉，不污染库。
USE ferry_system;

-- 清理上一轮可能残留的测试行（票 → 订单 → 用户）
DELETE t FROM ticket t
  JOIN orders o ON o.id = t.order_id
 WHERE o.seller_id IN (SELECT id FROM user WHERE username = 'm3_view_check_seller');
DELETE FROM orders WHERE seller_id IN (SELECT id FROM user WHERE username = 'm3_view_check_seller');
DELETE FROM user WHERE username = 'm3_view_check_seller';

INSERT INTO user (username, password, role, name, phone, id_card, status)
VALUES ('m3_view_check_seller', '$2a$10$ccLqXqcjpnrwyMSSRrkWfudu5bJoEd3DfD6lnMaBzLDXZpfYWQDx2',
        'SELLER', '视图检查员', '13600000001', '440100199001010013', 'ACTIVE');
SET @seller_id = LAST_INSERT_ID();

SELECT id INTO @schedule_id FROM schedule WHERE status = '售票中' ORDER BY id LIMIT 1;
SELECT id INTO @seat_id FROM seat WHERE schedule_id = @schedule_id ORDER BY id LIMIT 1;

INSERT INTO orders (schedule_id, passenger_id, seller_id, total_amount, status, pay_time)
VALUES (@schedule_id, @seller_id, @seller_id, 20.00, '已退票', NOW());
SET @order_id = LAST_INSERT_ID();

INSERT INTO ticket (order_id, schedule_id, seat_id, passenger_name, id_card, phone,
                    price, status, refund_amount, refund_reason, refund_time)
VALUES (@order_id, @schedule_id, @seat_id, '视图检查员', '440100199001010013', '13600000001',
        100.00, '已退票', 80.00, '乘客退票', NOW());

SELECT '视图收入含退票手续费' AS 检查项,
       (SELECT total_revenue FROM v_seller_performance WHERE seller_id = @seller_id) AS 实际值,
       20 AS 期望值;

DELETE FROM ticket WHERE order_id = @order_id;
DELETE FROM orders WHERE id = @order_id;
DELETE FROM user WHERE id = @seller_id;
