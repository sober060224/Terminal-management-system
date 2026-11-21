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
import com.gdou.ferry.repository.ScheduleRepository;
import com.gdou.ferry.repository.SeatRepository;
import com.gdou.ferry.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
    private ScheduleRepository scheduleRepository;
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
        service = new OrderService(scheduleRepository, seatRepository, ordersRepository,
                ticketRepository, scheduleLock);
        ReflectionTestUtils.setField(service, "orderPayMinutes", 15L);
    }

    @Test
    void 退票按实退金额递减订单总额() {
        Orders order = order(1L, 99L, new BigDecimal("150.00"));
        Ticket ticket = ticket(10L, 1L, new BigDecimal("100.00"));
        Seat seat = new Seat();

        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(ordersRepository.findById(1L)).thenReturn(Optional.of(order));
        when(scheduleLock.lock(1L)).thenReturn(schedule(LocalDateTime.now().plusHours(1)));
        when(seatRepository.findById(anyLong())).thenReturn(Optional.of(seat));

        Ticket refunded = service.refundTicket(10L, passenger);

        assertEquals("已退票", refunded.getStatus());
        assertEquals(0, new BigDecimal("80.00").compareTo(refunded.getRefundAmount()));
        assertEquals(0, new BigDecimal("70.00").compareTo(order.getTotalAmount()));
        assertEquals("可选", seat.getSeatStatus());
        assertNull(seat.getOrderId());
    }

    @Test
    void 改签补差价订单总额改为新票合计() {
        Orders order = order(1L, 99L, new BigDecimal("100.00"));
        Ticket ticket = ticket(10L, 1L, new BigDecimal("100.00"));
        ticket.setSeatId(11L);
        Schedule origin = schedule(LocalDateTime.now().plusHours(5));
        origin.setRouteId(1L);
        Schedule target = schedule(LocalDateTime.now().plusHours(6));
        target.setId(2L);
        target.setRouteId(1L);
        target.setPrice(new BigDecimal("120.00"));
        Seat targetSeat = new Seat();
        targetSeat.setId(20L);
        targetSeat.setScheduleId(2L);
        targetSeat.setSeatStatus("可选");

        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(ordersRepository.findById(1L)).thenReturn(Optional.of(order));
        when(scheduleLock.lock(2L)).thenReturn(target);
        when(scheduleRepository.findById(1L)).thenReturn(Optional.of(origin));
        when(seatRepository.findById(11L)).thenReturn(Optional.of(new Seat()));
        when(seatRepository.findById(20L)).thenReturn(Optional.of(targetSeat));
        when(ticketRepository.existsActiveTicket(anyString(), anyLong())).thenReturn(false);
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));

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
        ticket.setSeatId(11L);
        Schedule origin = schedule(LocalDateTime.now().plusHours(5));
        origin.setRouteId(1L);
        Schedule target = schedule(LocalDateTime.now().plusHours(6));
        target.setId(2L);
        target.setRouteId(1L);
        target.setPrice(new BigDecimal("80.00"));
        Seat targetSeat = new Seat();
        targetSeat.setId(20L);
        targetSeat.setScheduleId(2L);
        targetSeat.setSeatStatus("可选");

        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(ordersRepository.findById(1L)).thenReturn(Optional.of(order));
        when(scheduleLock.lock(2L)).thenReturn(target);
        when(scheduleRepository.findById(1L)).thenReturn(Optional.of(origin));
        when(seatRepository.findById(11L)).thenReturn(Optional.of(new Seat()));
        when(seatRepository.findById(20L)).thenReturn(Optional.of(targetSeat));
        when(ticketRepository.existsActiveTicket(anyString(), anyLong())).thenReturn(false);
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.rebookTicket(10L, 2L, 20L, passenger);

        // 多退少补退差额：原票实退 20，订单总额 100 → 80
        assertEquals(0, new BigDecimal("20.00").compareTo(ticket.getRefundAmount()));
        assertEquals(0, new BigDecimal("80.00").compareTo(order.getTotalAmount()));
    }

    @Test
    void 乘客不能操作别人的订单() {
        Orders order = order(1L, 88L, new BigDecimal("100.00"));
        Ticket ticket = ticket(10L, 1L, new BigDecimal("100.00"));

        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(ordersRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThrows(BizException.class, () -> service.refundTicket(10L, passenger));
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
