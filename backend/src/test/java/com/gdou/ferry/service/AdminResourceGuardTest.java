package com.gdou.ferry.service;

import com.gdou.ferry.common.BizException;
import com.gdou.ferry.dto.AdminForms;
import com.gdou.ferry.entity.Route;
import com.gdou.ferry.entity.Schedule;
import com.gdou.ferry.entity.Ship;
import com.gdou.ferry.repository.RouteRepository;
import com.gdou.ferry.repository.ScheduleRepository;
import com.gdou.ferry.repository.ShipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模块二：航线 / 船舶管理的前置校验单测。
 *
 * <p>覆盖需求 2.3-4「删除航线/船舶有前置校验」与 2.2 功能1/功能2 的编辑守卫。
 */
@ExtendWith(MockitoExtension.class)
class AdminResourceGuardTest {

    @Mock
    private RouteRepository routeRepository;
    @Mock
    private ShipRepository shipRepository;
    @Mock
    private ScheduleRepository scheduleRepository;

    private RouteService routeService;
    private ShipService shipService;

    @BeforeEach
    void setUp() {
        routeService = new RouteService(routeRepository, scheduleRepository);
        shipService = new ShipService(shipRepository, scheduleRepository);
    }

    // ------------------------------------------------------------------ 航线

    @Test
    void 航线有未完成班次时不能删除() {
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route()));
        when(scheduleRepository.countByRouteIdAndStatusIn(1L, List.of("待发布", "售票中"))).thenReturn(1L);

        BizException e = assertThrows(BizException.class, () -> routeService.delete(1L));
        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertEquals("该航线还有未完成班次，不能删除", e.getMessage());
        verify(routeRepository, never()).delete(any(Route.class));
    }

    @Test
    void 航线只有历史班次时也不物理删除而是提示停运() {
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route()));
        when(scheduleRepository.countByRouteIdAndStatusIn(1L, List.of("待发布", "售票中"))).thenReturn(0L);
        when(scheduleRepository.existsByRouteId(1L)).thenReturn(true);

        BizException e = assertThrows(BizException.class, () -> routeService.delete(1L));
        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertEquals("该航线已有历史班次，请改为停运", e.getMessage());
        verify(routeRepository, never()).delete(any(Route.class));
    }

    @Test
    void 航线没有任何班次时可以删除() {
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route()));
        when(scheduleRepository.countByRouteIdAndStatusIn(1L, List.of("待发布", "售票中"))).thenReturn(0L);
        when(scheduleRepository.existsByRouteId(1L)).thenReturn(false);

        routeService.delete(1L);
        verify(routeRepository).delete(any(Route.class));
    }

    @Test
    void 航线已有班次时不能改航程时长() {
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route()));
        when(scheduleRepository.existsByRouteId(1L)).thenReturn(true);

        AdminForms.RouteForm form = new AdminForms.RouteForm("广州-香港", "广州港", "香港港",
                999, "改了时长", "正常");
        BizException e = assertThrows(BizException.class, () -> routeService.update(1L, form));
        assertEquals("该航线已有班次，不能修改航程时长", e.getMessage());
    }

    @Test
    void 航线没有班次时可以改航程时长() {
        Route route = route();
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
        when(scheduleRepository.existsByRouteId(1L)).thenReturn(false);
        when(routeRepository.save(any(Route.class))).thenAnswer(inv -> inv.getArgument(0));

        AdminForms.RouteForm form = new AdminForms.RouteForm("广州-香港", "广州港", "香港港",
                999, "改了时长", "正常");
        routeService.update(1L, form);
        assertEquals(999, route.getDurationMin());
    }

    @Test
    void 航线名重复时不能新增() {
        when(routeRepository.existsByRouteName("广州-香港")).thenReturn(true);

        AdminForms.RouteForm form = new AdminForms.RouteForm("广州-香港", "广州港", "香港港",
                120, null, "正常");
        BizException e = assertThrows(BizException.class, () -> routeService.create(form));
        assertEquals("航线名称已存在", e.getMessage());
    }

    @Test
    void 航线不存在返回404() {
        when(routeRepository.findById(99L)).thenReturn(Optional.empty());
        BizException e = assertThrows(BizException.class, () -> routeService.delete(99L));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        assertEquals("航线不存在", e.getMessage());
    }

    // ------------------------------------------------------------------ 船舶

    @Test
    void 船舶有未完成班次时不能删除() {
        when(shipRepository.findById(1L)).thenReturn(Optional.of(ship()));
        when(scheduleRepository.countByShipIdAndStatusIn(1L, List.of("待发布", "售票中"))).thenReturn(1L);

        BizException e = assertThrows(BizException.class, () -> shipService.delete(1L));
        assertEquals("该船舶还有未完成班次，不能删除", e.getMessage());
        verify(shipRepository, never()).delete(any(Ship.class));
    }

    @Test
    void 船舶只有历史班次时提示改为停用() {
        when(shipRepository.findById(1L)).thenReturn(Optional.of(ship()));
        when(scheduleRepository.countByShipIdAndStatusIn(1L, List.of("待发布", "售票中"))).thenReturn(0L);
        when(scheduleRepository.existsByShipId(1L)).thenReturn(true);

        BizException e = assertThrows(BizException.class, () -> shipService.delete(1L));
        assertEquals("该船舶已有历史班次，请将状态改为停用", e.getMessage());
    }

    @Test
    void 船舶没有任何班次时可以删除() {
        when(shipRepository.findById(1L)).thenReturn(Optional.of(ship()));
        when(scheduleRepository.countByShipIdAndStatusIn(1L, List.of("待发布", "售票中"))).thenReturn(0L);
        when(scheduleRepository.existsByShipId(1L)).thenReturn(false);

        shipService.delete(1L);
        verify(shipRepository).delete(any(Ship.class));
    }

    @Test
    void 船舶已有班次时不能改总座位数() {
        when(shipRepository.findById(1L)).thenReturn(Optional.of(ship()));
        when(scheduleRepository.existsByShipId(1L)).thenReturn(true);

        AdminForms.ShipForm form = new AdminForms.ShipForm("粤港01", 999, "高速船", null, "正常");
        BizException e = assertThrows(BizException.class, () -> shipService.update(1L, form));
        assertEquals("该船舶已有班次，不能修改总座位数", e.getMessage());
    }

    @Test
    void 船舶名重复时不能新增() {
        when(shipRepository.existsByShipName("粤港01")).thenReturn(true);

        AdminForms.ShipForm form = new AdminForms.ShipForm("粤港01", 120, "高速船", null, "正常");
        BizException e = assertThrows(BizException.class, () -> shipService.create(form));
        assertEquals("船舶名称已存在", e.getMessage());
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

    /** 让 Mockito 忽略未使用的参数告警。 */
    @SuppressWarnings("unused")
    private void unused() {
        verify(scheduleRepository, never()).save(any(Schedule.class));
        when(scheduleRepository.findByRouteIdOrderByDepartTimeAsc(anyLong())).thenReturn(List.of());
        when(routeRepository.existsByRouteNameAndIdNot(anyString(), anyLong())).thenReturn(false);
        when(shipRepository.existsByShipNameAndIdNot(anyString(), anyLong())).thenReturn(false);
        when(routeRepository.save(any(Route.class))).thenAnswer(inv -> inv.getArgument(0));
        eq(1L);
    }
}
