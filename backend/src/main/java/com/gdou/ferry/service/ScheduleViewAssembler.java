package com.gdou.ferry.service;

import com.gdou.ferry.dto.AdminViews;
import com.gdou.ferry.entity.Schedule;
import com.gdou.ferry.entity.Seat;
import com.gdou.ferry.repository.RouteRepository;
import com.gdou.ferry.repository.ScheduleRepository;
import com.gdou.ferry.repository.SeatRepository;
import com.gdou.ferry.repository.ShipRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 班次视图组装：把 {@link Schedule} 变成管理端要的行视图。
 *
 * <p>管理端班次列表、航线详情里的班次列表共用这一处口径，
 * 免得两处各写一遍余票算法（余票 = 总座位 − 已售 − 未过期锁定，与 api.md §8.4.1 同口径）。
 */
@Component
public class ScheduleViewAssembler {

    private final RouteRepository routeRepository;
    private final ShipRepository shipRepository;
    private final SeatRepository seatRepository;
    private final Clock clock;

    public ScheduleViewAssembler(RouteRepository routeRepository,
                                 ShipRepository shipRepository,
                                 SeatRepository seatRepository,
                                 Clock clock) {
        this.routeRepository = routeRepository;
        this.shipRepository = shipRepository;
        this.seatRepository = seatRepository;
        this.clock = clock;
    }

    /**
     * 组装单个班次的行视图。
     */
    @Transactional(readOnly = true)
    public AdminViews.AdminScheduleRowView toRow(Schedule schedule) {
        var route = routeRepository.findById(schedule.getRouteId()).orElse(null);
        var ship = shipRepository.findById(schedule.getShipId()).orElse(null);
        return AdminViews.AdminScheduleRowView.of(
                schedule,
                route == null ? null : route.getRouteName(),
                route == null ? null : route.getStartPort(),
                route == null ? null : route.getEndPort(),
                ship == null ? null : ship.getShipName(),
                seatRepository.countByScheduleIdAndSeatStatus(schedule.getId(), "已售"),
                seatRepository.countUnavailable(schedule.getId(), LocalDateTime.now(clock))
        );
    }

    /**
     * 组装一批班次的行视图。
     */
    @Transactional(readOnly = true)
    public List<AdminViews.AdminScheduleRowView> toRows(List<Schedule> schedules) {
        return schedules.stream().map(this::toRow).toList();
    }

    /**
     * 班次详情用的座位列表（结构与 api.md §7.2 的座位图一致）。
     */
    @Transactional(readOnly = true)
    public List<AdminViews.AdminScheduleDetailView.SeatState> seatStates(Long scheduleId) {
        LocalDateTime now = LocalDateTime.now(clock);
        return seatRepository.findByScheduleIdOrderByIdAsc(scheduleId).stream()
                .map((Seat seat) -> new AdminViews.AdminScheduleDetailView.SeatState(
                        seat.getId(), seat.getSeatNo(), seat.stateAt(now)))
                .toList();
    }
}
