package com.gdou.ferry.service;

import com.gdou.ferry.common.BizException;
import com.gdou.ferry.common.ErrorCodes;
import com.gdou.ferry.entity.Route;
import com.gdou.ferry.entity.Schedule;
import com.gdou.ferry.entity.Seat;
import com.gdou.ferry.entity.Ship;
import com.gdou.ferry.repository.RouteRepository;
import com.gdou.ferry.repository.ScheduleRepository;
import com.gdou.ferry.repository.SeatRepository;
import com.gdou.ferry.repository.ShipRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 班次查询：售票中班次列表、班次座位实时状态。
 */
@Service
public class ScheduleQueryService {

    private final ScheduleRepository scheduleRepository;
    private final RouteRepository routeRepository;
    private final ShipRepository shipRepository;
    private final SeatRepository seatRepository;
    private final Clock clock;

    public ScheduleQueryService(ScheduleRepository scheduleRepository,
                                RouteRepository routeRepository,
                                ShipRepository shipRepository,
                                SeatRepository seatRepository,
                                Clock clock) {
        this.scheduleRepository = scheduleRepository;
        this.routeRepository = routeRepository;
        this.shipRepository = shipRepository;
        this.seatRepository = seatRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listOnSale() {
        LocalDateTime now = LocalDateTime.now(clock);
        // 已发船但状态还停在「售票中」的班次不该出现在列表里（班次状态流转属模块四，
        // 这里只在读侧按发船时间过滤，下单入口还有一道同样的校验）
        return scheduleRepository.findByStatusOrderByDepartTimeAsc("售票中").stream()
                .filter(schedule -> schedule.getDepartTime().isAfter(now))
                .map(this::scheduleView)
                .toList();
    }

    /**
     * 单个班次详情，选座下单页用。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long scheduleId) {
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BizException("班次不存在", HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND));
        return scheduleView(schedule);
    }

    /**
     * 某班次的座位实时状态，供选座页使用。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> seatState(Long scheduleId) {
        scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BizException("班次不存在", HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND));
        LocalDateTime now = LocalDateTime.now(clock);
        List<Map<String, Object>> seats = seatRepository.findByScheduleIdOrderByIdAsc(scheduleId).stream()
                .map(seat -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("seatId", seat.getId());
                    m.put("seatNo", seat.getSeatNo());
        m.put("state", seat.stateAt(now));
                    return m;
                })
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scheduleId", scheduleId);
        result.put("seats", seats);
        return result;
    }

    private Map<String, Object> scheduleView(Schedule schedule) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", schedule.getId());
        m.put("routeId", schedule.getRouteId());
        m.put("shipId", schedule.getShipId());
        m.put("departTime", schedule.getDepartTime());
        m.put("arriveTime", schedule.getArriveTime());
        m.put("price", schedule.getPrice());
        m.put("totalSeats", schedule.getTotalSeats());
        Route route = routeRepository.findById(schedule.getRouteId()).orElse(null);
        Ship ship = shipRepository.findById(schedule.getShipId()).orElse(null);
        m.put("routeName", route == null ? null : route.getRouteName());
        m.put("startPort", route == null ? null : route.getStartPort());
        m.put("endPort", route == null ? null : route.getEndPort());
        m.put("shipName", ship == null ? null : ship.getShipName());
        // 余票按座位表实时统计：座位表是占用状态的唯一事实来源（ADR-0003），
        // 不读 schedule.sold_seats，避免该冗余列与座位表脱节后算错。
        long sold = seatRepository.countByScheduleIdAndSeatStatus(schedule.getId(), "已售");
        m.put("remainingSeats", schedule.getTotalSeats() - sold);
        return m;
    }

}
