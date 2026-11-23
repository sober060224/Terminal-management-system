package com.gdou.ferry.common;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;

import java.time.LocalDateTime;

/**
 * 实体基类，提供 create_time 字段
 */
@MappedSuperclass
public abstract class BaseEntity {

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        if (createTime == null) {
            // 实体由 JPA 自己实例化，不是 Spring Bean，注入不进 Clock；
            // 需要冻结时间的地方（手续费、改签窗口）走 OrderService 注入的 Clock。
            createTime = LocalDateTime.now().withNano(0); // 库列是 DATETIME(0)，先截平，免得序列化格式时有时无
        }
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
