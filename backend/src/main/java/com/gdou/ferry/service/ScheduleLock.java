package com.gdou.ferry.service;

import com.gdou.ferry.common.BizException;
import com.gdou.ferry.common.ErrorCodes;
import com.gdou.ferry.entity.Schedule;
import com.gdou.ferry.repository.ScheduleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
}
