package com.gdou.ferry.service;

import com.gdou.ferry.common.LoginUser;
import com.gdou.ferry.dto.OrderViews;
import com.gdou.ferry.entity.Orders;
import com.gdou.ferry.entity.Role;
import com.gdou.ferry.entity.Schedule;
import com.gdou.ferry.entity.Ticket;
import com.gdou.ferry.repository.OrdersRepository;
import com.gdou.ferry.repository.ScheduleRepository;
import com.gdou.ferry.repository.TicketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 订单查询：只读，负责把订单 + 船票 + 班次组装成前端视图。
 */
@Service
public class OrderQueryService {

    private final OrdersRepository ordersRepository;
    private final TicketRepository ticketRepository;
    private final ScheduleRepository scheduleRepository;

    public OrderQueryService(OrdersRepository ordersRepository,
                             TicketRepository ticketRepository,
                             ScheduleRepository scheduleRepository) {
        this.ordersRepository = ordersRepository;
        this.ticketRepository = ticketRepository;
        this.scheduleRepository = scheduleRepository;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> myOrders(Long passengerId) {
        return views(ordersRepository.findByPassengerIdOrderByCreateTimeDesc(passengerId));
    }

    /**
     * 售票员/管理员订单列表：管理员看全部，售票员看自己经手的。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> sellerOrders(LoginUser loginUser) {
        List<Orders> orders = loginUser.getRole() == Role.ADMIN
                ? ordersRepository.findAll()
                : ordersRepository.findBySellerIdOrderByCreateTimeDesc(loginUser.getId());
        return views(orders);
    }

    /** 订单视图补上 routeId，前端改签时据此筛「同航线」目标班次。 */
    private List<Map<String, Object>> views(List<Orders> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        Map<Long, Schedule> schedules = new HashMap<>();
        scheduleRepository.findAllById(orders.stream().map(Orders::getScheduleId).distinct().toList())
                .forEach(schedule -> schedules.put(schedule.getId(), schedule));
        Map<Long, List<Ticket>> tickets = ticketRepository
                .findByOrderIdInOrderByIdAsc(orders.stream().map(Orders::getId).toList())
                .stream().collect(Collectors.groupingBy(Ticket::getOrderId));
        return orders.stream()
                .map(order -> {
                    Map<String, Object> view = OrderViews.orderWithTickets(
                            order, tickets.getOrDefault(order.getId(), List.of()));
                    Schedule schedule = schedules.get(order.getScheduleId());
                    if (schedule != null) {
                        view.put("routeId", schedule.getRouteId());
                    }
                    return view;
                })
                .toList();
    }
}
