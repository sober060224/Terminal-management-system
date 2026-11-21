// 退票手续费口径，与后端 common/FareRules 保持一致（PLAN.md D7）：
// 距发船 >2 小时全额退，≤2 小时扣 20%，发船后不可退。
export const FULL_REFUND_MINUTES = 120
export const LATE_DEDUCT_RATIO = 0.20

/**
 * 计算某张票的可退金额。
 * @param {number} price 票面价
 * @param {Date|string} departTime 发船时间
 * @param {Date} now 当前时间
 * @returns {{ refundable: boolean, refundAmount: number, reason: string }}
 */
export function refundable(price, departTime, now = new Date()) {
    const depart = new Date(departTime)
    const nowMs = now.getTime()
    if (depart.getTime() <= nowMs) {
        return { refundable: false, refundAmount: 0, reason: '发船后不可退票' }
    }
    const minutes = (depart.getTime() - nowMs) / 60000
    if (minutes > FULL_REFUND_MINUTES) {
        return { refundable: true, refundAmount: round2(price), reason: '全额退款' }
    }
    return { refundable: true, refundAmount: round2(price * (1 - LATE_DEDUCT_RATIO)), reason: '扣 20% 手续费' }
}

function round2(n) {
    return Math.round(n * 100) / 100
}
