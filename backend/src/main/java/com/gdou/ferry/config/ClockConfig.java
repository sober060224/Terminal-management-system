package com.gdou.ferry.config;

import com.gdou.ferry.common.BaseEntity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 业务时间统一从 Clock 取，测试里可以换成固定时钟。 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        Clock clock = Clock.systemDefaultZone();
        // JPA 实体不是 Spring Bean，create_time 只能通过静态入口拿到同一个 Clock
        BaseEntity.setClock(clock);
        return clock;
    }
}
