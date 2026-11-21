import { describe, expect, it } from 'vitest'
import { refundable } from './fee'

const now = new Date('2026-11-21T10:00:00')
const plus = (minutes) => new Date(now.getTime() + minutes * 60000)

describe('refundable', () => {
    it('距发船超过 2 小时全额退', () => {
        expect(refundable(100, plus(121), now)).toEqual({
            refundable: true, refundAmount: 100, reason: '全额退款',
        })
    })

    it('距发船恰好 2 小时扣 20%', () => {
        expect(refundable(100, plus(120), now)).toEqual({
            refundable: true, refundAmount: 80, reason: '扣 20% 手续费',
        })
    })

    it('距发船不足 2 小时扣 20% 并保留两位小数', () => {
        expect(refundable(65.5, plus(30), now).refundAmount).toBe(52.4)
    })

    it('发船后不可退', () => {
        expect(refundable(100, plus(-1), now)).toEqual({
            refundable: false, refundAmount: 0, reason: '发船后不可退票',
        })
    })
})
