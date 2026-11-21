package com.gdou.ferry.dto;

import com.gdou.ferry.entity.Orders;
import com.gdou.ferry.entity.Ticket;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 订单列表一行：订单字段 + 票 + routeId（前端改签时筛同航线班次）。 */
public record OrderRowView(
        Long id,
        Long scheduleId,
        Long passengerId,
        Long sellerId,
        BigDecimal totalAmount,
        String status,
        LocalDateTime payTime,
        LocalDateTime expireAt,
        LocalDateTime createTime,
        List<TicketView> tickets,
        Long routeId) {

    public static OrderRowView of(Orders order, List<Ticket> tickets, Long routeId) {
        return new OrderRowView(order.getId(), order.getScheduleId(), order.getPassengerId(),
                order.getSellerId(), order.getTotalAmount(), order.getStatus(), order.getPayTime(),
                order.getExpireAt(), order.getCreateTime(),
                tickets.stream().map(TicketView::of).toList(), routeId);
    }
}
