package com.gdou.ferry.service;

import com.gdou.ferry.common.BizException;
import com.gdou.ferry.common.ErrorCodes;
import com.gdou.ferry.entity.Schedule;
import com.gdou.ferry.repository.ScheduleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * ADR-0002：所有班次写操作统一从这里拿班次行锁，锁顺序 schedule → orders → ticket → seat。
 * <p>
 * 两个入口都是 {@code Propagation.MANDATORY}：调用方必须已经有事务。不能改成普通 {@code @Transactional}——
 * 那样代理会先开一个事务、方法返回即提交，{@code FOR UPDATE} 的锁瞬间就没了（M-8）。
 */
@Component
public class ScheduleLock {

    private final ScheduleRepository scheduleRepository;

    public ScheduleLock(ScheduleRepository scheduleRepository) {
        this.scheduleRepository = scheduleRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Schedule lock(Long scheduleId) {
        // 兜底断言：直接 new 出来（不走代理）时 MANDATORY 不生效，这里再挡一次
        // 脱离事务调用时 FOR UPDATE 的锁在方法返回（自动提交）时就没了，锁协议静默失效（M-8）
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("ScheduleLock 必须在事务内调用");
        }
        return scheduleRepository.findByIdForUpdate(scheduleId)
                .orElseThrow(() -> new BizException("班次不存在", HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND));
    }

    /**
     * 改签要同时改两个班次，按 id 升序加锁，避免两个方向相反的改签互相等锁。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, Schedule> lockAll(Long... scheduleIds) {
        TreeSet<Long> ordered = new TreeSet<>();
        for (Long scheduleId : scheduleIds) {
            if (scheduleId != null) {
                ordered.add(scheduleId);
            }
        }
        Map<Long, Schedule> locked = new HashMap<>();
        for (Long scheduleId : ordered) {
            locked.put(scheduleId, lock(scheduleId));
        }
        return locked;
    }
}
