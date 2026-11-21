package com.gdou.ferry.controller;

import com.gdou.ferry.common.Result;
import com.gdou.ferry.service.ScheduleQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 班次查询：售票中班次列表、班次座位实时状态。
 */
@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleQueryService scheduleQueryService;

    public ScheduleController(ScheduleQueryService scheduleQueryService) {
        this.scheduleQueryService = scheduleQueryService;
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(scheduleQueryService.listOnSale());
    }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(scheduleQueryService.detail(id));
    }

    @GetMapping("/{id}/seats")
    public Result<Map<String, Object>> seats(@PathVariable Long id) {
        return Result.ok(scheduleQueryService.seatState(id));
    }
}
