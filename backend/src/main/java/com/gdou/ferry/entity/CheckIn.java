package com.gdou.ferry.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 检票记录实体
 */
@Entity
@Table(name = "check_in")
@Getter
@Setter
public class CheckIn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "check_time", nullable = false)
    private LocalDateTime checkTime = LocalDateTime.now();

    @Column(nullable = false, length = 10)
    private String status;

    @Column(name = "fail_reason", length = 50)
    private String failReason;
}
