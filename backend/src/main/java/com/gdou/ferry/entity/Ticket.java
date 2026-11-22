package com.gdou.ferry.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 船票实体
 */
@Entity
@Table(name = "ticket")
@Getter
@Setter
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    @Column(name = "passenger_name", nullable = false, length = 50)
    private String passengerName;

    @Column(name = "id_card", nullable = false, length = 18)
    private String idCard;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false, length = 10)
    private String status = "待支付";

    @Column(name = "refund_amount", precision = 10, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "refund_reason", length = 200)
    private String refundReason;

    @Column(name = "refund_time")
    private LocalDateTime refundTime;

    @Column(name = "changed_from_ticket_id")
    private Long changedFromTicketId;

    @Column(name = "changed_at")
    private LocalDateTime changedAt;
}
