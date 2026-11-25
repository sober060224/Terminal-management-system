package com.gdou.ferry.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * 船舶实体
 */
@Entity
@Table(name = "ship")
@Getter
@Setter
public class Ship {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ship_name", nullable = false, unique = true, length = 50)
    private String shipName;

    @Column(name = "total_seats", nullable = false)
    private Integer totalSeats;

    @Column(name = "ship_type", nullable = false, length = 20)
    private String shipType;

    /** 需求 2.2 功能2 的「描述」，可空（数据库列为 VARCHAR(200) NULL）。 */
    @Column(length = 200)
    private String description;

    @Column(nullable = false, length = 10)
    private String status = "正常";
}
