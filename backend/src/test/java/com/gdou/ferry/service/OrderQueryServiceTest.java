package com.gdou.ferry.service;

import com.gdou.ferry.common.LoginUser;
import com.gdou.ferry.entity.Orders;
import com.gdou.ferry.entity.Role;
import com.gdou.ferry.entity.Route;
import com.gdou.ferry.entity.Schedule;
import com.gdou.ferry.entity.Ship;
import com.gdou.ferry.entity.Ticket;
import com.gdou.ferry.entity.User;
import com.gdou.ferry.entity.UserStatus;
import com.gdou.ferry.dto.OrderRowView;
import com.gdou.ferry.repository.OrdersRepository;
import com.gdou.ferry.repository.RouteRepository;
import com.gdou.ferry.repository.ScheduleRepository;
import com.gdou.ferry.repository.SeatRepository;
import com.gdou.ferry.repository.ShipRepository;
import com.gdou.ferry.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderQueryServiceTest {

    @Mock
    private OrdersRepository ordersRepository;
    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private ScheduleRepository scheduleRepository;
    @Mock
    private SeatRepository seatRepository;
    @Mock
    private RouteRepository routeRepository;
    @Mock
    private ShipRepository shipRepository;

    private OrderQueryService service;

    @BeforeEach
    void setUp() {
        service = new OrderQueryService(ordersRepository, ticketRepository, scheduleRepository,
                seatRepository, routeRepository, shipRepository);
    }

    @Test
    void 我的订单带上航线且班次一次取回() {
        Orders order = order(1L, 2L, 99L);
        Schedule schedule = new Schedule();
        schedule.setId(2L);
        schedule.setRouteId(7L);

        Ticket ticket = new Ticket();
        ticket.setId(10L);
        ticket.setOrderId(1L);
        ticket.setScheduleId(2L);
        ticket.setPrice(new BigDecimal("100.00"));
        LocalDateTime depart = LocalDateTime.of(2026, 11, 21, 18, 0);
        schedule.setDepartTime(depart);

        when(ordersRepository.findByPassengerIdOrderByCreateTimeDesc(99L)).thenReturn(List.of(order));
        when(ticketRepository.findByOrderIdInOrderByIdAsc(List.of(1L))).thenReturn(List.of(ticket));
        when(scheduleRepository.findAllById(List.of(2L))).thenReturn(List.of(schedule));

        List<OrderRowView> views = service.myOrders(99L);

        assertEquals(7L, views.get(0).routeId());
        assertEquals(1, views.get(0).tickets().size());
        assertEquals(new BigDecimal("100.00"), views.get(0).tickets().get(0).price());
        // 列表接口也要带票级发船时间：前端靠它隐藏已发船的退改签按钮
        assertEquals(depart, views.get(0).tickets().get(0).departTime());
        verify(scheduleRepository).findAllById(List.of(2L));
    }

    @Test
    void 售票员只看自己经手的订单() {
        when(ordersRepository.findBySellerIdOrderByCreateTimeDesc(5L)).thenReturn(List.of());

        assertTrue(service.sellerOrders(user(5L, Role.SELLER), null, null, null, null).isEmpty());

        verify(ordersRepository).findBySellerIdOrderByCreateTimeDesc(5L);
        verify(ordersRepository, never()).findAll();
    }

    @Test
    void 详情按每张票各自的班次返回发船时间() {
        Orders order = order(1L, 2L, 99L);
        Schedule origin = schedule(2L, 7L, LocalDateTime.of(2026, 11, 21, 18, 0));
        origin.setShipId(5L);
        Schedule target = schedule(3L, 7L, LocalDateTime.of(2026, 11, 21, 15, 0));

        Ticket oldTicket = ticket(10L, 1L, 2L);
        Ticket newTicket = ticket(11L, 1L, 3L);

        when(ordersRepository.findById(1L)).thenReturn(Optional.of(order));
        when(ticketRepository.findByOrderIdOrderByIdAsc(1L)).thenReturn(List.of(oldTicket, newTicket));
        when(seatRepository.findAllById(List.of(11L, 12L))).thenReturn(List.of());
        when(scheduleRepository.findById(2L)).thenReturn(Optional.of(origin));
        when(scheduleRepository.findAllById(List.of(2L, 3L))).thenReturn(List.of(origin, target));
        when(routeRepository.findById(7L)).thenReturn(Optional.empty());
        when(shipRepository.findById(5L)).thenReturn(Optional.empty());

        var detail = service.detail(1L, user(99L, Role.PASSENGER));

        // 改签后的票显示的是新班次时间，不能跟着订单原始班次走
        assertEquals(origin.getDepartTime(), detail.tickets().get(0).departTime());
        assertEquals(target.getDepartTime(), detail.tickets().get(1).departTime());
    }

    @Test
    void 详情按每张票各自的班次返回航线与船舶() {
        Orders order = order(1L, 2L, 99L);
        Schedule origin = schedule(2L, 7L, LocalDateTime.of(2026, 11, 21, 18, 0));
        origin.setShipId(5L);
        Schedule target = schedule(3L, 7L, LocalDateTime.of(2026, 11, 22, 15, 0));
        target.setShipId(8L);

        Route route = new Route();
        route.setId(7L);
        route.setRouteName("广州-香港");
        Ship oldShip = new Ship();
        oldShip.setId(5L);
        oldShip.setShipName("粤港01");
        Ship newShip = new Ship();
        newShip.setId(8L);
        newShip.setShipName("粤港02");

        when(ordersRepository.findById(1L)).thenReturn(Optional.of(order));
        when(ticketRepository.findByOrderIdOrderByIdAsc(1L)).thenReturn(List.of(ticket(10L, 1L, 2L), ticket(11L, 1L, 3L)));
        when(seatRepository.findAllById(List.of(11L, 12L))).thenReturn(List.of());
        when(scheduleRepository.findById(2L)).thenReturn(Optional.of(origin));
        when(scheduleRepository.findAllById(List.of(2L, 3L))).thenReturn(List.of(origin, target));
        when(routeRepository.findById(7L)).thenReturn(Optional.of(route));
        when(routeRepository.findAllById(List.of(7L))).thenReturn(List.of(route));
        when(shipRepository.findById(5L)).thenReturn(Optional.of(oldShip));
        when(shipRepository.findAllById(List.of(5L, 8L))).thenReturn(List.of(oldShip, newShip));

        var detail = service.detail(1L, user(99L, Role.PASSENGER));

        // 改签到同航线另一条船：票上必须是新船，不能跟着订单原始班次显示旧船
        assertEquals("粤港01", detail.tickets().get(0).shipName());
        assertEquals("粤港02", detail.tickets().get(1).shipName());
        assertEquals("广州-香港", detail.tickets().get(1).routeName());
        assertEquals(target.getDepartTime(), detail.tickets().get(1).departTime());
    }

    @Test
    void 按目标班次筛选能查到改签后的订单() {
        Orders order = order(1L, 2L, 99L);
        Schedule origin = schedule(2L, 7L, LocalDateTime.of(2026, 11, 21, 18, 0));
        Ticket oldTicket = ticket(10L, 1L, 2L);
        Ticket newTicket = ticket(11L, 1L, 3L);

        when(ordersRepository.findBySellerIdOrderByCreateTimeDesc(5L)).thenReturn(List.of(order));
        when(ticketRepository.findByOrderIdInOrderByIdAsc(List.of(1L))).thenReturn(List.of(oldTicket, newTicket));
        when(seatRepository.findAllById(List.of(11L, 12L))).thenReturn(List.of());
        when(scheduleRepository.findAllById(List.of(2L, 3L))).thenReturn(List.of(origin));

        var views = service.sellerOrders(user(5L, Role.SELLER), null, null, 3L, null);

        // 订单原始班次是 2，但有一张票改到了 3：按 3 筛必须命中
        assertEquals(1, views.size());
        assertTrue(service.sellerOrders(user(5L, Role.SELLER), null, null, 4L, null).isEmpty());
    }

    @Test
    void 列表里改签后的票带的是新班次发船时间() {
        Orders order = order(1L, 2L, 99L);
        Schedule origin = schedule(2L, 7L, LocalDateTime.of(2026, 11, 21, 18, 0));
        Schedule target = schedule(3L, 7L, LocalDateTime.of(2026, 11, 22, 15, 0));

        Ticket oldTicket = ticket(10L, 1L, 2L);
        Ticket newTicket = ticket(11L, 1L, 3L);

        when(ordersRepository.findByPassengerIdOrderByCreateTimeDesc(99L)).thenReturn(List.of(order));
        when(ticketRepository.findByOrderIdInOrderByIdAsc(List.of(1L))).thenReturn(List.of(oldTicket, newTicket));
        when(seatRepository.findAllById(List.of(11L, 12L))).thenReturn(List.of());
        when(scheduleRepository.findAllById(List.of(2L, 3L))).thenReturn(List.of(origin, target));

        List<OrderRowView> views = service.myOrders(99L);

        assertEquals(origin.getDepartTime(), views.get(0).tickets().get(0).departTime());
        assertEquals(target.getDepartTime(), views.get(0).tickets().get(1).departTime());
    }

    private Schedule schedule(Long id, Long routeId, LocalDateTime departTime) {
        Schedule schedule = new Schedule();
        schedule.setId(id);
        schedule.setRouteId(routeId);
        schedule.setDepartTime(departTime);
        schedule.setStatus("售票中");
        return schedule;
    }

    private Ticket ticket(Long id, Long orderId, Long scheduleId) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setOrderId(orderId);
        ticket.setScheduleId(scheduleId);
        ticket.setSeatId(id + 1);
        ticket.setPrice(new BigDecimal("100.00"));
        ticket.setStatus("已支付");
        return ticket;
    }

    private Orders order(Long id, Long scheduleId, Long passengerId) {
        Orders order = new Orders();
        order.setId(id);
        order.setScheduleId(scheduleId);
        order.setPassengerId(passengerId);
        order.setTotalAmount(new BigDecimal("100.00"));
        return order;
    }

    private LoginUser user(Long id, Role role) {
        User user = new User();
        user.setId(id);
        user.setUsername("user" + id);
        user.setPassword("x");
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        return new LoginUser(user);
    }
}
