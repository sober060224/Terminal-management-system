package com.gdou.ferry.entity;

import com.gdou.ferry.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
public class Orders extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    @Column(name = "passenger_id", nullable = false)
    private Long passengerId;

    @Column(name = "seller_id")
    private Long sellerId;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 10)
    private String status = "待支付";

    @Column(name = "pay_time")
    private LocalDateTime payTime;

    @Column(name = "expire_at")
    private LocalDateTime expireAt;
}
