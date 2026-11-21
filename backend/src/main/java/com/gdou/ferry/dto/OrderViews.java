package com.gdou.ferry.dto;

import com.gdou.ferry.entity.Orders;
import com.gdou.ferry.entity.Ticket;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单 / 船票的出参视图，只回前端要用的字段。
 */
public final class OrderViews {

    private OrderViews() {
    }

    public static Map<String, Object> order(Orders order) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", order.getId());
        m.put("scheduleId", order.getScheduleId());
        m.put("passengerId", order.getPassengerId());
        m.put("sellerId", order.getSellerId());
        m.put("totalAmount", order.getTotalAmount());
        m.put("status", order.getStatus());
        m.put("payTime", order.getPayTime());
        m.put("expireAt", order.getExpireAt());
        m.put("createTime", order.getCreateTime());
        return m;
    }

    public static Map<String, Object> ticket(Ticket ticket) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ticket.getId());
        m.put("orderId", ticket.getOrderId());
        m.put("scheduleId", ticket.getScheduleId());
        m.put("seatId", ticket.getSeatId());
        m.put("passengerName", ticket.getPassengerName());
        m.put("idCard", ticket.getIdCard());
        m.put("price", ticket.getPrice());
        m.put("status", ticket.getStatus());
        m.put("refundAmount", ticket.getRefundAmount());
        m.put("refundReason", ticket.getRefundReason());
        m.put("refundTime", ticket.getRefundTime());
        m.put("changedFromTicketId", ticket.getChangedFromTicketId());
        m.put("changedAt", ticket.getChangedAt());
        return m;
    }

    public static Map<String, Object> orderWithTickets(Orders order, List<Ticket> tickets) {
        Map<String, Object> m = order(order);
        m.put("tickets", tickets.stream().map(OrderViews::ticket).toList());
        return m;
    }
}
