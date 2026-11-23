package com.gdou.ferry.integration;

import com.gdou.ferry.common.BizException;
import com.gdou.ferry.common.LoginUser;
import com.gdou.ferry.entity.Role;
import com.gdou.ferry.entity.User;
import com.gdou.ferry.entity.UserStatus;
import com.gdou.ferry.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-1 的真实库回归：支付时座位已被别的订单占用，必须整单回滚。
 * <p>
 * 不用 H2：这个问题只有在「Hibernate 读出座位实体 → 别的事务改写该行 → 本事务按实体全列写回」
 * 的真实行为下才会重现；H2 上的锁/写行为不同，跑绿了也不代表线上没问题。
 * <p>
 * 需要真实库：{@code mvnw.cmd -B -o test -Dtest=SeatOwnershipIT} 且设 {@code FERRY_IT_DB=1}
 * （与后端同样的 DB_PORT/DB_PASSWORD）。默认 {@code mvnw test} 不跑它。
 */
@EnabledIfEnvironmentVariable(named = "FERRY_IT_DB", matches = "1")
@SpringBootTest
class SeatOwnershipIT {

    @Autowired
    private OrderService orderService;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> createdOrders = new java.util.ArrayList<>();
    private Long scheduleId;
    private Long userId;
    private Long routeId;
    private Long shipId;

    @AfterEach
    void cleanup() {
        for (Long orderId : createdOrders) {
            jdbc.update("delete from ticket where order_id = ?", orderId);
            jdbc.update("delete from orders where id = ?", orderId);
        }
        if (scheduleId != null) {
            jdbc.update("delete from seat where schedule_id = ?", scheduleId);
            jdbc.update("delete from schedule where id = ?", scheduleId);
        }
        if (shipId != null) {
            jdbc.update("delete from ship where id = ?", shipId);
        }
        if (routeId != null) {
            jdbc.update("delete from route where id = ?", routeId);
        }
        if (userId != null) {
            jdbc.update("delete from user where id = ?", userId);
        }
    }

    @Test
    void 座位在支付前被别的订单占用时支付失败且归属不变() {
        // 每轮用带纳秒后缀的名字，跑完在 @AfterEach 里按 id 删干净
        String tag = String.valueOf(System.nanoTime());
        String username = "it-seat-" + tag;
        jdbc.update("insert into user (username, password, role, name, phone, id_card, status) "
                        + "values (?, 'x', 'PASSENGER', 'IT乘客', '13800000001', '440100199001010011', 'ACTIVE')",
                username);
        userId = jdbc.queryForObject("select id from user where username = ?", Long.class, username);

        String routeName = "IT航线" + tag;
        jdbc.update("insert into route (route_name, start_port, end_port, duration_min) "
                + "values (?, '甲港', '乙港', 60)", routeName);
        routeId = jdbc.queryForObject("select id from route where route_name = ?", Long.class, routeName);

        String shipName = "IT船" + tag;
        jdbc.update("insert into ship (ship_name, total_seats, ship_type) values (?, 4, '普通船')", shipName);
        shipId = jdbc.queryForObject("select id from ship where ship_name = ?", Long.class, shipName);

        jdbc.update("insert into schedule (route_id, ship_id, depart_time, arrive_time, price, total_seats, status) "
                        + "values (?, ?, ?, ?, 100.00, 4, '售票中')",
                routeId, shipId, LocalDateTime.now().plusHours(3), LocalDateTime.now().plusHours(4));
        scheduleId = jdbc.queryForObject("select id from schedule where ship_id = ? order by id desc limit 1",
                Long.class, shipId);

        Long seatId = jdbc.queryForObject(
                "select id from seat where schedule_id = ? order by id limit 1", Long.class, scheduleId);

        Long orderA = insertOrder(userId, scheduleId, "100.00");
        Long orderB = insertOrder(userId, scheduleId, "100.00");
        jdbc.update("insert into ticket (order_id, schedule_id, seat_id, passenger_name, id_card, phone, price, status) "
                + "values (?, ?, ?, 'IT乘客', '440100199001010011', '13800000001', 100.00, '待支付')",
                orderA, scheduleId, seatId);
        // A 下单时锁住座位，随后锁过期被 B 抢走（这里直接造出这个中间态）
        jdbc.update("update seat set seat_status = '锁定', order_id = ?, lock_until = ? where id = ?",
                orderB, LocalDateTime.now().plusMinutes(15), seatId);

        User user = new User();
        user.setId(userId);
        user.setUsername(username);
        user.setPassword("x");
        user.setRole(Role.PASSENGER);
        user.setStatus(UserStatus.ACTIVE);

        BizException ex = assertThrows(BizException.class,
                () -> orderService.payOrder(orderA, new LoginUser(user)));

        assertTrue(ex.getMessage().contains("座位已被其他订单占用"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        // 整单回滚：订单和票都还是待支付，座位仍归 B —— 旧实现会把它置成「已售」且归属仍是 B
        Map<String, Object> orderRow = jdbc.queryForMap(
                "select status from orders where id = ?", orderA);
        assertEquals("待支付", orderRow.get("status"));
        Map<String, Object> seatRow = jdbc.queryForMap(
                "select seat_status, order_id from seat where id = ?", seatId);
        assertEquals("锁定", seatRow.get("seat_status"));
        assertEquals(orderB, ((Number) seatRow.get("order_id")).longValue());
    }

    private Long insertOrder(Long passengerId, Long scheduleId, String amount) {
        jdbc.update("insert into orders (schedule_id, passenger_id, total_amount, status, expire_at) "
                        + "values (?, ?, ?, '待支付', ?)",
                scheduleId, passengerId, new java.math.BigDecimal(amount), LocalDateTime.now().plusMinutes(10));
        Long id = jdbc.queryForObject(
                "select max(id) from orders where passenger_id = ? and schedule_id = ?",
                Long.class, passengerId, scheduleId);
        createdOrders.add(id);
        return id;
    }
}