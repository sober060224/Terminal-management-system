package com.gdou.ferry.service;

import com.gdou.ferry.common.BizException;
import com.gdou.ferry.dto.AdminForms;
import com.gdou.ferry.entity.Orders;
import com.gdou.ferry.entity.Route;
import com.gdou.ferry.entity.Schedule;
import com.gdou.ferry.entity.Ship;
import com.gdou.ferry.entity.Ticket;
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
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模块二：班次管理（新增 / 编辑 / 发布 / 取消 / 批量生成）的业务规则单测。
 *
 * <p>覆盖需求 2.3 业务规则与 2.4 状态流转。
 */
@ExtendWith(MockitoExtension.class)
class AdminScheduleServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 12, 0);

    @Mock
    private ScheduleRepository scheduleRepository;
    @Mock
    private RouteRepository routeRepository;
    @Mock
    private ShipRepository shipRepository;
    @Mock
    private SeatRepository seatRepository;
    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private OrdersRepository ordersRepository;
    @Mock
    private ScheduleLock scheduleLock;
    @Mock
    private SeatGenerator seatGenerator;
    @Mock
    private ScheduleViewAssembler viewAssembler;
    @Mock
    private OrderService orderService;

    private AdminScheduleService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(ZoneId.of("Asia/Shanghai")).toInstant(),
                ZoneId.of("Asia/Shanghai"));
        service = new AdminScheduleService(scheduleRepository, routeRepository, shipRepository,
                seatRepository, ticketRepository, ordersRepository, scheduleLock, seatGenerator,
                viewAssembler, orderService, clock);
    }

    // ------------------------------------------------------------------ 到达时间

    @Test
    void 到达时间是发船时间加航程时长() {
        LocalDateTime depart = LocalDateTime.of(2026, 12, 1, 8, 0);
        assertEquals(LocalDateTime.of(2026, 12, 1, 10, 0), AdminScheduleService.arriveTime(depart, 120));
    }

    @Test
    void 跨零点的班次到达时间加一天() {
        LocalDateTime depart = LocalDateTime.of(2026, 12, 1, 23, 0);
        assertEquals(LocalDateTime.of(2026, 12, 2, 1, 0), AdminScheduleService.arriveTime(depart, 120));
    }

    @Test
    void 到达时间正好等于发船时间也算跨零点加一天() {
        LocalDateTime depart = LocalDateTime.of(2026, 12, 1, 8, 0);
        assertEquals(LocalDateTime.of(2026, 12, 2, 8, 0), AdminScheduleService.arriveTime(depart, 1440));
    }

    // ------------------------------------------------------------------ 新增

    @Test
    void 新增班次时到达时间由服务端算出() {
        lenient().when(scheduleRepository.save(any(Schedule.class))).thenAnswer(inv -> {
            Schedule s = inv.getArgument(0);
            s.setId(10L);
            return s;
        });
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route()));
        when(shipRepository.findById(1L)).thenReturn(Optional.of(ship()));
        when(scheduleRepository.existsOverlapping(eq(1L), any(), any(), eq(0L))).thenReturn(false);

        AdminForms.ScheduleForm form = new AdminForms.ScheduleForm(1L, 1L,
                LocalDateTime.of(2026, 12, 1, 8, 0), new BigDecimal("150.00"));
        service.create(form);

        org.mockito.ArgumentCaptor<Schedule> captor = org.mockito.ArgumentCaptor.forClass(Schedule.class);
        verify(scheduleRepository).save(captor.capture());
        Schedule saved = captor.getValue();
        assertEquals(LocalDateTime.of(2026, 12, 1, 10, 0), saved.getArriveTime());
        assertEquals(120, saved.getTotalSeats());
        assertEquals("待发布", saved.getStatus());
    }

    @Test
    void 同船时间区间重叠时不能新增班次() {
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route()));
        when(shipRepository.findById(1L)).thenReturn(Optional.of(ship()));
        // 注意：不是「完全相同时刻」，是区间有交集
        when(scheduleRepository.existsOverlapping(eq(1L), any(), any(), eq(0L))).thenReturn(true);

        AdminForms.ScheduleForm form = new AdminForms.ScheduleForm(1L, 1L,
                LocalDateTime.of(2026, 12, 1, 9, 0), new BigDecimal("150.00"));
        BizException e = assertThrows(BizException.class, () -> service.create(form));
        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertEquals("该船舶在该时段已有班次", e.getMessage());
        verify(scheduleRepository, never()).save(any(Schedule.class));
    }

    @Test
    void 停运的航线不能新建班次() {
        Route stopped = route();
        stopped.setStatus("停运");
        when(routeRepository.findById(1L)).thenReturn(Optional.of(stopped));

        AdminForms.ScheduleForm form = new AdminForms.ScheduleForm(1L, 1L,
                LocalDateTime.of(2026, 12, 1, 8, 0), new BigDecimal("150.00"));
        BizException e = assertThrows(BizException.class, () -> service.create(form));
        assertEquals("航线已停运，不能新建班次", e.getMessage());
    }

    @Test
    void 维修状态的船舶不能新建班次() {
        Ship repairing = ship();
        repairing.setStatus("维修");
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route()));
        when(shipRepository.findById(1L)).thenReturn(Optional.of(repairing));

        AdminForms.ScheduleForm form = new AdminForms.ScheduleForm(1L, 1L,
                LocalDateTime.of(2026, 12, 1, 8, 0), new BigDecimal("150.00"));
        BizException e = assertThrows(BizException.class, () -> service.create(form));
        assertEquals("该船舶当前状态不是正常，不能新建班次", e.getMessage());
    }

    @Test
    void 发船时间早于当前时刻不能新建班次() {
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route()));
        when(shipRepository.findById(1L)).thenReturn(Optional.of(ship()));
        when(scheduleRepository.existsOverlapping(eq(1L), any(), any(), eq(0L))).thenReturn(false);

        AdminForms.ScheduleForm form = new AdminForms.ScheduleForm(1L, 1L,
                NOW.minusDays(1), new BigDecimal("150.00"));
        BizException e = assertThrows(BizException.class, () -> service.create(form));
        assertEquals("发船时间不能早于当前时间", e.getMessage());
    }

    // ------------------------------------------------------------------ 编辑

    @Test
    void 已发船的班次不能修改() {
        Schedule schedule = schedule("已发船");
        when(scheduleLock.lock(10L)).thenReturn(schedule);

        AdminForms.ScheduleUpdateForm form = new AdminForms.ScheduleUpdateForm(null, null, new BigDecimal("99.00"));
        BizException e = assertThrows(BizException.class, () -> service.update(10L, form));
        assertEquals("班次已发船或已关闭，不能修改", e.getMessage());
    }

    @Test
    void 已售票的班次不能改发船时间() {
        Schedule schedule = schedule("售票中");
        when(scheduleLock.lock(10L)).thenReturn(schedule);
        when(ticketRepository.existsActiveTicketBySchedule(10L)).thenReturn(true);

        AdminForms.ScheduleUpdateForm form = new AdminForms.ScheduleUpdateForm(null,
                LocalDateTime.of(2026, 12, 5, 8, 0), null);
        BizException e = assertThrows(BizException.class, () -> service.update(10L, form));
        assertEquals("班次已售票，不能修改发船时间或船舶", e.getMessage());
    }

    @Test
    void 有历史售票记录的班次不能换船() {
        Schedule schedule = schedule("待发布");
        when(scheduleLock.lock(10L)).thenReturn(schedule);
        when(ticketRepository.existsActiveTicketBySchedule(10L)).thenReturn(false);
        when(ticketRepository.existsByScheduleId(10L)).thenReturn(true);

        AdminForms.ScheduleUpdateForm form = new AdminForms.ScheduleUpdateForm(2L, null, null);
        BizException e = assertThrows(BizException.class, () -> service.update(10L, form));
        assertEquals("班次已有售票记录，不能更换船舶", e.getMessage());
    }

    @Test
    void 换船时按新船座位数重建座位() {
        Schedule schedule = schedule("待发布");
        when(scheduleLock.lock(10L)).thenReturn(schedule);
        when(ticketRepository.existsActiveTicketBySchedule(10L)).thenReturn(false);
        when(ticketRepository.existsByScheduleId(10L)).thenReturn(false);
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route()));
        Ship newShip = ship();
        newShip.setId(2L);
        newShip.setTotalSeats(80);
        when(shipRepository.findById(2L)).thenReturn(Optional.of(newShip));
        when(scheduleRepository.existsOverlapping(eq(2L), any(), any(), eq(10L))).thenReturn(false);
        lenient().when(scheduleRepository.save(any(Schedule.class))).thenAnswer(inv -> inv.getArgument(0));

        AdminForms.ScheduleUpdateForm form = new AdminForms.ScheduleUpdateForm(2L, null, null);
        service.update(10L, form);

        verify(seatGenerator).rebuild(10L, 80);
        assertEquals(80, schedule.getTotalSeats());
        assertEquals(2L, schedule.getShipId());
    }

    // ------------------------------------------------------------------ 状态流转

    @Test
    void 只有待发布的班次可以发布() {
        Schedule schedule = schedule("售票中");
        when(scheduleLock.lock(10L)).thenReturn(schedule);

        BizException e = assertThrows(BizException.class, () -> service.publish(10L));
        assertEquals("班次不在待发布状态，不能发布", e.getMessage());
        verify(scheduleRepository, never()).markOnSale(anyLong());
    }

    @Test
    void 已发船的班次不能取消() {
        Schedule schedule = schedule("已发船");
        when(scheduleLock.lock(10L)).thenReturn(schedule);

        BizException e = assertThrows(BizException.class, () -> service.cancel(10L));
        assertEquals("已发船的班次不能取消", e.getMessage());
    }

    @Test
    void 取消班次时已支付的票全额退票并释放座位() {
        Schedule schedule = schedule("售票中");
        when(scheduleLock.lock(10L)).thenReturn(schedule);

        Ticket ticket = new Ticket();
        ticket.setId(100L);
        ticket.setOrderId(50L);
        ticket.setSeatId(900L);
        ticket.setPrice(new BigDecimal("150.00"));
        ticket.setStatus("已支付");
        when(ticketRepository.findByScheduleIdAndStatusOrderByIdAsc(10L, "已支付"))
                .thenReturn(List.of(ticket));

        Orders order = new Orders();
        order.setId(50L);
        order.setStatus("已支付");
        order.setTotalAmount(new BigDecimal("150.00"));
        when(ordersRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(order));
        when(ticketRepository.markRefunded(eq(100L), eq(new BigDecimal("150.00")),
                eq("班次取消"), any())).thenReturn(1);
        when(ticketRepository.countByOrderIdAndStatusIn(eq(50L), any())).thenReturn(0L);
        when(ticketRepository.findByScheduleIdAndStatusOrderByIdAsc(10L, "待支付")).thenReturn(List.of());
        lenient().when(ordersRepository.save(any(Orders.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(scheduleRepository.save(any(Schedule.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.cancel(10L);

        assertEquals(1, result.refundedTickets());
        assertEquals(new BigDecimal("150.00"), result.refundedAmount());
        assertEquals("已取消", schedule.getStatus());
        // 座位按订单归属释放
        verify(seatRepository).releaseByOrder(900L, 50L);
        // 订单没有有效票了 → 已退票；总额递减实退额
        assertEquals("已退票", order.getStatus());
        assertEquals(new BigDecimal("0.00"), order.getTotalAmount());
    }

    @Test
    void 取消班次时待支付订单被取消() {
        Schedule schedule = schedule("售票中");
        when(scheduleLock.lock(10L)).thenReturn(schedule);
        when(ticketRepository.findByScheduleIdAndStatusOrderByIdAsc(10L, "已支付")).thenReturn(List.of());

        Ticket pending = new Ticket();
        pending.setId(101L);
        pending.setOrderId(51L);
        when(ticketRepository.findByScheduleIdAndStatusOrderByIdAsc(10L, "待支付"))
                .thenReturn(List.of(pending));
        when(orderService.cancelAsSystem(51L)).thenReturn(true);
        lenient().when(scheduleRepository.save(any(Schedule.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.cancel(10L);

        assertEquals(1, result.cancelledOrders());
        verify(orderService).cancelAsSystem(51L);
    }

    // ------------------------------------------------------------------ 批量生成

    @Test
    void 批量生成按天创建班次() {
        lenient().when(scheduleRepository.save(any(Schedule.class))).thenAnswer(inv -> inv.getArgument(0));
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route()));
        when(shipRepository.findById(1L)).thenReturn(Optional.of(ship()));
        when(scheduleRepository.existsOverlapping(eq(1L), any(), any(), eq(0L))).thenReturn(false);

        AdminForms.BatchScheduleForm form = new AdminForms.BatchScheduleForm(1L, 1L,
                java.time.LocalDate.of(2026, 12, 1), java.time.LocalDate.of(2026, 12, 3),
                java.time.LocalTime.of(8, 0), new BigDecimal("150.00"));
        var result = service.batchGenerate(form);

        assertEquals(3, result.created());
        assertTrue(result.skipped().isEmpty());
    }

    @Test
    void 批量生成时冲突的日期被跳过并回报原因() {
        lenient().when(scheduleRepository.save(any(Schedule.class))).thenAnswer(inv -> inv.getArgument(0));
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route()));
        when(shipRepository.findById(1L)).thenReturn(Optional.of(ship()));
        // 第二天与已有班次冲突
        when(scheduleRepository.existsOverlapping(eq(1L), any(), any(), eq(0L)))
                .thenAnswer(inv -> {
                    LocalDateTime depart = inv.getArgument(1);
                    return depart.getDayOfMonth() == 2;
                });

        AdminForms.BatchScheduleForm form = new AdminForms.BatchScheduleForm(1L, 1L,
                java.time.LocalDate.of(2026, 12, 1), java.time.LocalDate.of(2026, 12, 3),
                java.time.LocalTime.of(8, 0), new BigDecimal("150.00"));
        var result = service.batchGenerate(form);

        assertEquals(2, result.created());
        assertEquals(1, result.skipped().size());
        assertEquals("2026-12-02", result.skipped().get(0).date());
        assertEquals("该船舶在该时段已有班次", result.skipped().get(0).reason());
    }

    @Test
    void 批量生成跨度超过上限直接拒绝() {
        AdminForms.BatchScheduleForm form = new AdminForms.BatchScheduleForm(1L, 1L,
                java.time.LocalDate.of(2026, 12, 1), java.time.LocalDate.of(2027, 3, 1),
                java.time.LocalTime.of(8, 0), new BigDecimal("150.00"));
        BizException e = assertThrows(BizException.class, () -> service.batchGenerate(form));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertEquals("批量生成最多 62 天", e.getMessage());
    }

    @Test
    void 批量生成开始日期晚于结束日期直接拒绝() {
        AdminForms.BatchScheduleForm form = new AdminForms.BatchScheduleForm(1L, 1L,
                java.time.LocalDate.of(2026, 12, 10), java.time.LocalDate.of(2026, 12, 1),
                java.time.LocalTime.of(8, 0), new BigDecimal("150.00"));
        BizException e = assertThrows(BizException.class, () -> service.batchGenerate(form));
        assertEquals("日期范围不合法", e.getMessage());
    }

    // ------------------------------------------------------------------ 工具

    private static Route route() {
        Route route = new Route();
        route.setId(1L);
        route.setRouteName("广州-香港");
        route.setStartPort("广州港");
        route.setEndPort("香港港");
        route.setDurationMin(120);
        route.setStatus("正常");
        return route;
    }

    private static Ship ship() {
        Ship ship = new Ship();
        ship.setId(1L);
        ship.setShipName("粤港01");
        ship.setTotalSeats(120);
        ship.setShipType("高速船");
        ship.setStatus("正常");
        return ship;
    }

    private static Schedule schedule(String status) {
        Schedule schedule = new Schedule();
        schedule.setId(10L);
        schedule.setRouteId(1L);
        schedule.setShipId(1L);
        schedule.setDepartTime(LocalDateTime.of(2026, 12, 1, 8, 0));
        schedule.setArriveTime(LocalDateTime.of(2026, 12, 1, 10, 0));
        schedule.setPrice(new BigDecimal("150.00"));
        schedule.setTotalSeats(120);
        schedule.setStatus(status);
        return schedule;
    }
}
