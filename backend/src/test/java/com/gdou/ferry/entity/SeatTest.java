package com.gdou.ferry.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SeatTest {

    private final LocalDateTime now = LocalDateTime.of(2026, 11, 21, 12, 0);

    @Test
    void 已售优先于锁定时间() {
        Seat seat = seat("已售", now.plusHours(1));
        assertEquals("已售", seat.stateAt(now));
    }

    @Test
    void 锁定期内是锁定() {
        assertEquals("锁定", seat("锁定", now.plusMinutes(1)).stateAt(now));
    }

    @Test
    void 锁定期已过按可选() {
        assertEquals("可选", seat("锁定", now.minusMinutes(1)).stateAt(now));
    }

    @Test
    void 无锁定时间按可选() {
        assertEquals("可选", seat("锁定", null).stateAt(now));
        assertEquals("可选", seat("可选", null).stateAt(now));
    }

    private Seat seat(String status, LocalDateTime lockUntil) {
        Seat seat = new Seat();
        seat.setSeatStatus(status);
        seat.setLockUntil(lockUntil);
        return seat;
    }
}
