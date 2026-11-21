package com.gdou.ferry.service;

import com.gdou.ferry.common.BizException;
import com.gdou.ferry.common.ErrorCodes;
import com.gdou.ferry.entity.Schedule;
import com.gdou.ferry.repository.ScheduleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * ADR-0002：所有班次写操作统一从这里拿班次行锁，锁顺序 schedule → seat → orders → ticket。
 */
@Component
public class ScheduleLock {

    private final ScheduleRepository scheduleRepository;

    public ScheduleLock(ScheduleRepository scheduleRepository) {
        this.scheduleRepository = scheduleRepository;
    }

    @Transactional
    public Schedule lock(Long scheduleId) {
        return scheduleRepository.findByIdForUpdate(scheduleId)
                .orElseThrow(() -> new BizException("班次不存在", HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND));
    }

    /**
     * 改签要同时改两个班次，按 id 升序加锁，避免两个方向相反的改签互相等锁。
     */
    @Transactional
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
