import { describe, it, expect } from 'vitest'
import {
    arriveAt,
    crossesMidnight,
    expandDates,
    validateScheduleForm,
    validateBatchForm,
    validateDateRange,
    effectiveArrive,
    composeDepartTime,
    MAX_BATCH_SPAN
} from './scheduleRules'

// 固定「当前时间」，让「发船时间不能早于当前时间」这条分支可复现。
const NOW = new Date(2026, 10, 25, 12, 0, 0) // 2026-11-25 12:00:00

describe('arriveAt（§8.4.3 到达时间 = 发船 + 航程时长）', () => {
    it('同一天内的航程，直接加时长', () => {
        expect(arriveAt('2026-11-26T08:00:00', 120)).toBe('2026-11-26T10:00:00')
    })

    it('时长为 0 时到达正好等于发船，按「不晚于发船」规则 +1 天', () => {
        expect(arriveAt('2026-11-26T08:00:00', 0)).toBe('2026-11-27T08:00:00')
    })

    it('跨零点：到达早于发船，加 1 天', () => {
        expect(arriveAt('2026-11-26T22:00:00', 180)).toBe('2026-11-27T01:00:00')
    })

    it('跨零点：到达正好等于发船，算 +1 天（§8.4.3「不晚于发船」）', () => {
        expect(arriveAt('2026-11-26T08:00:00', 1440)).toBe('2026-11-27T08:00:00')
    })

    it('跨零点：24 小时整除时也要落到次日', () => {
        expect(arriveAt('2026-11-26T23:00:00', 1440)).toBe('2026-11-27T23:00:00')
    })

    it('跨零点：跨月', () => {
        expect(arriveAt('2026-11-30T23:30:00', 120)).toBe('2026-12-01T01:30:00')
    })

    it('跨零点：跨年', () => {
        expect(arriveAt('2026-12-31T23:00:00', 120)).toBe('2027-01-01T01:00:00')
    })

    it('不带秒的时间也能算', () => {
        expect(arriveAt('2026-11-26T08:00', 90)).toBe('2026-11-26T09:30:00')
    })

    it('非法输入返回空字符串', () => {
        expect(arriveAt('不是时间', 60)).toBe('')
        expect(arriveAt('', 60)).toBe('')
        expect(arriveAt('2026-11-26T08:00:00', -1)).toBe('')
    })

    it('不存在的日期视为非法（2026-02-30）', () => {
        expect(arriveAt('2026-02-30T08:00:00', 60)).toBe('')
    })
})

describe('effectiveArrive / crossesMidnight', () => {
    it('后端已把跨零点班次的 arriveTime 推到次日时，直接沿用', () => {
        expect(effectiveArrive('2026-11-26T22:00:00', '2026-11-27T01:00:00'))
            .toBe('2026-11-27T01:00:00')
        expect(crossesMidnight('2026-11-26T22:00:00', '2026-11-27T01:00:00')).toBe(true)
    })

    it('后端返回同日但早于发船的 arriveTime 时，前端补 +1 天', () => {
        expect(effectiveArrive('2026-11-26T22:00:00', '2026-11-26T01:00:00'))
            .toBe('2026-11-27T01:00:00')
        expect(crossesMidnight('2026-11-26T22:00:00', '2026-11-26T01:00:00')).toBe(true)
    })

    it('同日到达（晚于发船）不算跨零点', () => {
        expect(crossesMidnight('2026-11-26T08:00:00', '2026-11-26T10:00:00')).toBe(false)
        expect(effectiveArrive('2026-11-26T08:00:00', '2026-11-26T10:00:00'))
            .toBe('2026-11-26T10:00:00')
    })

    it('同日且正好等于发船，算跨零点', () => {
        expect(crossesMidnight('2026-11-26T08:00:00', '2026-11-26T08:00:00')).toBe(true)
    })
})

describe('expandDates（日期区间展开，含首尾）', () => {
    it('单日区间返回 1 个日期', () => {
        expect(expandDates('2026-12-01', '2026-12-01')).toEqual(['2026-12-01'])
    })

    it('多日区间按天展开', () => {
        expect(expandDates('2026-12-01', '2026-12-04')).toEqual([
            '2026-12-01', '2026-12-02', '2026-12-03', '2026-12-04'
        ])
    })

    it('跨月展开', () => {
        expect(expandDates('2026-11-30', '2026-12-02')).toEqual([
            '2026-11-30', '2026-12-01', '2026-12-02'
        ])
    })

    it('跨年展开', () => {
        expect(expandDates('2026-12-30', '2027-01-02')).toEqual([
            '2026-12-30', '2026-12-31', '2027-01-01', '2027-01-02'
        ])
    })

    it('闰年 2 月展开到 29 日', () => {
        expect(expandDates('2028-02-28', '2028-03-01')).toEqual([
            '2028-02-28', '2028-02-29', '2028-03-01'
        ])
    })

    it('起始晚于结束返回空数组', () => {
        expect(expandDates('2026-12-05', '2026-12-01')).toEqual([])
    })

    it('非法日期返回空数组', () => {
        expect(expandDates('2026-13-01', '2026-12-31')).toEqual([])
        expect(expandDates('', '2026-12-31')).toEqual([])
        expect(expandDates('2026-12-01', '不是日期')).toEqual([])
    })
})

describe('validateDateRange（§8.4.8 批量生成跨度 ≤ 62 天）', () => {
    it('正常区间通过', () => {
        expect(validateDateRange('2026-12-01', '2026-12-31')).toEqual({ ok: true, message: '' })
    })

    it('正好 62 天通过', () => {
        expect(validateDateRange('2026-12-01', '2027-01-31')).toEqual({ ok: true, message: '' })
    })

    it('超过 62 天不通过', () => {
        const r = validateDateRange('2026-12-01', '2027-02-01')
        expect(r.ok).toBe(false)
        expect(r.message).toContain('62')
    })

    it('起始晚于结束报「日期范围不合法」', () => {
        const r = validateDateRange('2026-12-31', '2026-12-01')
        expect(r.ok).toBe(false)
        expect(r.message).toBe('日期范围不合法')
    })

    it('MAX_BATCH_SPAN 与文档一致', () => {
        expect(MAX_BATCH_SPAN).toBe(62)
    })
})

describe('validateScheduleForm（§8.4.3 / §8.6）', () => {
    const base = {
        routeId: 1,
        shipId: 2,
        departTime: '2026-11-26T08:00:00',
        price: 150
    }

    it('合法表单通过', () => {
        expect(validateScheduleForm(base, NOW)).toEqual({ ok: true, message: '' })
    })

    it('未选航线不通过', () => {
        const r = validateScheduleForm({ ...base, routeId: null }, NOW)
        expect(r.ok).toBe(false)
        expect(r.message).toBe('请选择航线')
    })

    it('未选船舶不通过', () => {
        const r = validateScheduleForm({ ...base, shipId: '' }, NOW)
        expect(r.ok).toBe(false)
        expect(r.message).toBe('请选择船舶')
    })

    it('未填发船时间不通过', () => {
        const r = validateScheduleForm({ ...base, departTime: '' }, NOW)
        expect(r.ok).toBe(false)
        expect(r.message).toBe('请选择发船时间')
    })

    it('发船时间格式不正确不通过', () => {
        const r = validateScheduleForm({ ...base, departTime: '明天早上八点' }, NOW)
        expect(r.ok).toBe(false)
        expect(r.message).toBe('发船时间格式不正确')
    })

    it('发船时间早于当前时间不通过', () => {
        const r = validateScheduleForm({ ...base, departTime: '2026-11-25T09:00:00' }, NOW)
        expect(r.ok).toBe(false)
        expect(r.message).toBe('发船时间不能早于当前时间')
    })

    it('发船时间正好等于当前时间视为通过', () => {
        const r = validateScheduleForm(
            { ...base, departTime: '2026-11-25T12:00:00' }, NOW)
        expect(r.ok).toBe(true)
    })

    it('未填票价不通过', () => {
        const r = validateScheduleForm({ ...base, price: '' }, NOW)
        expect(r.ok).toBe(false)
        expect(r.message).toBe('请填写票价')
    })

    it('票价为负不通过', () => {
        const r = validateScheduleForm({ ...base, price: -1 }, NOW)
        expect(r.ok).toBe(false)
        expect(r.message).toBe('票价不能为负')
    })

    it('票价为 0 通过', () => {
        expect(validateScheduleForm({ ...base, price: 0 }, NOW).ok).toBe(true)
    })

    it('票价格式不合法不通过', () => {
        const r = validateScheduleForm({ ...base, price: 'abc' }, NOW)
        expect(r.ok).toBe(false)
        expect(r.message).toBe('票价格式不正确')
    })

    it('带上日期区间时额外校验跨度', () => {
        const r = validateScheduleForm(
            { ...base, dateFrom: '2026-12-01', dateTo: '2027-03-01' }, NOW)
        expect(r.ok).toBe(false)
        expect(r.message).toContain('62')
    })

    it('ignorePast 为 true 时（编辑但未改发船时间）不拦过去时间', () => {
        const r = validateScheduleForm(
            { ...base, departTime: '2026-11-20T08:00:00', ignorePast: true }, NOW)
        expect(r.ok).toBe(true)
    })
})

describe('validateBatchForm（§8.4.8）', () => {
    const base = {
        routeId: 1,
        shipId: 2,
        dateFrom: '2026-12-01',
        dateTo: '2026-12-31',
        departTime: '08:00:00',
        price: 150
    }

    it('合法批量表单通过', () => {
        expect(validateBatchForm(base)).toEqual({ ok: true, message: '' })
    })

    it('日期范围不合法不通过', () => {
        const r = validateBatchForm({ ...base, dateFrom: '2026-12-31', dateTo: '2026-12-01' })
        expect(r.ok).toBe(false)
        expect(r.message).toBe('日期范围不合法')
    })

    it('跨度超过 62 天不通过', () => {
        const r = validateBatchForm({ ...base, dateFrom: '2026-12-01', dateTo: '2027-03-01' })
        expect(r.ok).toBe(false)
        expect(r.message).toContain('62')
    })

    it('发船时刻格式不正确不通过', () => {
        const r = validateBatchForm({ ...base, departTime: '8 点' })
        expect(r.ok).toBe(false)
        expect(r.message).toBe('发船时刻格式不正确')
    })

    it('发船时刻允许 HH:mm', () => {
        expect(validateBatchForm({ ...base, departTime: '08:00' }).ok).toBe(true)
    })

    it('票价为负不通过', () => {
        const r = validateBatchForm({ ...base, price: -5 })
        expect(r.ok).toBe(false)
        expect(r.message).toBe('票价不能为负')
    })

    it('未选航线不通过', () => {
        const r = validateBatchForm({ ...base, routeId: null })
        expect(r.ok).toBe(false)
        expect(r.message).toBe('请选择航线')
    })
})

describe('composeDepartTime', () => {
    it('日期 + 时刻拼成 ISO 本地时间', () => {
        expect(composeDepartTime('2026-12-01', '08:00:00')).toBe('2026-12-01T08:00:00')
        expect(composeDepartTime('2026-12-01', '08:30')).toBe('2026-12-01T08:30:00')
    })

    it('非法输入返回空字符串', () => {
        expect(composeDepartTime('不是日期', '08:00:00')).toBe('')
        expect(composeDepartTime('2026-12-01', '乱写')).toBe('')
    })
})
