package com.gdou.ferry.service;

import com.gdou.ferry.entity.Schedule;
import com.gdou.ferry.repository.ScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 班次状态推进任务（需求 2.4）的单测。
 */
@ExtendWith(MockitoExtension.class)
class ScheduleStatusJobTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 12, 0);

    @Mock
    private ScheduleRepository scheduleRepository;

    private ScheduleStatusJob job;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(ZoneId.of("Asia/Shanghai")).toInstant(),
                ZoneId.of("Asia/Shanghai"));
        job = new ScheduleStatusJob(scheduleRepository, clock);
    }

    @Test
    void 售票中且发船时间已过的班次推进为已发船() {
        Schedule schedule = schedule(1L, "售票中");
        when(scheduleRepository.findByStatusAndDepartTimeLessThanEqual(eq("售票中"), any()))
                .thenReturn(List.of(schedule));
        when(scheduleRepository.markDeparted(eq(1L), eq(NOW))).thenReturn(1);
        when(scheduleRepository.findByStatusAndArriveTimeLessThanEqual(eq("已发船"), any()))
                .thenReturn(List.of());

        job.advanceStatuses();

        verify(scheduleRepository).markDeparted(1L, NOW);
    }

    @Test
    void 已发船且到达时间已过的班次推进为已完成() {
        Schedule schedule = schedule(2L, "已发船");
        when(scheduleRepository.findByStatusAndDepartTimeLessThanEqual(eq("售票中"), any()))
                .thenReturn(List.of());
        when(scheduleRepository.findByStatusAndArriveTimeLessThanEqual(eq("已发船"), any()))
                .thenReturn(List.of(schedule));
        when(scheduleRepository.markCompleted(eq(2L), eq(NOW))).thenReturn(1);

        job.advanceStatuses();

        verify(scheduleRepository).markCompleted(2L, NOW);
    }

    @Test
    void 没有到点的班次时不做任何状态更新() {
        when(scheduleRepository.findByStatusAndDepartTimeLessThanEqual(eq("售票中"), any()))
                .thenReturn(List.of());
        when(scheduleRepository.findByStatusAndArriveTimeLessThanEqual(eq("已发船"), any()))
                .thenReturn(List.of());

        job.advanceStatuses();

        verify(scheduleRepository, never()).markDeparted(any(), any());
        verify(scheduleRepository, never()).markCompleted(any(), any());
    }

    private static Schedule schedule(Long id, String status) {
        Schedule schedule = new Schedule();
        schedule.setId(id);
        schedule.setStatus(status);
        return schedule;
    }
}
