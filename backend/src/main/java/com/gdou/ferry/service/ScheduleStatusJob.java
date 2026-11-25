package com.gdou.ferry.service;

import com.gdou.ferry.repository.ScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 班次状态推进（需求 2.4 状态流转）。
 *
 * <p>待发布 → 售票中 是管理员手动发布（{@code POST /api/admin/schedules/{id}/publish}），
 * 后两跳由本任务按时间自动推进：
 * <ul>
 *   <li>{@code 售票中} + {@code depart_time <= now} → {@code 已发船}；</li>
 *   <li>{@code 已发船} + {@code arrive_time <= now} → {@code 已完成}。</li>
 * </ul>
 *
 * <p>用条件更新（{@code markDeparted} / {@code markCompleted}）而不是读改写：
 * 并发下只有一方能改到行，且状态不会回退（已发船的班次不会被改回售票中）。
 *
 * <p>即使这个任务没跑，售票与退改签的读侧仍按 {@code depart_time} 实时过滤，
 * 所以「状态字段滞后」不影响业务正确性（ADR-0003 的惰性判断思路）。
 */
@Service
public class ScheduleStatusJob {

    private static final Logger log = LoggerFactory.getLogger(ScheduleStatusJob.class);

    private final ScheduleRepository scheduleRepository;
    private final Clock clock;

    public ScheduleStatusJob(ScheduleRepository scheduleRepository, Clock clock) {
        this.scheduleRepository = scheduleRepository;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1M")
    @Transactional
    public void advanceStatuses() {
        LocalDateTime now = LocalDateTime.now(clock);
        int departed = 0;
        int completed = 0;
        for (var schedule : scheduleRepository.findByStatusAndDepartTimeLessThanEqual("售票中", now)) {
            departed += scheduleRepository.markDeparted(schedule.getId(), now);
        }
        for (var schedule : scheduleRepository.findByStatusAndArriveTimeLessThanEqual("已发船", now)) {
            completed += scheduleRepository.markCompleted(schedule.getId(), now);
        }
        if (departed > 0 || completed > 0) {
            log.info("班次状态推进：{} 个班次转为已发船，{} 个班次转为已完成", departed, completed);
        }
    }
}
