package com.gdou.ferry.service;

import com.gdou.ferry.common.BizException;
import com.gdou.ferry.common.ErrorCodes;
import com.gdou.ferry.common.LoginUser;
import com.gdou.ferry.dto.OrderDetailView;
import com.gdou.ferry.dto.OrderRowView;
import com.gdou.ferry.dto.TicketView;
import com.gdou.ferry.entity.Orders;
import com.gdou.ferry.entity.Role;
import com.gdou.ferry.entity.Route;
import com.gdou.ferry.entity.Schedule;
import com.gdou.ferry.entity.Seat;
import com.gdou.ferry.entity.Ship;
import com.gdou.ferry.entity.Ticket;
import com.gdou.ferry.repository.OrdersRepository;
import com.gdou.ferry.repository.RouteRepository;
import com.gdou.ferry.repository.ScheduleRepository;
import com.gdou.ferry.repository.SeatRepository;
import com.gdou.ferry.repository.ShipRepository;
import com.gdou.ferry.repository.TicketRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private final SeatRepository seatRepository;
    private final RouteRepository routeRepository;
    private final ShipRepository shipRepository;

    public OrderQueryService(OrdersRepository ordersRepository,
                             TicketRepository ticketRepository,
                             ScheduleRepository scheduleRepository,
                             SeatRepository seatRepository,
                             RouteRepository routeRepository,
                             ShipRepository shipRepository) {
        this.ordersRepository = ordersRepository;
        this.ticketRepository = ticketRepository;
        this.scheduleRepository = scheduleRepository;
        this.seatRepository = seatRepository;
        this.routeRepository = routeRepository;
        this.shipRepository = shipRepository;
    }

    @Transactional(readOnly = true)
    public List<OrderRowView> myOrders(Long passengerId) {
        return views(ordersRepository.findByPassengerIdOrderByCreateTimeDesc(passengerId));
    }

    /**
     * 售票员/管理员订单列表：管理员看全部，售票员默认看自己经手的；
     * 传 orderId / idCard 时改成跨经手人精确查询——窗口代退要能处理乘客线上自购的票。
     */
    @Transactional(readOnly = true)
    public List<OrderRowView> sellerOrders(LoginUser loginUser, LocalDate dateFrom, LocalDate dateTo,
                                           Long scheduleId, String passengerName,
                                           Long orderId, String idCard) {
        List<Orders> orders;
        if (orderId != null) {
            orders = ordersRepository.findById(orderId).map(List::of).orElse(List.of());
        } else if (idCard != null && !idCard.isBlank()) {
            orders = ordersRepository.findByTicketIdCard(idCard.trim());
        } else if (loginUser.getRole() == Role.ADMIN) {
            orders = ordersRepository.findAll();
        } else {
            orders = ordersRepository.findBySellerIdOrderByCreateTimeDesc(loginUser.getId());
        }
        return views(orders).stream()
                .filter(view -> matches(view, dateFrom, dateTo, scheduleId, passengerName))
                .toList();
    }

    /** 订单详情：班次挂了的话明细字段留空，订单本身仍然返回。 */
    @Transactional(readOnly = true)
    public OrderDetailView detail(Long orderId, LoginUser loginUser) {
        Orders order = ordersRepository.findById(orderId)
                .orElseThrow(() -> new BizException("订单不存在", HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND));
        OrderService.checkOrderPermission(order, loginUser);
        Schedule schedule = scheduleRepository.findById(order.getScheduleId()).orElse(null);
        Route route = schedule == null ? null
                : routeRepository.findById(schedule.getRouteId()).orElse(null);
        Ship ship = schedule == null ? null
                : shipRepository.findById(schedule.getShipId()).orElse(null);
        List<Ticket> tickets = ticketRepository.findByOrderIdOrderByIdAsc(orderId);
        // 改签会让同一订单的票分属两个班次：票级发船时间/航线/船舶都按票自己的班次查（P1-3）
        Map<Long, Schedule> ticketSchedules = new HashMap<>();
        scheduleRepository.findAllById(tickets.stream().map(Ticket::getScheduleId).distinct().toList())
                .forEach(s -> ticketSchedules.put(s.getId(), s));
        Map<Long, Route> ticketRoutes = new HashMap<>();
        routeRepository.findAllById(ticketSchedules.values().stream()
                        .map(Schedule::getRouteId).distinct().toList())
                .forEach(r -> ticketRoutes.put(r.getId(), r));
        Map<Long, Ship> ticketShips = new HashMap<>();
        shipRepository.findAllById(ticketSchedules.values().stream()
                        .map(Schedule::getShipId).distinct().toList())
                .forEach(s -> ticketShips.put(s.getId(), s));
        Map<Long, String> seatNos = seatRepository
                .findAllById(tickets.stream().map(Ticket::getSeatId).toList()).stream()
                .collect(Collectors.toMap(Seat::getId, Seat::getSeatNo));
        return new OrderDetailView(order.getId(), order.getScheduleId(), order.getPassengerId(),
                order.getSellerId(), order.getTotalAmount(), order.getStatus(), order.getPayTime(),
                order.getExpireAt(), order.getCreateTime(),
                schedule == null ? null : schedule.getRouteId(),
                route == null ? null : route.getRouteName(),
                route == null ? null : route.getStartPort(),
                route == null ? null : route.getEndPort(),
                ship == null ? null : ship.getShipName(),
                schedule == null ? null : schedule.getDepartTime(),
                schedule == null ? null : schedule.getArriveTime(),
                tickets.stream().map(t -> {
                    Schedule ticketSchedule = ticketSchedules.get(t.getScheduleId());
                    Route ticketRoute = ticketSchedule == null ? null : ticketRoutes.get(ticketSchedule.getRouteId());
                    Ship ticketShip = ticketSchedule == null ? null : ticketShips.get(ticketSchedule.getShipId());
                    return TicketView.of(t, seatNos.get(t.getSeatId()),
                            departTimeOf(ticketSchedule),
                            ticketRoute == null ? null : ticketRoute.getRouteName(),
                            ticketShip == null ? null : ticketShip.getShipName());
                }).toList());
    }

    private boolean matches(OrderRowView view, LocalDate dateFrom, LocalDate dateTo,
                            Long scheduleId, String passengerName) {
        // 订单原始班次或任一票的班次命中即保留（改签后同单跨班次）
        if (scheduleId != null && !scheduleId.equals(view.scheduleId())
                && view.tickets().stream().noneMatch(t -> scheduleId.equals(t.scheduleId()))) {
            return false;
        }
        if (view.createTime() != null) {
            if (dateFrom != null && view.createTime().isBefore(dateFrom.atStartOfDay())) {
                return false;
            }
            if (dateTo != null && !view.createTime().isBefore(dateTo.plusDays(1).atStartOfDay())) {
                return false;
            }
        }
        String name = passengerName == null ? null : passengerName.trim();
        if (name != null && !name.isEmpty()) {
            return view.tickets().stream().anyMatch(t -> t.passengerName().contains(name));
        }
        return true;
    }

    /** 订单视图补上 routeId，前端改签时据此筛「同航线」目标班次。 */
    private List<OrderRowView> views(List<Orders> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        Map<Long, Schedule> schedules = new HashMap<>();
        Map<Long, List<Ticket>> tickets = ticketRepository
                .findByOrderIdInOrderByIdAsc(orders.stream().map(Orders::getId).toList())
                .stream().collect(Collectors.groupingBy(Ticket::getOrderId));
        // 订单班次 + 票自己的班次：改签后同单跨班次，两边都要
        List<Long> scheduleIds = new ArrayList<>(orders.stream().map(Orders::getScheduleId).distinct().toList());
        tickets.values().stream().flatMap(List::stream).map(Ticket::getScheduleId).forEach(scheduleIds::add);
        scheduleRepository.findAllById(scheduleIds.stream().distinct().toList())
                .forEach(schedule -> schedules.put(schedule.getId(), schedule));
        Map<Long, String> seatNos = seatRepository
                .findAllById(tickets.values().stream().flatMap(List::stream)
                        .map(Ticket::getSeatId).distinct().toList())
                .stream().collect(Collectors.toMap(Seat::getId, Seat::getSeatNo));
        return orders.stream()
                .map(order -> OrderRowView.of(order,
                        tickets.getOrDefault(order.getId(), List.of()).stream()
                                .map(ticket -> TicketView.of(ticket, seatNos.get(ticket.getSeatId()),
                                        departTimeOf(schedules.get(ticket.getScheduleId())))).toList(),
                        routeIdOf(schedules.get(order.getScheduleId()))))
                .toList();
    }

    /** 票级发船时间：前端靠它隐藏已发船的退改签按钮、给不足 1 小时的票置灰。 */
    private LocalDateTime departTimeOf(Schedule schedule) {
        return schedule == null ? null : schedule.getDepartTime();
    }

    private Long routeIdOf(Schedule schedule) {
        return schedule == null ? null : schedule.getRouteId();
    }
}
