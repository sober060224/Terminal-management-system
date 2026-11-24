package com.gdou.ferry.integration;

import com.gdou.ferry.common.BizException;
import com.gdou.ferry.common.LoginUser;
import com.gdou.ferry.entity.Role;
import com.gdou.ferry.entity.User;
import com.gdou.ferry.entity.UserStatus;
import com.gdou.ferry.repository.SeatRepository;
import com.gdou.ferry.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

/**
 * P1-1（一个座位两个已支付订单）的真实库回归。
 * <p>
 * 不用 H2：这个问题只有在「Hibernate 读出座位实体 → 别的事务改写该行 → 本事务按实体全列写回」
 * 的真实行为下才会重现；H2 上的锁/写行为不同，跑绿了也不代表线上没问题。
 * <p>
 * 做法（计划里 M-1 定的方案）：用 {@code @MockitoSpyBean} 把 {@link SeatRepository}
 * 拦在置售那一刻——A 支付走到「准备置售座位」时用 {@link CountDownLatch} 通知另一个线程，
 * 那个线程把同一个座位抢走（锁过期被新订单接手的真实场景），然后 A 继续执行。
 * 修复后的实现是条件更新 {@code sellByOrder(seatId, orderId)}，座位归属已变 → 命中 0 行 →
 * 整单回滚并报 409；修复前是无条件全列写回，会把座位抢回来并出现「同座两个已支付订单」。
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
    @MockitoSpyBean
    private SeatRepository seatRepository;

    private final List<Long> createdOrders = new ArrayList<>();
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
    void 支付时座位被别的订单抢走则整单回滚且座位归属不变() throws Exception {
        String tag = String.valueOf(System.nanoTime());
        String username = "it-seat-" + tag;
        jdbc.update("insert into user (username, password, role, name, phone, id_card, status) "
                        + "values (?, 'x', 'PASSENGER', 'IT乘客', '13800000000', '440100199001010012', 'ACTIVE')",
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
                        + "values (?, ?, ?, 'IT乘客', '440100199001010012', '13800000000', 100.00, '待支付')",
                orderA, scheduleId, seatId);
        // A 下单时锁着座位（order_id = A，锁定未过期）
        jdbc.update("update seat set seat_status = '锁定', order_id = ?, lock_until = ? where id = ?",
                orderA, LocalDateTime.now().plusMinutes(15), seatId);

        // A 走到「准备置售座位」时通知 B，B 抢走座位（锁过期后被新订单接手的真实场景）
        CountDownLatch aReadyToSell = new CountDownLatch(1);
        CountDownLatch bTookTheSeat = new CountDownLatch(1);
        doAnswer((InvocationOnMock inv) -> {
            Long orderId = inv.getArgument(1);
            if (orderId.equals(orderA)) {
                aReadyToSell.countDown();
                assertTrue(bTookTheSeat.await(10, TimeUnit.SECONDS), "B 线程没有在 10 秒内抢到座位");
            }
            // Mockito spy 不能对 Spring Data 的 @Query 接口方法 callRealMethod（抽象方法），
            // 用完全相同的 UPDATE 谓词代替真实实现：命中 0 行 = 座位已不属于本订单。
            return jdbc.update("update seat set seat_status = '已售', lock_until = null, order_id = ? "
                    + "where id = ? and order_id = ? and seat_status = '锁定'", orderId, inv.getArgument(0), orderId);
        }).when(seatRepository).sellByOrder(anyLong(), anyLong());

        Thread b = new Thread(() -> {
            try {
                assertTrue(aReadyToSell.await(10, TimeUnit.SECONDS), "A 没有走到置售座位那一步");
                jdbc.update("update seat set seat_status = '锁定', order_id = ?, lock_until = ? where id = ?",
                        orderB, LocalDateTime.now().plusMinutes(15), seatId);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                bTookTheSeat.countDown();
            }
        }, "seat-thief");
        b.start();

        User user = new User();
        user.setId(userId);
        user.setUsername(username);
        user.setPassword("x");
        user.setRole(Role.PASSENGER);
        user.setStatus(UserStatus.ACTIVE);

        BizException ex = assertThrows(BizException.class,
                () -> orderService.payOrder(orderA, new LoginUser(user)));
        b.join(10_000);

        assertTrue(ex.getMessage().contains("座位已被其他订单占用"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        // 整单回滚：订单和票都还是待支付，座位仍归 B
        assertEquals("待支付", jdbc.queryForObject("select status from orders where id = ?", String.class, orderA));
        assertEquals("待支付", jdbc.queryForObject("select status from ticket where order_id = ?", String.class, orderA));
        Map<String, Object> seatRow = jdbc.queryForMap(
                "select seat_status, order_id from seat where id = ?", seatId);
        assertEquals("锁定", seatRow.get("seat_status"));
        assertEquals(orderB, ((Number) seatRow.get("order_id")).longValue());
    }

    @Test
    void 退票后座位被释放且释放只命中一行() {
        String tag = String.valueOf(System.nanoTime());
        String username = "it-rel-" + tag;
        jdbc.update("insert into user (username, password, role, name, phone, id_card, status) "
                        + "values (?, 'x', 'PASSENGER', 'IT乘客', '13800000000', '440100199001010012', 'ACTIVE')",
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

        Long orderId = insertOrder(userId, scheduleId, "100.00");
        jdbc.update("insert into ticket (order_id, schedule_id, seat_id, passenger_name, id_card, phone, price, status) "
                        + "values (?, ?, ?, 'IT乘客', '440100199001010012', '13800000000', 100.00, '待支付')",
                orderId, scheduleId, seatId);
        jdbc.update("update seat set seat_status = '锁定', order_id = ?, lock_until = ? where id = ?",
                orderId, LocalDateTime.now().plusMinutes(15), seatId);

        User user = new User();
        user.setId(userId);
        user.setUsername(username);
        user.setPassword("x");
        user.setRole(Role.PASSENGER);
        user.setStatus(UserStatus.ACTIVE);

        orderService.payOrder(orderId, new LoginUser(user));
        Long ticketId = jdbc.queryForObject("select id from ticket where order_id = ?", Long.class, orderId);

        orderService.refundTicket(ticketId, "误购", new LoginUser(user));

        // 释放座位只应命中它自己那一行
        verify(seatRepository).releaseByOrder(seatId, orderId);
        assertEquals("可选", jdbc.queryForObject("select seat_status from seat where id = ?", String.class, seatId));
        assertEquals(null, jdbc.queryForObject("select order_id from seat where id = ?", Long.class, seatId));
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
