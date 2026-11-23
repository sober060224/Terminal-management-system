package com.gdou.ferry.common;

import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 退票手续费规则（模块三口径，见 PLAN.md D7）：
 * 距发船超过 2 小时全额退，不足等于 2 小时扣 20%，发船后不退。
 */
public final class FareRules {

    /** 全额退票的时限（分钟）：超过该分钟数全额退，恰好等于也扣 20%。 */
    public static final long FULL_REFUND_MINUTES = 120;

    /** 临近发船的扣款比例。 */
    public static final BigDecimal LATE_DEDUCT_RATIO = new BigDecimal("0.20");

    private FareRules() {
    }

    /**
     * 计算一张票的实退金额。发船后抛 CONFLICT，由接口层拒绝、不落退票流水。
     */
    public static BigDecimal refundable(BigDecimal price, LocalDateTime departTime, LocalDateTime now) {
        if (departTime == null || !departTime.isAfter(now)) {
            throw new BizException("发船后不能退票", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        // 直接比时刻而不是取整分钟：2:00:01 属于「超过 2 小时」，toMinutes() 会截成 2:00，
        // 把差 1 秒的票错扣 20%（P3-1）。
        if (Duration.between(now, departTime).compareTo(Duration.ofMinutes(FULL_REFUND_MINUTES)) > 0) {
            return price;
        }
        return price.multiply(BigDecimal.ONE.subtract(LATE_DEDUCT_RATIO))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
