package com.gdou.ferry.common;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 实体基类，提供 create_time 字段
 */
@MappedSuperclass
public abstract class BaseEntity {

    /**
     * 业务时间统一从 Clock 取（ClockConfig 启动时注入），测试里可以换成固定时钟。
     * 实体由 JPA 自己实例化，拿不到 Spring Bean，只能用这个静态入口。
     */
    private static volatile Clock clock = Clock.systemDefaultZone();

    public static void setClock(Clock newClock) {
        if (newClock != null) {
            clock = newClock;
        }
    }

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        if (createTime == null) {
            createTime = LocalDateTime.now(clock).withNano(0); // 库列是 DATETIME(0)，先截平，免得序列化格式时有时无
        }
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
