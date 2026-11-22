package com.gdou.ferry.dto;

import com.gdou.ferry.entity.Ticket;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 船票视图。seatNo 只有订单详情接口会填（列表接口不带座位号，省一次座位查询）。 */
public record TicketView(
        Long id,
        Long orderId,
        Long scheduleId,
        Long seatId,
        String seatNo,
        String passengerName,
        String idCard,
        String phone,
        BigDecimal price,
        String status,
        BigDecimal refundAmount,
        String refundReason,
        LocalDateTime refundTime,
        Long changedFromTicketId,
        LocalDateTime changedAt) {

    public static TicketView of(Ticket ticket) {
        return new TicketView(ticket.getId(), ticket.getOrderId(), ticket.getScheduleId(),
                ticket.getSeatId(), null, ticket.getPassengerName(), ticket.getIdCard(), ticket.getPhone(),
                ticket.getPrice(), ticket.getStatus(), ticket.getRefundAmount(), ticket.getRefundReason(),
                ticket.getRefundTime(), ticket.getChangedFromTicketId(), ticket.getChangedAt());
    }

    public static TicketView of(Ticket ticket, String seatNo) {
        return new TicketView(ticket.getId(), ticket.getOrderId(), ticket.getScheduleId(),
                ticket.getSeatId(), seatNo, ticket.getPassengerName(), ticket.getIdCard(), ticket.getPhone(),
                ticket.getPrice(), ticket.getStatus(), ticket.getRefundAmount(), ticket.getRefundReason(),
                ticket.getRefundTime(), ticket.getChangedFromTicketId(), ticket.getChangedAt());
    }
}
