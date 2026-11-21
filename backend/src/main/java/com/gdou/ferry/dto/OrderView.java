package com.gdou.ferry.dto;

import com.gdou.ferry.entity.Orders;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 下单/支付/取消接口返回的订单视图（字段顺序即 JSON 字段顺序）。 */
public record OrderView(
        Long id,
        Long scheduleId,
        Long passengerId,
        Long sellerId,
        BigDecimal totalAmount,
        String status,
        LocalDateTime payTime,
        LocalDateTime expireAt,
        LocalDateTime createTime) {

    public static OrderView of(Orders order) {
        return new OrderView(order.getId(), order.getScheduleId(), order.getPassengerId(),
                order.getSellerId(), order.getTotalAmount(), order.getStatus(),
                order.getPayTime(), order.getExpireAt(), order.getCreateTime());
    }
}
