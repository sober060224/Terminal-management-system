package com.gdou.ferry.controller;

import com.gdou.ferry.common.Result;
import com.gdou.ferry.dto.AdminForms;
import com.gdou.ferry.dto.AdminViews;
import com.gdou.ferry.service.AdminScheduleService;
import com.gdou.ferry.service.RouteService;
import com.gdou.ferry.service.ShipService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 模块二：航线与班次管理（管理员专属）。
 *
 * <p>权限由 {@code SecurityConfig} 的 {@code /api/admin/** -> hasRole("ADMIN")} 兜住，
 * 非管理员访问得到 403 {@code FORBIDDEN}。接口契约见 {@code docs/api.md} 第 8 节。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminResourceController {

    private final RouteService routeService;
    private final ShipService shipService;
    private final AdminScheduleService adminScheduleService;

    public AdminResourceController(RouteService routeService,
                                   ShipService shipService,
                                   AdminScheduleService adminScheduleService) {
        this.routeService = routeService;
        this.shipService = shipService;
        this.adminScheduleService = adminScheduleService;
    }

    // ---------------------------------------------------------------- 航线

    @GetMapping("/routes")
    public Result<AdminViews.PageResult<AdminViews.RouteView>> listRoutes(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String startPort,
            @RequestParam(required = false) String endPort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(routeService.search(name, startPort, endPort, page, size));
    }

    @GetMapping("/routes/{id}")
    public Result<AdminViews.RouteDetailView> routeDetail(@PathVariable Long id) {
        return Result.ok(routeService.detail(id));
    }

    @PostMapping("/routes")
    public Result<AdminViews.RouteView> createRoute(@Valid @RequestBody AdminForms.RouteForm form) {
        return Result.ok("新增航线成功", AdminViews.RouteView.from(routeService.create(form)));
    }

    @PutMapping("/routes/{id}")
    public Result<AdminViews.RouteView> updateRoute(@PathVariable Long id,
                                                     @Valid @RequestBody AdminForms.RouteForm form) {
        return Result.ok("修改航线成功", AdminViews.RouteView.from(routeService.update(id, form)));
    }

    @DeleteMapping("/routes/{id}")
    public Result<Void> deleteRoute(@PathVariable Long id) {
        routeService.delete(id);
        return Result.ok("删除航线成功", null);
    }

    // ---------------------------------------------------------------- 船舶

    @GetMapping("/ships")
    public Result<AdminViews.PageResult<AdminViews.ShipView>> listShips(
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(shipService.search(name, page, size));
    }

    @GetMapping("/ships/{id}")
    public Result<AdminViews.ShipView> shipDetail(@PathVariable Long id) {
        return Result.ok(shipService.detail(id));
    }

    @PostMapping("/ships")
    public Result<AdminViews.ShipView> createShip(@Valid @RequestBody AdminForms.ShipForm form) {
        return Result.ok("新增船舶成功", AdminViews.ShipView.from(shipService.create(form)));
    }

    @PutMapping("/ships/{id}")
    public Result<AdminViews.ShipView> updateShip(@PathVariable Long id,
                                                   @Valid @RequestBody AdminForms.ShipForm form) {
        return Result.ok("修改船舶成功", AdminViews.ShipView.from(shipService.update(id, form)));
    }

    @DeleteMapping("/ships/{id}")
    public Result<Void> deleteShip(@PathVariable Long id) {
        shipService.delete(id);
        return Result.ok("删除船舶成功", null);
    }

    // ---------------------------------------------------------------- 班次

    @GetMapping("/schedules")
    public Result<AdminViews.PageResult<AdminViews.AdminScheduleRowView>> listSchedules(
            @RequestParam(required = false) Long routeId,
            @RequestParam(required = false) Long shipId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(adminScheduleService.search(routeId, shipId, dateFrom, dateTo, status, page, size));
    }

    @GetMapping("/schedules/{id}")
    public Result<AdminViews.AdminScheduleDetailView> scheduleDetail(@PathVariable Long id) {
        return Result.ok(adminScheduleService.detail(id));
    }

    @PostMapping("/schedules")
    public Result<AdminViews.AdminScheduleRowView> createSchedule(
            @Valid @RequestBody AdminForms.ScheduleForm form) {
        return Result.ok("新增班次成功", adminScheduleService.create(form));
    }

    @PutMapping("/schedules/{id}")
    public Result<AdminViews.AdminScheduleRowView> updateSchedule(
            @PathVariable Long id,
            @Valid @RequestBody AdminForms.ScheduleUpdateForm form) {
        return Result.ok("修改班次成功", adminScheduleService.update(id, form));
    }

    @PostMapping("/schedules/{id}/publish")
    public Result<AdminViews.AdminScheduleRowView> publishSchedule(@PathVariable Long id) {
        return Result.ok("班次已发布", adminScheduleService.publish(id));
    }

    @PostMapping("/schedules/{id}/cancel")
    public Result<AdminViews.CancelResultView> cancelSchedule(@PathVariable Long id) {
        return Result.ok("班次已取消并完成退款", adminScheduleService.cancel(id));
    }

    @PostMapping("/schedules/batch")
    public Result<AdminViews.BatchResultView> batchSchedules(
            @Valid @RequestBody AdminForms.BatchScheduleForm form) {
        return Result.ok("批量生成完成", adminScheduleService.batchGenerate(form));
    }
}
