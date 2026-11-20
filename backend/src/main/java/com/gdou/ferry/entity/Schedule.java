package com.gdou.ferry.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 班次实体
 */
@Entity
@Table(name = "schedule")
@Getter
@Setter
public class Schedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "route_id", nullable = false)
    private Long routeId;

    @Column(name = "ship_id", nullable = false)
    private Long shipId;

    @Column(name = "depart_time", nullable = false)
    private LocalDateTime departTime;

    @Column(name = "arrive_time", nullable = false)
    private LocalDateTime arriveTime;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "total_seats", nullable = false)
    private Integer totalSeats;

    @Column(name = "sold_seats", nullable = false)
    private Integer soldSeats = 0;

    @Column(nullable = false, length = 10)
    private String status = "待发布";
}
