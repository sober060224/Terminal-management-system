package com.gdou.ferry.service;

import com.gdou.ferry.common.LoginUser;
import com.gdou.ferry.entity.Orders;
import com.gdou.ferry.entity.Role;
import com.gdou.ferry.entity.Schedule;
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
import java.util.List;

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
        ticket.setPrice(new BigDecimal("100.00"));

        when(ordersRepository.findByPassengerIdOrderByCreateTimeDesc(99L)).thenReturn(List.of(order));
        when(ticketRepository.findByOrderIdInOrderByIdAsc(List.of(1L))).thenReturn(List.of(ticket));
        when(scheduleRepository.findAllById(List.of(2L))).thenReturn(List.of(schedule));

        List<OrderRowView> views = service.myOrders(99L);

        assertEquals(7L, views.get(0).routeId());
        assertEquals(1, views.get(0).tickets().size());
        assertEquals(new BigDecimal("100.00"), views.get(0).tickets().get(0).price());
        verify(scheduleRepository).findAllById(List.of(2L));
    }

    @Test
    void 售票员只看自己经手的订单() {
        when(ordersRepository.findBySellerIdOrderByCreateTimeDesc(5L)).thenReturn(List.of());

        assertTrue(service.sellerOrders(user(5L, Role.SELLER), null, null, null, null).isEmpty());

        verify(ordersRepository).findBySellerIdOrderByCreateTimeDesc(5L);
        verify(ordersRepository, never()).findAll();
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
