package com.gdou.ferry.service;

import com.gdou.ferry.common.BizException;
import com.gdou.ferry.common.ErrorCodes;
import com.gdou.ferry.common.FareRules;
import com.gdou.ferry.common.IdCards;
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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
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
 *
 * 隔离级别用 READ_COMMITTED：并发靠的是显式行锁（班次 + 订单行），而 MySQL 默认的
 * REPEATABLE READ 会让加锁之后的普通 SELECT 继续读事务开始时的旧快照——锁拿到了、
 * 值还是旧的，等于没锁（并发退票演示里抓到过总额丢失更新）。
 */
@Service
public class OrderService {

    private final SeatRepository seatRepository;
    private final OrdersRepository ordersRepository;
    private final TicketRepository ticketRepository;
    private final ScheduleLock scheduleLock;
    private final Clock clock;

    /** 改签窗口：原班次与目标班次都必须在发船前 1 小时以上。 */
    private static final int REBOOK_MINUTES_BEFORE_DEPARTURE = 60;

    /** 有效票 = 仍占用订单金额的票；已退票、已取消、待支付都不算。 */
    private static final List<String> ACTIVE_TICKET_STATUSES = List.of("已支付", "已检票");

    /** 退票原因预设（接口只接受这四项，不传记「乘客退票」，保持旧调用方可用）。 */
    private static final Set<String> REFUND_REASONS = Set.of("行程变更", "重复购票", "误购", "其他");

    /** 订单支付时限（分钟），超时未支付由定时任务释放座位。 */
    @Value("${ferry.order-pay-minutes:15}")
    private long orderPayMinutes;

    public OrderService(SeatRepository seatRepository,
                        OrdersRepository ordersRepository,
                        TicketRepository ticketRepository,
                        ScheduleLock scheduleLock,
                        Clock clock) {
        this.seatRepository = seatRepository;
        this.ordersRepository = ordersRepository;
        this.ticketRepository = ticketRepository;
        this.scheduleLock = scheduleLock;
        this.clock = clock;
    }

    /**
     * 创建订单：锁班次 → 锁座 → 生成待支付订单与票。
     * passengerId：乘客自购传自己；代售传售票员本人（真实乘船人写在票上）。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Orders createOrder(Long scheduleId, Long passengerId, Long sellerId,
                              List<OrderForms.SeatRequest> seats) {
        Schedule schedule = scheduleLock.lock(scheduleId);
        if (!"售票中".equals(schedule.getStatus())) {
            throw new BizException("班次不在售票中，无法下单", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        // 已发船的班次不允许再下单（班次状态流转属模块四，这里只做读侧校验）
        if (!schedule.getDepartTime().isAfter(LocalDateTime.now(clock))) {
            throw new BizException("班次已发船，无法下单", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }

        Set<String> seatNos = new HashSet<>();
        Set<String> idCards = new HashSet<>();
        for (OrderForms.SeatRequest seat : seats) {
            if (!seatNos.add(seat.seatNo())) {
                throw new BizException("座位号重复：" + seat.seatNo(),
                        HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR);
            }
            IdCards.require(seat.idCard());
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
        order.setExpireAt(LocalDateTime.now(clock).plusMinutes(orderPayMinutes));
        order = ordersRepository.save(order);

        LocalDateTime now = LocalDateTime.now(clock);
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
            ticket.setPhone(seat.phone());
            ticket.setPrice(schedule.getPrice());
            ticket.setStatus("待支付");
            ticketRepository.save(ticket);
        }
        return order;
    }

    /**
     * 支付订单：待支付 → 已支付，座位转已售，票转已支付。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Orders payOrder(Long orderId, LoginUser loginUser) {
        Orders order = loadOrder(orderId);
        checkOrderPermission(order, loginUser);
        LocalDateTime now = LocalDateTime.now(clock);
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
    @Transactional(isolation = Isolation.READ_COMMITTED)
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
            releaseSeat(ticket.getSeatId(), orderId);
        }
        return true;
    }

    /**
     * 票级部分退票：按实退金额递减订单总额，释放座位。
     * 发船后由 FareRules 抛 CONFLICT，接口拒绝、不落退票流水。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Ticket refundTicket(Long ticketId, String reason, LoginUser loginUser) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BizException("船票不存在", HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND));
        // 手续费基准是票所属班次，不是订单初次下单的班次（改签后两者会分叉，ADR-0002）
        Schedule schedule = scheduleLock.lock(ticket.getScheduleId());
        // 订单行悲观锁：必须先锁再读。先读订单再 lock 的话，同一个持久化上下文里
        // Hibernate 会把旧实例原样交回来（行锁照拿、值是旧的），等于没锁
        Orders order = lockOrder(ticket.getOrderId());
        checkOrderPermission(order, loginUser);
        if (!"已支付".equals(ticket.getStatus())) {
            throw new BizException("只有已支付的船票可以退票", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        BigDecimal refund = FareRules.refundable(ticket.getPrice(), schedule.getDepartTime(), now);
        String reasonText = normalizeRefundReason(reason);
        if (ticketRepository.markRefunded(ticket.getId(), refund, reasonText, now) == 0) {
            throw new BizException("只有已支付的船票可以退票", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }

        // 锁顺序：班次 → 订单行 → 座位 → 票；订单行必须在班次之后取，改签与退票才不会互锁（ADR-0002）
        releaseSeat(ticket.getSeatId(), order.getId());
        ticket.setRefundAmount(refund);
        ticket.setRefundReason(reasonText);
        ticket.setRefundTime(now);
        ticket.setStatus("已退票");

        // 退票减的是实退现金，手续费留在 total_amount 里（它就是实收现金净额），
        // 所以「单已结清」不看金额归零，只看这张单还有没有有效票。
        order.setTotalAmount(order.getTotalAmount().subtract(refund));
        if (ticketRepository.countByOrderIdAndStatusIn(order.getId(), ACTIVE_TICKET_STATUSES) == 0) {
            order.setStatus("已退票");
        }
        ordersRepository.save(order);
        return ticket;
    }

    /**
     * 改签（多退少补，限同航线）：原票置已退票（refund_reason=改签，refund_amount 只记实际退回的现金），
     * 新票挂改签链（changed_from_ticket_id 指回原票），订单总额改为新票合计。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Ticket rebookTicket(Long ticketId, Long targetScheduleId, Long targetSeatId, LoginUser loginUser) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BizException("船票不存在", HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND));
        // 改签同时写原班次和目标班次，两个班次一起锁（按 id 升序），避免互相改签时死锁
        Map<Long, Schedule> locked = scheduleLock.lockAll(ticket.getScheduleId(), targetScheduleId);
        // 订单行悲观锁：先锁再读，理由同 refundTicket
        Orders order = lockOrder(ticket.getOrderId());
        checkOrderPermission(order, loginUser);
        if (!"已支付".equals(ticket.getStatus()) || !"已支付".equals(order.getStatus())) {
            throw new BizException("只有已支付的船票可以改签", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        Schedule origin = locked.get(ticket.getScheduleId());
        Schedule target = locked.get(targetScheduleId);
        LocalDateTime now = LocalDateTime.now(clock);
        // 校验顺序：先看目标班次能不能用、是不是同航线，再看时间窗口，
        // 这样「改到别的航线」这类请求报的是航线错误而不是时间错误。
        if (!"售票中".equals(target.getStatus())) {
            throw new BizException("目标班次不在售票中", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        if (!origin.getRouteId().equals(target.getRouteId())) {
            throw new BizException("改签仅限同航线", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        // 改签窗口：原班次与目标班次都要在发船前 1 小时以上；允许改到更早的班次
        if (!origin.getDepartTime().isAfter(now.plusMinutes(REBOOK_MINUTES_BEFORE_DEPARTURE))) {
            throw new BizException("距发船不足 1 小时，不能改签", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        if (!target.getDepartTime().isAfter(now.plusMinutes(REBOOK_MINUTES_BEFORE_DEPARTURE))) {
            throw new BizException("目标班次距发船不足 1 小时，不能改签", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }

        Seat targetSeat = seatRepository.findById(targetSeatId)
                .orElseThrow(() -> new BizException("目标座位不存在", HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND));
        if (!targetSeat.getScheduleId().equals(targetScheduleId)) {
            throw new BizException("目标座位与目标班次不匹配",
                    HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR);
        }
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
        releaseSeat(ticket.getSeatId(), order.getId());

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
            newTicket.setPhone(ticket.getPhone());
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

    /** 退改签取订单行锁并重新加载：保证后面基于最新的 total_amount 做加减。 */
    private Orders lockOrder(Long orderId) {
        return ordersRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BizException("订单不存在", HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND));
    }

    /**
     * 乘客只能操作自己的订单；售票员/管理员可代操作任意订单。
     */
    static void checkOrderPermission(Orders order, LoginUser loginUser) {
        Role role = loginUser.getRole();
        if (role == Role.ADMIN || role == Role.SELLER) {
            return;
        }
        if (!order.getPassengerId().equals(loginUser.getId())) {
            throw new BizException("只能操作自己的订单", HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN);
        }
    }

    private void checkSeatFree(Seat seat, LocalDateTime now) {
        String state = seat.stateAt(now);
        if ("已售".equals(state)) {
            throw new BizException("座位已被占用", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        if ("锁定".equals(state)) {
            throw new BizException("座位已被他人锁定，请稍后重试", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
    }

    /**
     * 释放座位，只清「属于该订单」的锁。座位可能已经被别的订单抢走（原订单超时后、
     * 清理任务还没跑到它的空档），无条件清空会把新订单的座位一起改成可选。
     *
     * @return 有座位被本次释放返回 true；座位已不属于该订单（或座位不存在）返回 false
     */
    private boolean releaseSeat(Long seatId, Long orderId) {
        return seatRepository.releaseByOrder(seatId, orderId) > 0;
    }

    private String normalizeRefundReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "乘客退票";
        }
        String trimmed = reason.trim();
        if (!REFUND_REASONS.contains(trimmed)) {
            throw new BizException("退票原因不合法：" + trimmed,
                    HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR);
        }
        return trimmed;
    }
}
