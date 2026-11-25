package com.gdou.ferry.service;

import com.gdou.ferry.common.BizException;
import com.gdou.ferry.common.ErrorCodes;
import com.gdou.ferry.dto.AdminForms;
import com.gdou.ferry.dto.AdminViews;
import com.gdou.ferry.entity.Orders;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 模块二·功能3：班次管理。
 *
 * <p>覆盖需求 2.2 功能3（列表 / 新增 / 批量生成 / 编辑 / 取消 / 详情）、
 * 2.3 业务规则（同船时段不冲突、发船后不可改、取消触发全额退票）、
 * 2.4 状态流转（待发布 → 售票中 → 已发船 → 已完成，售票中 → 已取消）。
 *
 * <p>并发与锁：沿用 ADR-0002 的悲观锁方案，锁顺序固定为 schedule → orders → ticket → seat。
 * 区间重叠检查用应用层查询（PLAN.md §6：数据库唯一索引表达不了区间不重叠）。
 */
@Service
public class AdminScheduleService {

    /** 班次状态全集（api.md §8.4.1）。 */
    static final List<String> ALL_STATUSES = List.of("待发布", "售票中", "已发船", "已完成", "已取消");

    /** 可被取消的班次状态：已发船之后不能取消。 */
    static final List<String> CANCELLABLE_STATUSES = List.of("待发布", "售票中");

    /** 取消班次时写进 ticket.refund_reason 的原因（不进普通退票原因白名单）。 */
    static final String CANCEL_REFUND_REASON = "班次取消";

    /** 批量生成的最大天数跨度（含首尾），防止一次刷爆库。 */
    static final int MAX_BATCH_DAYS = 62;

    private final ScheduleRepository scheduleRepository;
    private final RouteRepository routeRepository;
    private final ShipRepository shipRepository;
    private final SeatRepository seatRepository;
    private final TicketRepository ticketRepository;
    private final OrdersRepository ordersRepository;
    private final ScheduleLock scheduleLock;
    private final SeatGenerator seatGenerator;
    private final ScheduleViewAssembler viewAssembler;
    private final OrderService orderService;
    private final Clock clock;

    public AdminScheduleService(ScheduleRepository scheduleRepository,
                                RouteRepository routeRepository,
                                ShipRepository shipRepository,
                                SeatRepository seatRepository,
                                TicketRepository ticketRepository,
                                OrdersRepository ordersRepository,
                                ScheduleLock scheduleLock,
                                SeatGenerator seatGenerator,
                                ScheduleViewAssembler viewAssembler,
                                OrderService orderService,
                                Clock clock) {
        this.scheduleRepository = scheduleRepository;
        this.routeRepository = routeRepository;
        this.shipRepository = shipRepository;
        this.seatRepository = seatRepository;
        this.ticketRepository = ticketRepository;
        this.ordersRepository = ordersRepository;
        this.scheduleLock = scheduleLock;
        this.seatGenerator = seatGenerator;
        this.viewAssembler = viewAssembler;
        this.orderService = orderService;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 查询

    @Transactional(readOnly = true)
    public AdminViews.PageResult<AdminViews.AdminScheduleRowView> search(Long routeId, Long shipId,
                                                                         LocalDate dateFrom, LocalDate dateTo,
                                                                         String status, int page, int size) {
        Page<Schedule> result = scheduleRepository.search(
                routeId, shipId, blankToNull(status),
                dateFrom == null ? null : dateFrom.atStartOfDay(),
                dateTo == null ? null : dateTo.atTime(LocalTime.MAX),
                PageRequest.of(page - 1, size, Sort.by(Sort.Direction.ASC, "departTime")));
        return AdminViews.PageResult.of(
                new org.springframework.data.domain.PageImpl<>(
                        viewAssembler.toRows(result.getContent()),
                        result.getPageable(),
                        result.getTotalElements()));
    }

    @Transactional(readOnly = true)
    public AdminViews.AdminScheduleDetailView detail(Long id) {
        Schedule schedule = load(id);
        return new AdminViews.AdminScheduleDetailView(
                viewAssembler.toRow(schedule),
                viewAssembler.seatStates(id));
    }

    // ------------------------------------------------------------------ 新增

    /**
     * 新增班次：到达时间由航线航程算出，座位数取自船舶。
     *
     * <p>座位由数据库触发器 {@code trg_schedule_generate_seats} 在同一事务里生成，
     * Java 侧不重复生成（两条生成路径必须只有一条）。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminViews.AdminScheduleRowView create(AdminForms.ScheduleForm form) {
        Route route = loadRoute(form.routeId());
        Ship ship = loadShip(form.shipId());
        LocalDateTime depart = form.departTime();
        LocalDateTime arrive = arriveTime(depart, route.getDurationMin());
        checkShipUsable(ship);
        checkNoOverlap(ship.getId(), depart, arrive, 0L);
        if (!depart.isAfter(LocalDateTime.now(clock))) {
            throw new BizException("发船时间不能早于当前时间", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }

        Schedule schedule = new Schedule();
        schedule.setRouteId(route.getId());
        schedule.setShipId(ship.getId());
        schedule.setDepartTime(depart);
        schedule.setArriveTime(arrive);
        schedule.setPrice(form.price());
        schedule.setTotalSeats(ship.getTotalSeats());
        schedule.setStatus("待发布");
        schedule = scheduleRepository.save(schedule);
        return viewAssembler.toRow(schedule);
    }

    /**
     * 批量生成班次：逐日生成，冲突的日期跳过并回报原因。
     *
     * <p>不走存储过程 {@code sp_generate_schedules}：那个过程用 INSERT IGNORE，
     * 只能跳过「同船同刻」，表达不了区间重叠，也回报不了跳过原因（口径见 deviations.md）。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminViews.BatchResultView batchGenerate(AdminForms.BatchScheduleForm form) {
        if (form.dateFrom().isAfter(form.dateTo())) {
            throw new BizException("日期范围不合法", HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR);
        }
        long days = form.dateTo().toEpochDay() - form.dateFrom().toEpochDay() + 1;
        if (days > MAX_BATCH_DAYS) {
            throw new BizException("批量生成最多 " + MAX_BATCH_DAYS + " 天", HttpStatus.BAD_REQUEST,
                    ErrorCodes.VALIDATION_ERROR);
        }
        Route route = loadRoute(form.routeId());
        Ship ship = loadShip(form.shipId());
        checkShipUsable(ship);

        List<AdminViews.BatchResultView.Skipped> skipped = new ArrayList<>();
        int created = 0;
        LocalDate day = form.dateFrom();
        while (!day.isAfter(form.dateTo())) {
            LocalDateTime depart = LocalDateTime.of(day, form.departTime());
            LocalDateTime arrive = arriveTime(depart, route.getDurationMin());
            if (scheduleRepository.existsOverlapping(ship.getId(), depart, arrive, 0L)) {
                skipped.add(new AdminViews.BatchResultView.Skipped(day.toString(), "该船舶在该时段已有班次"));
            } else {
                Schedule schedule = new Schedule();
                schedule.setRouteId(route.getId());
                schedule.setShipId(ship.getId());
                schedule.setDepartTime(depart);
                schedule.setArriveTime(arrive);
                schedule.setPrice(form.price());
                schedule.setTotalSeats(ship.getTotalSeats());
                schedule.setStatus("待发布");
                scheduleRepository.save(schedule);
                created++;
            }
            day = day.plusDays(1);
        }
        return new AdminViews.BatchResultView(created, skipped);
    }

    // ------------------------------------------------------------------ 编辑

    /**
     * 编辑班次。
     *
     * <p>守卫顺序（先说最致命的）：
     * <ol>
     *   <li>已发船 / 已完成 / 已取消 → 只能查看（需求 2.3-2）；</li>
     *   <li>有有效票时不能改发船时间与船舶（乘客已经按旧时间买票了）；</li>
     *   <li>有任何售票记录时不能换船（座位表与船不匹配，历史票没法交代）；</li>
     *   <li>改后的时段不能与同船其他班次重叠。</li>
     * </ol>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminViews.AdminScheduleRowView update(Long id, AdminForms.ScheduleUpdateForm form) {
        Schedule schedule = scheduleLock.lock(id);
        if (!"待发布".equals(schedule.getStatus()) && !"售票中".equals(schedule.getStatus())) {
            throw new BizException("班次已发船或已关闭，不能修改", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        LocalDateTime depart = form.departTime() != null ? form.departTime() : schedule.getDepartTime();
        boolean shipChanged = form.shipId() != null && !form.shipId().equals(schedule.getShipId());
        boolean timeChanged = form.departTime() != null && !form.departTime().equals(schedule.getDepartTime());

        if (shipChanged) {
            if (ticketRepository.existsActiveTicketBySchedule(id)) {
                throw new BizException("班次已售票，不能修改发船时间或船舶", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
            }
            if (ticketRepository.existsByScheduleId(id)) {
                throw new BizException("班次已有售票记录，不能更换船舶", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
            }
        } else if (timeChanged && ticketRepository.existsActiveTicketBySchedule(id)) {
            throw new BizException("班次已售票，不能修改发船时间或船舶", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }

        Ship ship = shipChanged ? loadShip(form.shipId()) : loadShip(schedule.getShipId());
        checkShipUsable(ship);
        Route route = routeRepository.findById(schedule.getRouteId())
                .orElseThrow(() -> new BizException("航线不存在", HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND));

        LocalDateTime newDepart = depart;
        LocalDateTime newArrive = arriveTime(depart, route.getDurationMin());
        if (!newDepart.isAfter(LocalDateTime.now(clock))) {
            throw new BizException("发船时间不能早于当前时间", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        checkNoOverlap(ship.getId(), newDepart, newArrive, id);

        schedule.setDepartTime(newDepart);
        schedule.setArriveTime(newArrive);
        schedule.setShipId(ship.getId());
        if (form.price() != null) {
            schedule.setPrice(form.price());
        }
        if (shipChanged) {
            // 换船：座位按新船的座位数重建（旧船座位数与新船对不上）
            schedule.setTotalSeats(ship.getTotalSeats());
            seatGenerator.rebuild(id, ship.getTotalSeats());
        }
        schedule = scheduleRepository.save(schedule);
        return viewAssembler.toRow(schedule);
    }

    // ------------------------------------------------------------------ 状态流转

    /**
     * 发布班次：待发布 → 售票中。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminViews.AdminScheduleRowView publish(Long id) {
        Schedule schedule = scheduleLock.lock(id);
        if (!"待发布".equals(schedule.getStatus())) {
            throw new BizException("班次不在待发布状态，不能发布", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        if (scheduleRepository.markOnSale(id) == 0) {
            throw new BizException("班次不在待发布状态，不能发布", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        return viewAssembler.toRow(scheduleRepository.findById(id).orElseThrow());
    }

    /**
     * 取消班次（需求 2.3-3）：所有已购票自动全额退票。
     *
     * <p>锁顺序：schedule → orders → ticket → seat（ADR-0002）。
     * <ol>
     *   <li>已支付的票 → 已退票，实退金额 = 票面全额，原因「班次取消」，座位释放；</li>
     *   <li>对应订单总额递减实退额，没有有效票的订单转「已退票」；</li>
     *   <li>待支付的订单 → 已取消（座位释放）；</li>
     *   <li>班次 → 已取消。</li>
     * </ol>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminViews.CancelResultView cancel(Long id) {
        Schedule schedule = scheduleLock.lock(id);
        if (!CANCELLABLE_STATUSES.contains(schedule.getStatus())) {
            if ("已发船".equals(schedule.getStatus()) || "已完成".equals(schedule.getStatus())) {
                throw new BizException("已发船的班次不能取消", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
            }
            throw new BizException("只有待发布或售票中的班次可以取消", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        LocalDateTime now = LocalDateTime.now(clock);

        int refundedTickets = 0;
        BigDecimal refundedAmount = BigDecimal.ZERO;
        for (Ticket ticket : ticketRepository.findByScheduleIdAndStatusOrderByIdAsc(id, "已支付")) {
            Orders order = ordersRepository.findByIdForUpdate(ticket.getOrderId()).orElse(null);
            if (order == null) {
                continue;
            }
            // 全额退：取消班次不收手续费（与普通退票的三档手续费不同）
            if (ticketRepository.markRefunded(ticket.getId(), ticket.getPrice(), CANCEL_REFUND_REASON, now) == 0) {
                continue;
            }
            refundedTickets++;
            refundedAmount = refundedAmount.add(ticket.getPrice());
            seatRepository.releaseByOrder(ticket.getSeatId(), order.getId());
            order.setTotalAmount(order.getTotalAmount().subtract(ticket.getPrice()));
            if (ticketRepository.countByOrderIdAndStatusIn(order.getId(),
                    List.of("已支付", "已检票", "已完成")) == 0) {
                order.setStatus("已退票");
            }
            ordersRepository.save(order);
        }

        int cancelledOrders = 0;
        for (Ticket ticket : ticketRepository.findByScheduleIdAndStatusOrderByIdAsc(id, "待支付")) {
            if (orderService.cancelAsSystem(ticket.getOrderId())) {
                cancelledOrders++;
            }
        }

        schedule.setStatus("已取消");
        scheduleRepository.save(schedule);
        return new AdminViews.CancelResultView(id, refundedTickets, refundedAmount, cancelledOrders);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 到达时间 = 发船时间 + 航程时长；跨零点时加 1 天（PLAN.md §1.1，不支持跨两日）。
     */
    static LocalDateTime arriveTime(LocalDateTime depart, int durationMin) {
        LocalDateTime arrive = depart.plusMinutes(durationMin);
        if (!arrive.isAfter(depart)) {
            arrive = arrive.plusDays(1);
        }
        return arrive;
    }

    private void checkShipUsable(Ship ship) {
        if (!"正常".equals(ship.getStatus())) {
            throw new BizException("该船舶当前状态不是正常，不能新建班次", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
    }

    private void checkNoOverlap(Long shipId, LocalDateTime depart, LocalDateTime arrive, Long excludeId) {
        if (scheduleRepository.existsOverlapping(shipId, depart, arrive, excludeId)) {
            throw new BizException("该船舶在该时段已有班次", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
    }

    private Route loadRoute(Long id) {
        Route route = routeRepository.findById(id)
                .orElseThrow(() -> new BizException("航线不存在", HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND));
        if (!"正常".equals(route.getStatus())) {
            throw new BizException("航线已停运，不能新建班次", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        return route;
    }

    private Ship loadShip(Long id) {
        return shipRepository.findById(id)
                .orElseThrow(() -> new BizException("船舶不存在", HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND));
    }

    private Schedule load(Long id) {
        return scheduleRepository.findById(id)
                .orElseThrow(() -> new BizException("班次不存在", HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 供测试用：座位状态查询。 */
    @Transactional(readOnly = true)
    public List<Seat> seatsOf(Long scheduleId) {
        return seatRepository.findByScheduleIdOrderByIdAsc(scheduleId);
    }
}
