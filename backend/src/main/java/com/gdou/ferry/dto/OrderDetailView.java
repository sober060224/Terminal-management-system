package com.gdou.ferry.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单详情：订单字段 + 班次/航线/船舶明细 + 每张票（带座位号）。
 * 三端共用 GET /api/orders/{id}，权限由服务层判断。
 */
public record OrderDetailView(
        Long id,
        Long scheduleId,
        Long passengerId,
        Long sellerId,
        BigDecimal totalAmount,
        String status,
        LocalDateTime payTime,
        LocalDateTime expireAt,
        LocalDateTime createTime,
        Long routeId,
        String routeName,
        String startPort,
        String endPort,
        String shipName,
        LocalDateTime departTime,
        LocalDateTime arriveTime,
        List<TicketView> tickets) {
}
