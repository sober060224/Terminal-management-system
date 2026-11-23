package com.gdou.ferry.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FareRulesTest {

    private final LocalDateTime now = LocalDateTime.of(2026, 11, 21, 10, 0);
    private final BigDecimal price = new BigDecimal("100.00");

    @Test
    void 距发船超过两小时全额退() {
        BigDecimal refund = FareRules.refundable(price, now.plusMinutes(121), now);
        assertEquals(0, price.compareTo(refund));
    }

    @Test
    void 距发船恰好两小时扣两成() {
        BigDecimal refund = FareRules.refundable(price, now.plusMinutes(120), now);
        assertEquals(0, new BigDecimal("80.00").compareTo(refund));
    }

    @Test
    void 距发船不足两小时扣两成() {
        BigDecimal refund = FareRules.refundable(price, now.plusMinutes(60), now);
        assertEquals(0, new BigDecimal("80.00").compareTo(refund));
    }

    @Test
    void 距发船两小时零一秒仍全额退() {
        // 差 1 秒也算「超过 2 小时」；按分钟取整的实现会在这里错扣 20%
        BigDecimal refund = FareRules.refundable(price, now.plusMinutes(120).plusSeconds(1), now);
        assertEquals(0, price.compareTo(refund));
    }

    @Test
    void 距发船一小时五十九分五十九秒扣两成() {
        BigDecimal refund = FareRules.refundable(price, now.plusMinutes(120).minusSeconds(1), now);
        assertEquals(0, new BigDecimal("80.00").compareTo(refund));
    }

    @Test
    void 发船后拒绝退票() {
        assertThrows(BizException.class, () -> FareRules.refundable(price, now.minusSeconds(1), now));
    }
}
