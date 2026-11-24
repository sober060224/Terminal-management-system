package com.gdou.ferry.service;

import com.gdou.ferry.common.BizException;
import com.gdou.ferry.common.LoginUser;
import com.gdou.ferry.entity.Orders;
import com.gdou.ferry.entity.Role;
import com.gdou.ferry.entity.Schedule;
import com.gdou.ferry.entity.Seat;
import com.gdou.ferry.entity.Ticket;
import com.gdou.ferry.entity.User;
import com.gdou.ferry.entity.UserStatus;
import com.gdou.ferry.repository.OrdersRepository;
import com.gdou.ferry.repository.SeatRepository;
import com.gdou.ferry.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private SeatRepository seatRepository;
    @Mock
    private OrdersRepository ordersRepository;
    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private ScheduleLock scheduleLock;

    private OrderService service;
    private final LoginUser passenger = passenger(99L);
    private Schedule targetSchedule;
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 11, 21, 12, 0);

    private static LoginUser passenger(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("passenger" + id);
        user.setPassword("x");
        user.setRole(Role.PASSENGER);
        user.setStatus(UserStatus.ACTIVE);
        return new LoginUser(user);
    }

    @BeforeEach
    void setUp() {
        service = new OrderService(seatRepository, ordersRepository, ticketRepository, scheduleLock,
                Clock.fixed(FIXED_NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()));
        ReflectionTestUtils.setField(service, "orderPayMinutes", 15L);
    }

    @Test
    void 退票按实退金额递减订单总额() {
        Orders order = order(1L, 99L, new BigDecimal("150.00"));
        Ticket ticket = ticket(10L, 1L, new BigDecimal("100.00"));

        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(scheduleLock.lock(1L)).thenReturn(schedule(FIXED_NOW.plusHours(1)));
        when(ordersRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(ticketRepository.markRefunded(anyLong(), any(), anyString(), any())).thenReturn(1);
        // 同一张单还有一张有效票，所以订单不能置「已退票」
        when(ticketRepository.countByOrderIdAndStatusIn(anyLong(), any())).thenReturn(1L);

        Ticket refunded = service.refundTicket(10L, null, passenger);

        assertEquals("已退票", refunded.getStatus());
        assertEquals(0, new BigDecimal("80.00").compareTo(refunded.getRefundAmount()));
        assertEquals(0, new BigDecimal("70.00").compareTo(order.getTotalAmount()));
        assertEquals("已支付", order.getStatus());
        assertEquals(FIXED_NOW, refunded.getRefundTime());
        verify(seatRepository).releaseByOrder(11L, 1L);
    }

    @Test
    void 最后一张有效票退掉后订单变已退票() {
        Orders order = order(1L, 99L, new BigDecimal("100.00"));
        Ticket ticket = ticket(10L, 1L, new BigDecimal("100.00"));

        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(scheduleLock.lock(1L)).thenReturn(schedule(FIXED_NOW.plusHours(5)));
        when(ordersRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(ticketRepository.markRefunded(anyLong(), any(), anyString(), any())).thenReturn(1);
        when(ticketRepository.countByOrderIdAndStatusIn(anyLong(), any())).thenReturn(0L);

        service.refundTicket(10L, null, passenger);

        // 全额退：总额归零，判据仍走有效票计数而不是金额
        assertEquals(0, new BigDecimal("0.00").compareTo(order.getTotalAmount()));
        assertEquals("已退票", order.getStatus());
    }

    @Test
    void 两张票都按手续费退掉后订单也变已退票() {
        Orders order = order(1L, 99L, new BigDecimal("200.00"));
        Ticket first = ticket(10L, 1L, new BigDecimal("100.00"));
        Ticket second = ticket(11L, 1L, new BigDecimal("100.00"));
        second.setSeatId(12L);

        when(ticketRepository.findById(10L)).thenReturn(Optional.of(first));
        when(ticketRepository.findById(11L)).thenReturn(Optional.of(second));
        when(ordersRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(scheduleLock.lock(1L)).thenReturn(schedule(FIXED_NOW.plusHours(1)));
        when(ticketRepository.markRefunded(anyLong(), any(), anyString(), any())).thenReturn(1);
        when(ticketRepository.countByOrderIdAndStatusIn(anyLong(), any())).thenReturn(1L, 0L);

        service.refundTicket(10L, null, passenger);
        // 每张退 80（扣 20%），总额 200 → 120，还有一张有效票所以仍是已支付
        assertEquals(0, new BigDecimal("120.00").compareTo(order.getTotalAmount()));
        assertEquals("已支付", order.getStatus());

        service.refundTicket(11L, null, passenger);
        assertEquals(0, new BigDecimal("40.00").compareTo(order.getTotalAmount()));
        assertEquals("已退票", order.getStatus());
    }

    @Test
    void 改签后退票按票所属班次算手续费() {
        // 订单初次下单的班次是 1L（5 小时后发船，全额退）；票已改签到 2L（1.5 小时后，扣 20%）
        Orders order = order(1L, 99L, new BigDecimal("100.00"));
        Ticket ticket = ticket(10L, 1L, new BigDecimal("100.00"));
        ticket.setScheduleId(2L);

        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(scheduleLock.lock(2L)).thenReturn(schedule(FIXED_NOW.plusMinutes(90)));
        when(ordersRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(ticketRepository.markRefunded(anyLong(), any(), anyString(), any())).thenReturn(1);
        when(ticketRepository.countByOrderIdAndStatusIn(anyLong(), any())).thenReturn(0L);

        Ticket refunded = service.refundTicket(10L, null, passenger);

        // 锁错了班次会按 1L 全额退 100
        assertEquals(0, new BigDecimal("80.00").compareTo(refunded.getRefundAmount()));
        assertEquals(0, new BigDecimal("20.00").compareTo(order.getTotalAmount()));
    }

    @Test
    void 重复退票被条件更新挡住() {
        Orders order = order(1L, 99L, new BigDecimal("100.00"));
        Ticket ticket = ticket(10L, 1L, new BigDecimal("100.00"));

        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(scheduleLock.lock(1L)).thenReturn(schedule(FIXED_NOW.plusHours(1)));
        when(ordersRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(ticketRepository.markRefunded(anyLong(), any(), anyString(), any())).thenReturn(0);

        assertThrows(BizException.class, () -> service.refundTicket(10L, null, passenger));
    }

    @Test
    void 改签补差价订单总额改为新票合计() {
        Orders order = order(1L, 99L, new BigDecimal("100.00"));
        Ticket ticket = ticket(10L, 1L, new BigDecimal("100.00"));
        Seat targetSeat = targetSeat();
        stubRebook(order, ticket, new BigDecimal("120.00"), targetSeat);

        Ticket newTicket = service.rebookTicket(10L, 2L, 20L, passenger);

        assertEquals(0, new BigDecimal("120.00").compareTo(order.getTotalAmount()));
        // 只补不退：原票 refund_amount 记 0（实际退回现金为 0）。
        // markRefunded 是 bulk update，内存里的 ticket 不再带回退票字段，断言条件更新收到的参数。
        verify(ticketRepository).markRefunded(eq(10L), eq(BigDecimal.ZERO), eq("改签"), any());
        assertEquals(0, new BigDecimal("120.00").compareTo(newTicket.getPrice()));
        assertEquals(10L, newTicket.getChangedFromTicketId());
        assertEquals("13800138000", newTicket.getPhone());
        // 置售走条件更新（座位已被别人抢走时 0 行 → 409），不再直接改实体
        verify(seatRepository).sellTargetSeat(20L, 2L, 1L);
    }

    @Test
    void 改签退差额订单总额递减差额() {
        Orders order = order(1L, 99L, new BigDecimal("100.00"));
        Ticket ticket = ticket(10L, 1L, new BigDecimal("100.00"));
        stubRebook(order, ticket, new BigDecimal("80.00"), targetSeat());

        service.rebookTicket(10L, 2L, 20L, passenger);

        // 多退少补退差额：原票实退 20，订单总额 100 → 80
        verify(ticketRepository).markRefunded(eq(10L), eq(new BigDecimal("20.00")), eq("改签"), any());
        assertEquals(0, new BigDecimal("80.00").compareTo(order.getTotalAmount()));
    }

    @Test
    void 改签零差价订单总额不变且仍已支付() {
        Orders order = order(1L, 99L, new BigDecimal("100.00"));
        Ticket ticket = ticket(10L, 1L, new BigDecimal("100.00"));
        stubRebook(order, ticket, new BigDecimal("100.00"), targetSeat());

        service.rebookTicket(10L, 2L, 20L, passenger);

        assertEquals(0, new BigDecimal("100.00").compareTo(order.getTotalAmount()));
        assertEquals("已支付", order.getStatus());
    }

    @Test
    void 改签不影响同一订单的其他船票() {
        Orders order = order(1L, 99L, new BigDecimal("150.00"));
        Ticket ticket = ticket(10L, 1L, new BigDecimal("100.00"));
        Ticket sibling = ticket(11L, 1L, new BigDecimal("50.00"));
        sibling.setSeatId(12L);
        stubRebook(order, ticket, new BigDecimal("120.00"), targetSeat());

        service.rebookTicket(10L, 2L, 20L, passenger);

        assertEquals(0, new BigDecimal("170.00").compareTo(order.getTotalAmount()));
        assertEquals("已支付", order.getStatus());
        assertEquals("已支付", sibling.getStatus());
        assertEquals(0, new BigDecimal("50.00").compareTo(sibling.getPrice()));
    }

    @Test
    void 原班次距发船不足一小时不能改签() {
        Orders order = order(1L, 99L, new BigDecimal("100.00"));
        Ticket ticket = ticket(10L, 1L, new BigDecimal("100.00"));
        Schedule origin = schedule(FIXED_NOW.plusMinutes(30));
        origin.setRouteId(1L);
        Schedule target = schedule(FIXED_NOW.plusHours(6));
        target.setId(2L);
        target.setRouteId(1L);

        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(ordersRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(scheduleLock.lockAll(anyLong(), anyLong())).thenReturn(Map.of(1L, origin, 2L, target));

        BizException ex = assertThrows(BizException.class,
                () -> service.rebookTicket(10L, 2L, 20L, passenger));

        assertTrue(ex.getMessage().contains("不足 1 小时"));
    }

    @Test
    void 目标班次距发船不足一小时不能改签() {
        Orders order = order(1L, 99L, new BigDecimal("100.00"));
        Ticket ticket = ticket(10L, 1L, new BigDecimal("100.00"));
        Schedule origin = schedule(FIXED_NOW.plusHours(5));
        origin.setRouteId(1L);
        Schedule target = schedule(FIXED_NOW.plusMinutes(40));
        target.setId(2L);
        target.setRouteId(1L);

        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(ordersRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(scheduleLock.lockAll(anyLong(), anyLong())).thenReturn(Map.of(1L, origin, 2L, target));

        BizException ex = assertThrows(BizException.class,
                () -> service.rebookTicket(10L, 2L, 20L, passenger));

        assertTrue(ex.getMessage().contains("目标班次"));
    }

    @Test
    void 允许改签到更早的班次() {
        Orders order = order(1L, 99L, new BigDecimal("100.00"));
        Ticket ticket = ticket(10L, 1L, new BigDecimal("100.00"));
        Schedule origin = schedule(FIXED_NOW.plusHours(5));
        origin.setRouteId(1L);
        Schedule target = schedule(FIXED_NOW.plusHours(2));
        target.setId(2L);
        target.setRouteId(1L);
        target.setPrice(new BigDecimal("100.00"));
        Seat targetSeat = targetSeat();

        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(ordersRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(scheduleLock.lockAll(anyLong(), anyLong())).thenReturn(Map.of(1L, origin, 2L, target));
        when(seatRepository.findById(20L)).thenReturn(Optional.of(targetSeat));
        when(ticketRepository.existsActiveTicket(anyString(), anyLong(), anyLong())).thenReturn(false);
        when(seatRepository.sellTargetSeat(anyLong(), anyLong(), anyLong())).thenReturn(1);
        when(ticketRepository.markRefunded(anyLong(), any(), anyString(), any())).thenReturn(1);
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Ticket newTicket = service.rebookTicket(10L, 2L, 20L, passenger);

        // 只要两个班次都在发船前 1 小时以上，往早改也允许
        assertEquals(2L, newTicket.getScheduleId());
        assertEquals(0, new BigDecimal("100.00").compareTo(order.getTotalAmount()));
    }

    @Test
    void 乘客不能操作别人的订单() {
        Orders order = order(1L, 88L, new BigDecimal("100.00"));
        Ticket ticket = ticket(10L, 1L, new BigDecimal("100.00"));

        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(scheduleLock.lock(1L)).thenReturn(schedule(FIXED_NOW.plusHours(5)));
        when(ordersRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));

        BizException ex = assertThrows(BizException.class,
                () -> service.refundTicket(10L, null, passenger));

        assertEquals("只能操作自己的订单", ex.getMessage());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verify(ticketRepository, never()).markRefunded(anyLong(), any(), anyString(), any());
    }

    @Test
    void 已发船班次不能下单() {
        when(scheduleLock.lock(1L)).thenReturn(schedule(FIXED_NOW));

        BizException ex = assertThrows(BizException.class, () -> service.createOrder(1L, 99L, null,
                java.util.List.of(new com.gdou.ferry.dto.OrderForms.SeatRequest(
                        "A1", "王乘客", "440100199004041004", "13800000001"))));

        assertTrue(ex.getMessage().contains("已发船"));
    }

    @Test
    void 退票原因按预设透传() {
        Orders order = order(1L, 99L, new BigDecimal("100.00"));
        Ticket ticket = ticket(10L, 1L, new BigDecimal("100.00"));

        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(scheduleLock.lock(1L)).thenReturn(schedule(FIXED_NOW.plusHours(5)));
        when(ordersRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(ticketRepository.markRefunded(anyLong(), any(), anyString(), any())).thenReturn(1);
        when(ticketRepository.countByOrderIdAndStatusIn(anyLong(), any())).thenReturn(0L);

        service.refundTicket(10L, "行程变更", passenger);

        verify(ticketRepository).markRefunded(eq(10L), any(), eq("行程变更"), any());
    }

    @Test
    void 非预设退票原因被拒() {
        Orders order = order(1L, 99L, new BigDecimal("100.00"));
        Ticket ticket = ticket(10L, 1L, new BigDecimal("100.00"));

        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(scheduleLock.lock(1L)).thenReturn(schedule(FIXED_NOW.plusHours(5)));
        when(ordersRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));

        BizException ex = assertThrows(BizException.class,
                () -> service.refundTicket(10L, "我不想要了", passenger));

        assertTrue(ex.getMessage().contains("退票原因不合法"));
        verify(ticketRepository, never()).markRefunded(anyLong(), any(), anyString(), any());
    }

    @Test
    void 支付时座位已被别的订单占用则整单失败() {
        Orders order = order(1L, 99L, new BigDecimal("100.00"));
        order.setExpireAt(FIXED_NOW.plusMinutes(10));
        Ticket ticket = ticket(10L, 1L, new BigDecimal("100.00"));
        ticket.setStatus("待支付");

        when(ordersRepository.findById(1L)).thenReturn(Optional.of(order));
        when(ordersRepository.markPaid(eq(1L), any())).thenReturn(1);
        when(ticketRepository.findByOrderIdOrderByIdAsc(1L)).thenReturn(java.util.List.of(ticket));
        // 座位已被新订单锁走：按归属条件更新 0 行，必须整单回滚，不能悄悄置售
        when(seatRepository.sellByOrder(11L, 1L)).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () -> service.payOrder(1L, passenger));

        assertTrue(ex.getMessage().contains("座位已被其他订单占用"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(seatRepository, never()).save(any(Seat.class));
    }

    @Test
    void 同班次不能改签() {
        Orders order = order(1L, 99L, new BigDecimal("100.00"));
        Ticket ticket = ticket(10L, 1L, new BigDecimal("100.00"));

        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));

        BizException ex = assertThrows(BizException.class,
                () -> service.rebookTicket(10L, 1L, 20L, passenger));

        assertTrue(ex.getMessage().contains("同班次"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void 改签时目标座位已被占用则失败() {
        Orders order = order(1L, 99L, new BigDecimal("100.00"));
        Ticket ticket = ticket(10L, 1L, new BigDecimal("100.00"));
        stubRebook(order, ticket, new BigDecimal("120.00"), targetSeat(), 0);

        BizException ex = assertThrows(BizException.class,
                () -> service.rebookTicket(10L, 2L, 20L, passenger));

        assertTrue(ex.getMessage().contains("目标座位已被占用"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    private Seat targetSeat() {
        Seat seat = new Seat();
        seat.setId(20L);
        seat.setScheduleId(2L);
        seat.setSeatStatus("可选");
        return seat;
    }

    private void stubRebook(Orders order, Ticket ticket, BigDecimal targetPrice, Seat seat) {
        stubRebook(order, ticket, targetPrice, seat, 1);
    }

    private void stubRebook(Orders order, Ticket ticket, BigDecimal targetPrice, Seat seat, int seatRows) {
        Schedule origin = schedule(FIXED_NOW.plusHours(5));
        origin.setRouteId(1L);
        targetSchedule = schedule(FIXED_NOW.plusHours(6));
        targetSchedule.setId(2L);
        targetSchedule.setRouteId(1L);
        targetSchedule.setPrice(targetPrice);

        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(ordersRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(scheduleLock.lockAll(anyLong(), anyLong())).thenReturn(Map.of(1L, origin, 2L, targetSchedule));
        when(seatRepository.findById(20L)).thenReturn(Optional.of(seat));
        when(ticketRepository.existsActiveTicket(anyString(), anyLong(), anyLong())).thenReturn(false);
        when(seatRepository.sellTargetSeat(anyLong(), anyLong(), anyLong())).thenReturn(seatRows);
        when(ticketRepository.markRefunded(anyLong(), any(), anyString(), any())).thenReturn(1);
        // 红/绿两个方向共用这个助手：失败路径走不到保存，按 lenient 声明
        lenient().when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Orders order(Long id, Long passengerId, BigDecimal total) {
        Orders order = new Orders();
        order.setId(id);
        order.setScheduleId(1L);
        order.setPassengerId(passengerId);
        order.setTotalAmount(total);
        order.setStatus("已支付");
        return order;
    }

    private Ticket ticket(Long id, Long orderId, BigDecimal price) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setOrderId(orderId);
        ticket.setScheduleId(1L);
        ticket.setSeatId(11L);
        ticket.setIdCard("110101199001011234");
        ticket.setPhone("13800138000");
        ticket.setPrice(price);
        ticket.setStatus("已支付");
        return ticket;
    }

    private Schedule schedule(LocalDateTime departTime) {
        Schedule schedule = new Schedule();
        schedule.setDepartTime(departTime);
        schedule.setStatus("售票中");
        return schedule;
    }
}
