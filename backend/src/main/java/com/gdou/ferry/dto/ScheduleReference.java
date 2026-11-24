package com.gdou.ferry.dto;

import com.gdou.ferry.entity.Schedule;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模块二提供给检票、报表等模块的只读班次引用。
 * routeName、shipName 只在需要展示时补充。
 */
public record ScheduleReference(
        Long id,
        Long routeId,
        Long shipId,
        LocalDateTime departTime,
        LocalDateTime arriveTime,
        BigDecimal price,
        String status,
        String routeName,
        String shipName) {

    public static ScheduleReference from(Schedule schedule) {
        return from(schedule, null, null);
    }

    public static ScheduleReference from(Schedule schedule, String routeName, String shipName) {
        return new ScheduleReference(
                schedule.getId(),
                schedule.getRouteId(),
                schedule.getShipId(),
                schedule.getDepartTime(),
                schedule.getArriveTime(),
                schedule.getPrice(),
                schedule.getStatus(),
                routeName,
                shipName
        );
    }
}
