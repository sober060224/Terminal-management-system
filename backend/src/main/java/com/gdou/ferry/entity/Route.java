package com.gdou.ferry.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * 航线实体
 */
@Entity
@Table(name = "route")
@Getter
@Setter
public class Route {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "route_name", nullable = false, length = 100)
    private String routeName;

    @Column(name = "start_port", nullable = false, length = 50)
    private String startPort;

    @Column(name = "end_port", nullable = false, length = 50)
    private String endPort;

    @Column(name = "duration_min", nullable = false)
    private Integer durationMin;

    /** 需求 2.2 功能1 的「航线描述」，可空（数据库列为 VARCHAR(200) NULL）。 */
    @Column(length = 200)
    private String description;

    @Column(nullable = false, length = 10)
    private String status = "正常";
}
