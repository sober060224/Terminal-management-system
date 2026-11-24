package com.gdou.ferry.integration;

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
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单行悲观锁的真实库回归：同一订单的两张票挂在**不同班次**上并发退票，
 * 订单总额必须正好减到 0，不能出现丢失更新。
 * <p>
 * 为什么必须跨班次：同一班次的两张票并发退票时，班次行锁（{@code ScheduleLock}）
 * 本来就把两个事务串行化了，第二个事务一定能读到第一个事务提交后的新总额，
 * 所以「同班次并发」证明不了订单行锁的作用。跨班次时两笔事务互不阻塞，
 * 唯一的串行化手段就是订单行锁（{@code OrdersRepository.findByIdForUpdate}）。
 * <p>
 * 怎么做才确定会踩到竞态：给 {@code orders} 表挂一个临时触发器，在金额更新时
 * {@code SLEEP(3)}。触发器是在 UPDATE 语句执行期间跑的，此时行锁已经握在手里，
 * 于是另一笔事务必然落在「读旧值」的窗口里：
 * <ul>
 *   <li>修复后（先锁订单行再读）：另一笔事务在 {@code findByIdForUpdate} 上被挡住，
 *       等第一笔提交后才读到新总额 → 总额归零，用例通过。</li>
 *   <li>修复前（普通 {@code findById} 读订单）：另一笔事务在窗口里读到旧总额，
 *       两笔各自减一次 → 总额只减一次 → 用例失败（丢失更新）。</li>
 * </ul>
 * 需要真实库：设 {@code FERRY_IT_DB=1}（与后端同样的 DB_PORT/DB_PASSWORD）后
 * 跑 {@code mvnw.cmd -B -o test -Dtest=ConcurrentRefundIT}；默认 {@code mvnw test} 不跑它。
 */
@EnabledIfEnvironmentVariable(named = "FERRY_IT_DB", matches = "1")
@SpringBootTest
class ConcurrentRefundIT {

    /** 触发器里每笔金额更新停这么久，给另一笔事务留出读到旧总额的时间窗。 */
    private static final int SLEEP_SECONDS = 3;

    @Autowired
    private OrderService orderService;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> createdOrders = new ArrayList<>();
    private Long scheduleAId;
    private Long scheduleBId;
    private Long userId;
    private Long routeId;
    private Long shipId;

    @AfterEach
    void cleanup() {
        try {
            jdbc.execute("drop trigger if exists trg_it_slow_orders");
        } catch (Exception ignored) {
            // 触发器可能没建成功，清理失败不影响结论
        }
        for (Long orderId : createdOrders) {
            jdbc.update("delete from ticket where order_id = ?", orderId);
            jdbc.update("delete from orders where id = ?", orderId);
        }
        if (scheduleAId != null) {
            jdbc.update("delete from seat where schedule_id = ?", scheduleAId);
            jdbc.update("delete from schedule where id = ?", scheduleAId);
        }
        if (scheduleBId != null) {
            jdbc.update("delete from seat where schedule_id = ?", scheduleBId);
            jdbc.update("delete from schedule where id = ?", scheduleBId);
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
    void 跨班次并发退票不会丢订单金额() throws Exception {
        String tag = String.valueOf(System.nanoTime());
        String username = "it-refund-" + tag;
        jdbc.update("insert into user (username, password, role, name, phone, id_card, status) "
                        + "values (?, 'x', 'PASSENGER', 'IT乘客', ?, ?, 'ACTIVE')",
                username, phone(), idCard(1));
        userId = jdbc.queryForObject("select id from user where username = ?", Long.class, username);

        String routeName = "IT航线" + tag;
        jdbc.update("insert into route (route_name, start_port, end_port, duration_min) "
                + "values (?, '甲港', '乙港', 60)", routeName);
        routeId = jdbc.queryForObject("select id from route where route_name = ?", Long.class, routeName);

        String shipName = "IT船" + tag;
        jdbc.update("insert into ship (ship_name, total_seats, ship_type) values (?, 8, '普通船')", shipName);
        shipId = jdbc.queryForObject("select id from ship where ship_name = ?", Long.class, shipName);

        // 两个同航线的班次：同一订单的两张票分挂在这两个班次上
        scheduleAId = insertSchedule(4);
        scheduleBId = insertSchedule(5);

        Long seatA = jdbc.queryForObject(
                "select id from seat where schedule_id = ? order by id limit 1", Long.class, scheduleAId);
        Long seatB = jdbc.queryForObject(
                "select id from seat where schedule_id = ? order by id limit 1", Long.class, scheduleBId);

        // 一个已支付订单，两张已支付票，分属两个班次
        jdbc.update("insert into orders (schedule_id, passenger_id, total_amount, status, pay_time) "
                        + "values (?, ?, 200.00, '已支付', ?)",
                scheduleAId, userId, LocalDateTime.now());
        Long orderId = jdbc.queryForObject(
                "select max(id) from orders where passenger_id = ?", Long.class, userId);
        createdOrders.add(orderId);

        Long ticketA = insertTicket(orderId, scheduleAId, seatA, "IT并发A");
        Long ticketB = insertTicket(orderId, scheduleBId, seatB, "IT并发B");
        jdbc.update("update seat set seat_status = '已售', order_id = ?, lock_until = null where id in (?, ?)",
                orderId, seatA, seatB);

        // 金额更新时在数据库里停 3 秒：此时行锁已经握住，另一笔事务必然读到旧总额
        jdbc.execute("drop trigger if exists trg_it_slow_orders");
        jdbc.execute("create trigger trg_it_slow_orders before update on orders for each row "
                + "set @it_slow = sleep(" + SLEEP_SECONDS + ")");

        User user = new User();
        user.setId(userId);
        user.setUsername(username);
        user.setPassword("x");
        user.setRole(Role.PASSENGER);
        user.setStatus(UserStatus.ACTIVE);
        LoginUser loginUser = new LoginUser(user);

        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errors = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (Long ticketId : List.of(ticketA, ticketB)) {
            Thread thread = new Thread(() -> {
                try {
                    start.await(20, TimeUnit.SECONDS);
                    orderService.refundTicket(ticketId, "误购", loginUser);
                } catch (Throwable error) {
                    synchronized (errors) {
                        errors.add(error);
                    }
                }
            }, "refund-" + ticketId);
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(SLEEP_SECONDS * 4000L);
        }
        assertTrue(errors.isEmpty(), "并发退票抛了异常：" + errors);

        BigDecimal total = jdbc.queryForObject(
                "select total_amount from orders where id = ?", BigDecimal.class, orderId);
        assertEquals(0, total.compareTo(BigDecimal.ZERO),
                "订单总额应为 0（两笔各退 100），实际 " + total + " —— 丢了更新");
        assertEquals("已退票", jdbc.queryForObject(
                "select status from orders where id = ?", String.class, orderId));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from ticket where order_id = ? and status <> '已退票'", Integer.class, orderId));
    }

    private Long insertSchedule(int hoursFromNow) {
        LocalDateTime depart = LocalDateTime.now().plusHours(hoursFromNow);
        jdbc.update("insert into schedule (route_id, ship_id, depart_time, arrive_time, price, total_seats, status) "
                        + "values (?, ?, ?, ?, 100.00, 8, '售票中')",
                routeId, shipId, depart, depart.plusHours(2));
        return jdbc.queryForObject("select id from schedule where ship_id = ? order by id desc limit 1",
                Long.class, shipId);
    }

    private Long insertTicket(Long orderId, Long scheduleId, Long seatId, String name) {
        jdbc.update("insert into ticket (order_id, schedule_id, seat_id, passenger_name, id_card, phone, price, status) "
                        + "values (?, ?, ?, ?, ?, ?, 100.00, '已支付')",
                orderId, scheduleId, seatId, name, idCard(2), phone());
        return jdbc.queryForObject("select max(id) from ticket where order_id = ?", Long.class, orderId);
    }

    /** 18 位合法校验位身份证（GB 11643），两张票用不同号码，避免撞一人一票。 */
    private static String idCard(int seed) {
        String base = "440100" + "19900101" + String.format("%03d", seed);
        int[] weight = { 7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2 };
        String check = "10X98765432";
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (base.charAt(i) - '0') * weight[i];
        }
        return base + check.charAt(sum % 11);
    }

    private static String phone() {
        return "139" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode() % 100000000));
    }
}
