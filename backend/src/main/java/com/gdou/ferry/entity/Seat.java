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

    /**
     * 座位当前状态（ADR-0003 惰性判断）：已售 > 锁定（未到解锁时间）> 可选。
     * 锁定期已过按可选算，由调用方决定是否回收。
     */
    public String stateAt(LocalDateTime now) {
        if ("已售".equals(seatStatus)) {
            return "已售";
        }
        if ("锁定".equals(seatStatus) && lockUntil != null && lockUntil.isAfter(now)) {
            return "锁定";
        }
        return "可选";
    }
}
