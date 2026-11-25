package com.gdou.ferry.dto;

import com.gdou.ferry.entity.Route;
import com.gdou.ferry.entity.Schedule;
import com.gdou.ferry.entity.Ship;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 模块二（航线与班次管理）的视图 DTO。
 *
 * <p>字段名与 {@code docs/api.md} 第 8 节一致，前端按这里取数。
 */
public class AdminViews {

    /**
     * 统一分页结果（api.md §8.1）。
     */
    public record PageResult<T>(
            List<T> items,
            long total,
            int page,
            int size) {

        public static <T> PageResult<T> of(org.springframework.data.domain.Page<T> page) {
            return new PageResult<>(page.getContent(), page.getTotalElements(),
                    page.getNumber() + 1, page.getSize());
        }
    }

    /**
     * 航线视图。
     */
    public record RouteView(
            Long id,
            String routeName,
            String startPort,
            String endPort,
            Integer durationMin,
            String description,
            String status) {

        public static RouteView from(Route route) {
            return new RouteView(
                    route.getId(),
                    route.getRouteName(),
                    route.getStartPort(),
                    route.getEndPort(),
                    route.getDurationMin(),
                    route.getDescription(),
                    route.getStatus()
            );
        }
    }

    /**
     * 航线详情：航线视图 + 该航线下的全部班次。
     */
    public record RouteDetailView(
            Long id,
            String routeName,
            String startPort,
            String endPort,
            Integer durationMin,
            String description,
            String status,
            List<AdminScheduleRowView> schedules) {

        public static RouteDetailView from(Route route, List<AdminScheduleRowView> schedules) {
            return new RouteDetailView(
                    route.getId(),
                    route.getRouteName(),
                    route.getStartPort(),
                    route.getEndPort(),
                    route.getDurationMin(),
                    route.getDescription(),
                    route.getStatus(),
                    schedules
            );
        }
    }

    /**
     * 船舶视图。
     */
    public record ShipView(
            Long id,
            String shipName,
            Integer totalSeats,
            String shipType,
            String description,
            String status) {

        public static ShipView from(Ship ship) {
            return new ShipView(
                    ship.getId(),
                    ship.getShipName(),
                    ship.getTotalSeats(),
                    ship.getShipType(),
                    ship.getDescription(),
                    ship.getStatus()
            );
        }
    }

    /**
     * 管理端班次行视图（api.md §8.4.1）。
     */
    public record AdminScheduleRowView(
            Long id,
            Long routeId,
            String routeName,
            String startPort,
            String endPort,
            Long shipId,
            String shipName,
            LocalDateTime departTime,
            LocalDateTime arriveTime,
            BigDecimal price,
            Integer totalSeats,
            long soldSeats,
            long remainingSeats,
            String status) {

        public static AdminScheduleRowView of(Schedule schedule,
                                             String routeName,
                                             String startPort,
                                             String endPort,
                                             String shipName,
                                             long soldSeats,
                                             long takenSeats) {
            return new AdminScheduleRowView(
                    schedule.getId(),
                    schedule.getRouteId(),
                    routeName,
                    startPort,
                    endPort,
                    schedule.getShipId(),
                    shipName,
                    schedule.getDepartTime(),
                    schedule.getArriveTime(),
                    schedule.getPrice(),
                    schedule.getTotalSeats(),
                    soldSeats,
                    schedule.getTotalSeats() - takenSeats,
                    schedule.getStatus()
            );
        }
    }

    /**
     * 班次详情：行视图 + 座位列表（结构与 api.md §7.2 的座位图一致）。
     */
    public record AdminScheduleDetailView(
            AdminScheduleRowView schedule,
            List<SeatState> seats) {

        public record SeatState(Long seatId, String seatNo, String state) {}
    }

    /**
     * 取消班次的结果（api.md §8.4.7）。
     */
    public record CancelResultView(
            Long id,
            int refundedTickets,
            BigDecimal refundedAmount,
            int cancelledOrders) {
    }

    /**
     * 批量生成的结果（api.md §8.4.8）。
     */
    public record BatchResultView(
            int created,
            List<Skipped> skipped) {

        public record Skipped(String date, String reason) {}
    }
}
