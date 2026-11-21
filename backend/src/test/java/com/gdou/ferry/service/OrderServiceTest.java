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
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
        Seat seat = new Seat();

        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(ordersRepository.findById(1L)).thenReturn(Optional.of(order));
        when(scheduleLock.lock(1L)).thenReturn(schedule(FIXED_NOW.plusHours(1)));
        when(ticketRepository.markRefunded(anyLong(), any(), anyString(), any())).thenReturn(1);
        when(seatRepository.findById(anyLong())).thenReturn(Optional.of(seat));

        Ticket refunded = service.refundTicket(10L, passenger);

        assertEquals("已退票", refunded.getStatus());
        assertEquals(0, new BigDecimal("80.00").compareTo(refunded.getRefundAmount()));
        assertEquals(0, new BigDecimal("70.00").compareTo(order.getTotalAmount()));
        assertEquals(FIXED_NOW, refunded.getRefundTime());
        assertEquals("可选", seat.getSeatStatus());
        assertNull(seat.getOrderId());
    }

    @Test
    void 重复退票被条件更新挡住() {
        Orders order = order(1L, 99L, new BigDecimal("100.00"));
        Ticket ticket = ticket(10L, 1L, new BigDecimal("100.00"));

        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(ordersRepository.findById(1L)).thenReturn(Optional.of(order));
        when(scheduleLock.lock(1L)).thenReturn(schedule(FIXED_NOW.plusHours(1)));
        when(ticketRepository.markRefunded(anyLong(), any(), anyString(), any())).thenReturn(0);

        assertThrows(BizException.class, () -> service.refundTicket(10L, passenger));
    }

    @Test
    void 改签补差价订单总额改为新票合计() {
        Orders order = order(1L, 99L, new BigDecimal("100.00"));
        Ticket ticket = ticket(10L, 1L, new BigDecimal("100.00"));
        Seat targetSeat = targetSeat();
        stubRebook(order, ticket, new BigDecimal("120.00"), targetSeat);

        Ticket newTicket = service.rebookTicket(10L, 2L, 20L, passenger);

        assertEquals(0, new BigDecimal("120.00").compareTo(order.getTotalAmount()));
        // 只补不退：原票 refund_amount 记 0（实际退回现金为 0）
        assertEquals(0, BigDecimal.ZERO.compareTo(ticket.getRefundAmount()));
        assertEquals("改签", ticket.getRefundReason());
        assertEquals(0, new BigDecimal("120.00").compareTo(newTicket.getPrice()));
        assertEquals(10L, newTicket.getChangedFromTicketId());
        assertEquals("已售", targetSeat.getSeatStatus());
        assertEquals(1L, targetSeat.getOrderId());
    }

    @Test
    void 改签退差额订单总额递减差额() {
        Orders order = order(1L, 99L, new BigDecimal("100.00"));
        Ticket ticket = ticket(10L, 1L, new BigDecimal("100.00"));
        stubRebook(order, ticket, new BigDecimal("80.00"), targetSeat());

        service.rebookTicket(10L, 2L, 20L, passenger);

        // 多退少补退差额：原票实退 20，订单总额 100 → 80
        assertEquals(0, new BigDecimal("20.00").compareTo(ticket.getRefundAmount()));
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
    void 乘客不能操作别人的订单() {
        Orders order = order(1L, 88L, new BigDecimal("100.00"));
        Ticket ticket = ticket(10L, 1L, new BigDecimal("100.00"));

        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(ordersRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThrows(BizException.class, () -> service.refundTicket(10L, passenger));
    }

    private Seat targetSeat() {
        Seat seat = new Seat();
        seat.setId(20L);
        seat.setScheduleId(2L);
        seat.setSeatStatus("可选");
        return seat;
    }

    private void stubRebook(Orders order, Ticket ticket, BigDecimal targetPrice, Seat seat) {
        Schedule origin = schedule(FIXED_NOW.plusHours(5));
        origin.setRouteId(1L);
        targetSchedule = schedule(FIXED_NOW.plusHours(6));
        targetSchedule.setId(2L);
        targetSchedule.setRouteId(1L);
        targetSchedule.setPrice(targetPrice);

        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(ordersRepository.findById(1L)).thenReturn(Optional.of(order));
        when(scheduleLock.lockAll(anyLong(), anyLong())).thenReturn(Map.of(1L, origin, 2L, targetSchedule));
        when(seatRepository.findById(anyLong())).thenReturn(Optional.of(new Seat()));
        when(seatRepository.findById(20L)).thenReturn(Optional.of(seat));
        when(ticketRepository.existsActiveTicket(anyString(), anyLong())).thenReturn(false);
        when(ticketRepository.markRefunded(anyLong(), any(), anyString(), any())).thenReturn(1);
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
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
