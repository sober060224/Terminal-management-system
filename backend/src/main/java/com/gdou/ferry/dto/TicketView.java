package com.gdou.ferry.dto;

import com.gdou.ferry.entity.Ticket;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 退票/改签接口返回的船票视图。 */
public record TicketView(
        Long id,
        Long orderId,
        Long scheduleId,
        Long seatId,
        String passengerName,
        String idCard,
        BigDecimal price,
        String status,
        BigDecimal refundAmount,
        String refundReason,
        LocalDateTime refundTime,
        Long changedFromTicketId,
        LocalDateTime changedAt) {

    public static TicketView of(Ticket ticket) {
        return new TicketView(ticket.getId(), ticket.getOrderId(), ticket.getScheduleId(),
                ticket.getSeatId(), ticket.getPassengerName(), ticket.getIdCard(), ticket.getPrice(),
                ticket.getStatus(), ticket.getRefundAmount(), ticket.getRefundReason(),
                ticket.getRefundTime(), ticket.getChangedFromTicketId(), ticket.getChangedAt());
    }
}
