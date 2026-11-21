package com.gdou.ferry.service;

import com.gdou.ferry.common.BizException;
import com.gdou.ferry.common.ErrorCodes;
import com.gdou.ferry.common.FareRules;
import com.gdou.ferry.common.LoginUser;
import com.gdou.ferry.dto.OrderForms;
import com.gdou.ferry.entity.Orders;
import com.gdou.ferry.entity.Role;
import com.gdou.ferry.entity.Schedule;
import com.gdou.ferry.entity.Seat;
import com.gdou.ferry.entity.Ticket;
import com.gdou.ferry.repository.OrdersRepository;
import com.gdou.ferry.repository.SeatRepository;
import com.gdou.ferry.repository.TicketRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模块三核心：下单、支付、取消、退票、改签。
 *
 * 金额口径（PLAN.md D7 / ADR-0004 修订）：
 * orders.total_amount = 该订单当前实收/应收金额：支付时写入票面合计，退票时递减实退额，
 * 改签时改为新票合计；统计收入直接 SUM(orders.total_amount)。
 */
@Service
public class OrderService {

    private final SeatRepository seatRepository;
    private final OrdersRepository ordersRepository;
    private final TicketRepository ticketRepository;
    private final ScheduleLock scheduleLock;

    /** 订单支付时限（分钟），超时未支付由定时任务释放座位。 */
    @Value("${ferry.order-pay-minutes:15}")
    private long orderPayMinutes;

    public OrderService(SeatRepository seatRepository,
                        OrdersRepository ordersRepository,
                        TicketRepository ticketRepository,
                        ScheduleLock scheduleLock) {
        this.seatRepository = seatRepository;
        this.ordersRepository = ordersRepository;
        this.ticketRepository = ticketRepository;
        this.scheduleLock = scheduleLock;
    }

    /**
     * 创建订单：锁班次 → 锁座 → 生成待支付订单与票。
     * passengerId：乘客自购传自己；代售传售票员本人（真实乘船人写在票上）。
     */
    @Transactional
    public Orders createOrder(Long scheduleId, Long passengerId, Long sellerId,
                              List<OrderForms.SeatRequest> seats) {
        Schedule schedule = scheduleLock.lock(scheduleId);
        if (!"售票中".equals(schedule.getStatus())) {
            throw new BizException("班次不在售票中，无法下单", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }

        Set<String> seatNos = new HashSet<>();
        Set<String> idCards = new HashSet<>();
        for (OrderForms.SeatRequest seat : seats) {
            if (!seatNos.add(seat.seatNo())) {
                throw new BizException("座位号重复：" + seat.seatNo(),
                        HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR);
            }
            if (!idCards.add(seat.idCard())) {
                throw new BizException("同一订单内乘船人身份证号不能重复",
                        HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR);
            }
        }

        Orders order = new Orders();
        order.setScheduleId(scheduleId);
        order.setPassengerId(passengerId);
        order.setSellerId(sellerId);
        order.setTotalAmount(schedule.getPrice().multiply(BigDecimal.valueOf(seats.size())));
        order.setStatus("待支付");
        order.setExpireAt(LocalDateTime.now().plusMinutes(orderPayMinutes));
        order = ordersRepository.save(order);

        LocalDateTime now = LocalDateTime.now();
        for (OrderForms.SeatRequest seat : seats) {
            Seat entity = seatRepository.findByScheduleIdAndSeatNo(scheduleId, seat.seatNo())
                    .orElseThrow(() -> new BizException("座位不存在：" + seat.seatNo(),
                            HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND));
            checkSeatFree(entity, now);
            if (ticketRepository.existsActiveTicket(seat.idCard(), scheduleId)) {
                throw new BizException("乘船人已购买本班次船票（一人一票）",
                        HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
            }
            // 锁座：座位挂到订单上，锁到支付截止时刻，支付/取消/超时再流转。
            entity.setSeatStatus("锁定");
            entity.setLockUntil(order.getExpireAt());
            entity.setOrderId(order.getId());
            seatRepository.save(entity);

            Ticket ticket = new Ticket();
            ticket.setOrderId(order.getId());
            ticket.setScheduleId(scheduleId);
            ticket.setSeatId(entity.getId());
            ticket.setPassengerName(seat.passengerName());
            ticket.setIdCard(seat.idCard());
            ticket.setPrice(schedule.getPrice());
            ticket.setStatus("待支付");
            ticketRepository.save(ticket);
        }
        return order;
    }

    /**
     * 支付订单：待支付 → 已支付，座位转已售，票转已支付。
     */
    @Transactional
    public Orders payOrder(Long orderId, LoginUser loginUser) {
        Orders order = loadOrder(orderId);
        checkOrderPermission(order, loginUser);
        LocalDateTime now = LocalDateTime.now();
        if (order.getExpireAt() != null && order.getExpireAt().isBefore(now)) {
            throw new BizException("订单已超时，请重新下单", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }

        // 不持班次锁，靠条件更新判定状态：并发支付、取消、超时清理只有一方能改到行
        if (ordersRepository.markPaid(orderId, now) == 0) {
            throw new BizException("订单状态不允许支付", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }

        for (Ticket ticket : ticketRepository.findByOrderIdOrderByIdAsc(orderId)) {
            if (!"待支付".equals(ticket.getStatus())) {
                continue;
            }
            ticket.setStatus("已支付");
            ticketRepository.save(ticket);
            seatRepository.findById(ticket.getSeatId()).ifPresent(seat -> {
                seat.setSeatStatus("已售");
                seat.setLockUntil(null);
                seatRepository.save(seat);
            });
        }
        return loadOrder(orderId);
    }

    /**
     * 取消未支付订单：释放座位，订单与票置已取消。
     */
    @Transactional
    public Orders cancelOrder(Long orderId, LoginUser loginUser) {
        Orders order = loadOrder(orderId);
        checkOrderPermission(order, loginUser);
        if (!cancelAsSystem(orderId)) {
            throw new BizException("只有待支付订单可以取消", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        return loadOrder(orderId);
    }

    /**
     * 取消待支付订单（不校验操作人），人工取消与超时清理共用。
     *
     * @return 订单仍是待支付并已取消返回 true；已被支付/取消/清理抢先改到行返回 false。
     */
    boolean cancelAsSystem(Long orderId) {
        if (ordersRepository.markCancelled(orderId) == 0) {
            return false;
        }
        for (Ticket ticket : ticketRepository.findByOrderIdOrderByIdAsc(orderId)) {
            ticket.setStatus("已取消");
            ticketRepository.save(ticket);
            releaseSeat(ticket.getSeatId());
        }
        return true;
    }

    /**
     * 票级部分退票：按实退金额递减订单总额，释放座位。
     * 发船后由 FareRules 抛 CONFLICT，接口拒绝、不落退票流水。
     */
    @Transactional
    public Ticket refundTicket(Long ticketId, LoginUser loginUser) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BizException("船票不存在", HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND));
        Orders order = loadOrder(ticket.getOrderId());
        checkOrderPermission(order, loginUser);
        if (!"已支付".equals(ticket.getStatus())) {
            throw new BizException("只有已支付的船票可以退票", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }

        Schedule schedule = scheduleLock.lock(order.getScheduleId());
        LocalDateTime now = LocalDateTime.now();
        BigDecimal refund = FareRules.refundable(ticket.getPrice(), schedule.getDepartTime(), now);
        if (ticketRepository.markRefunded(ticket.getId(), refund, "乘客退票", now) == 0) {
            throw new BizException("只有已支付的船票可以退票", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }

        // 锁顺序 schedule → seat → orders → ticket（ADR-0002）
        releaseSeat(ticket.getSeatId());
        ticket.setRefundAmount(refund);
        ticket.setRefundReason("乘客退票");
        ticket.setRefundTime(now);
        ticket.setStatus("已退票");

        order.setTotalAmount(order.getTotalAmount().subtract(refund));
        if (order.getTotalAmount().compareTo(BigDecimal.ZERO) == 0) {
            order.setStatus("已退票");
        }
        ordersRepository.save(order);
        return ticket;
    }

    /**
     * 改签（多退少补，限同航线）：原票置已退票（refund_reason=改签，refund_amount 只记实际退回的现金），
     * 新票挂改签链（changed_from_ticket_id 指回原票），订单总额改为新票合计。
     */
    @Transactional
    public Ticket rebookTicket(Long ticketId, Long targetScheduleId, Long targetSeatId, LoginUser loginUser) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BizException("船票不存在", HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND));
        Orders order = loadOrder(ticket.getOrderId());
        checkOrderPermission(order, loginUser);
        if (!"已支付".equals(ticket.getStatus()) || !"已支付".equals(order.getStatus())) {
            throw new BizException("只有已支付的船票可以改签", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }

        // 改签同时写原班次和目标班次，两个班次一起锁（按 id 升序），避免互相改签时死锁
        Map<Long, Schedule> locked = scheduleLock.lockAll(ticket.getScheduleId(), targetScheduleId);
        Schedule origin = locked.get(ticket.getScheduleId());
        Schedule target = locked.get(targetScheduleId);
        if (!"售票中".equals(target.getStatus())) {
            throw new BizException("目标班次不在售票中", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        if (!origin.getRouteId().equals(target.getRouteId())) {
            throw new BizException("改签仅限同航线", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }

        Seat targetSeat = seatRepository.findById(targetSeatId)
                .orElseThrow(() -> new BizException("目标座位不存在", HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND));
        if (!targetSeat.getScheduleId().equals(targetScheduleId)) {
            throw new BizException("目标座位与目标班次不匹配",
                    HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR);
        }
        LocalDateTime now = LocalDateTime.now();
        checkSeatFree(targetSeat, now);
        if (ticketRepository.existsActiveTicket(ticket.getIdCard(), targetScheduleId)) {
            throw new BizException("乘船人已购买目标班次船票（一人一票）",
                HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }

        BigDecimal diff = target.getPrice().subtract(ticket.getPrice());

        // 原票置已退票：refund_amount 只记实际退回的现金（补差价记 0，退差额记差额）
        BigDecimal refund = diff.signum() < 0 ? diff.negate() : BigDecimal.ZERO;
        if (ticketRepository.markRefunded(ticket.getId(), refund, "改签", now) == 0) {
            throw new BizException("只有已支付的船票可以改签", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        ticket.setRefundAmount(refund);
        ticket.setRefundReason("改签");
        ticket.setRefundTime(now);
        ticket.setStatus("已退票");
        releaseSeat(ticket.getSeatId());

        // 订单总额改为新票合计（实收口径：+差价或 −差额）；新票仍是已支付，订单状态不跟着变
        order.setTotalAmount(order.getTotalAmount().add(diff));
        if (order.getTotalAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("订单金额异常，改签失败", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        ordersRepository.save(order);

        targetSeat.setSeatStatus("已售");
        targetSeat.setLockUntil(null);
        targetSeat.setOrderId(order.getId());
        seatRepository.save(targetSeat);

        Ticket newTicket = new Ticket();
        newTicket.setOrderId(order.getId());
        newTicket.setScheduleId(targetScheduleId);
        newTicket.setSeatId(targetSeatId);
        newTicket.setPassengerName(ticket.getPassengerName());
        newTicket.setIdCard(ticket.getIdCard());
        newTicket.setPrice(target.getPrice());
        newTicket.setStatus("已支付");
        newTicket.setChangedFromTicketId(ticket.getId());
        newTicket.setChangedAt(now);
        return ticketRepository.save(newTicket);
    }

    private Orders loadOrder(Long orderId) {
        return ordersRepository.findById(orderId)
                .orElseThrow(() -> new BizException("订单不存在", HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND));
    }

    /**
     * 乘客只能操作自己的订单；售票员/管理员可代操作任意订单。
     */
    private void checkOrderPermission(Orders order, LoginUser loginUser) {
        Role role = loginUser.getRole();
        if (role == Role.ADMIN || role == Role.SELLER) {
            return;
        }
        if (!order.getPassengerId().equals(loginUser.getId())) {
            throw new BizException("只能操作自己的订单", HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN);
        }
    }

    private void checkSeatFree(Seat seat, LocalDateTime now) {
        if ("已售".equals(seat.getSeatStatus())) {
            throw new BizException("座位已被占用", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        if ("锁定".equals(seat.getSeatStatus()) && seat.getLockUntil() != null
                && seat.getLockUntil().isAfter(now)) {
            throw new BizException("座位已被他人锁定，请稍后重试", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
    }

    private void releaseSeat(Long seatId) {
        seatRepository.findById(seatId).ifPresent(seat -> {
            seat.setSeatStatus("可选");
            seat.setLockUntil(null);
            seat.setOrderId(null);
            seatRepository.save(seat);
        });
    }
}
