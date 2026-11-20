package com.gdou.ferry.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 座位实体
 */
@Entity
@Table(name = "seat")
@Getter
@Setter
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    @Column(name = "seat_no", nullable = false, length = 10)
    private String seatNo;

    @Column(name = "seat_status", nullable = false, length = 10)
    private String seatStatus = "可选";

    @Column(name = "lock_until")
    private LocalDateTime lockUntil;

    @Column(name = "order_id")
    private Long orderId;
}
