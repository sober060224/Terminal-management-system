// 模块二（航线/船舶/班次管理）的纯计算与校验规则。
// 抽成独立模块是为了能被 vitest 直接测：页面里只做展示和请求，
// 所有跨零点、日期展开、前置校验的口径都集中在这里，改一处就够。
// 口径来源：docs/api.md §8.4.3（新增班次）、§8.4.8（批量生成）、§8.6（错误文案）。

// 批量生成一次最多跨的天数（含首尾），后端同样限制 62 天。
export const MAX_BATCH_SPAN = 62

// 票价下限，票价不能为负（§8.6 VALIDATION_ERROR：票价不能为负）。
export const MIN_PRICE = 0

const DATETIME_RE = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?$/
const DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/
const TIME_RE = /^(\d{2}):(\d{2})(?::(\d{2}))?$/

function pad(n) {
    return String(n).padStart(2, '0')
}

// Date → 'YYYY-MM-DDTHH:mm:ss'（本地时间，不带时区后缀）。
// 后端 departTime/arriveTime 就是这个格式，前端要原样回传给后端。
export function formatLocal(date) {
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
        `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

// 'YYYY-MM-DD' 格式化
export function formatDate(date) {
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

// 解析本地时间字符串；非法返回 null。
// 不用 new Date(str)：'2026-11-26T08:00:00' 会被当成 UTC，本地东八区就偏了 8 小时。
export function parseLocalDateTime(value) {
    if (typeof value !== 'string') return null
    const m = DATETIME_RE.exec(value.trim())
    if (!m) return null
    const [, y, mo, d, hh, mi, ss] = m
    const date = new Date(Number(y), Number(mo) - 1, Number(d), Number(hh), Number(mi), Number(ss || 0))
    // 反查一遍，挡掉 2026-02-30 这类「格式对但日期不存在」的输入
    if (date.getFullYear() !== Number(y) || date.getMonth() !== Number(mo) - 1 ||
        date.getDate() !== Number(d) || date.getHours() !== Number(hh) ||
        date.getMinutes() !== Number(mi)) {
        return null
    }
    return date
}

// 解析 'YYYY-MM-DD'；非法返回 null
export function parseLocalDate(value) {
    if (typeof value !== 'string') return null
    const m = DATE_RE.exec(value.trim())
    if (!m) return null
    const [, y, mo, d] = m
    const date = new Date(Number(y), Number(mo) - 1, Number(d))
    if (date.getFullYear() !== Number(y) || date.getMonth() !== Number(mo) - 1 ||
        date.getDate() !== Number(d)) {
        return null
    }
    return date
}

// 一天里的第几分钟
function minutesOfDay(date) {
    return date.getHours() * 60 + date.getMinutes()
}

/**
 * 按 §8.4.3 计算到达时间：到达 = 发船 + 航程时长；
 * 若算出的到达时间不晚于发船时间（正好相等也算），则加 1 天。
 * 返回形如 '2026-11-26T10:00:00' 的本地 ISO 字符串（不带时区）。
 */
export function arriveAt(departTime, durationMin) {
    const depart = parseLocalDateTime(departTime)
    if (!depart) return ''
    const duration = Number(durationMin)
    if (!Number.isFinite(duration) || duration < 0) return ''

    // 先按同一天的时钟推进：跨零点的部分先折回当天，再判断要不要 +1 天。
    const rawMinutes = minutesOfDay(depart) + Math.round(duration)
    const clockMinutes = ((rawMinutes % 1440) + 1440) % 1440
    const arrive = new Date(
        depart.getFullYear(),
        depart.getMonth(),
        depart.getDate(),
        Math.floor(clockMinutes / 60),
        clockMinutes % 60,
        depart.getSeconds()
    )
    // 到达不晚于发船 → 跨零点，落到次日。
    if (arrive.getTime() <= depart.getTime()) {
        arrive.setDate(arrive.getDate() + 1)
    }
    return formatLocal(arrive)
}

/**
 * 实际到达时刻（带日期）。
 * 后端 §8.4.3 已经把跨零点班次的 arriveTime 推到次日；而 §8.4.1 又说
 * 「arriveTime 早于 departTime 表示跨零点」，两种口径都可能出现。
 * 这里统一归一化：到达日期已经比发船日期晚 → 直接用；
 * 否则按「不晚于发船就 +1 天」补一天。两种口径下结果一致。
 */
export function effectiveArrive(departTime, arriveTime) {
    const depart = parseLocalDateTime(departTime)
    const arrive = parseLocalDateTime(arriveTime)
    if (!depart) return arriveTime || ''
    if (!arrive) return arriveTime || ''
    const sameDay = arrive.getFullYear() === depart.getFullYear() &&
        arrive.getMonth() === depart.getMonth() && arrive.getDate() === depart.getDate()
    if (sameDay && arrive.getTime() <= depart.getTime()) {
        const next = new Date(arrive.getTime())
        next.setDate(next.getDate() + 1)
        return formatLocal(next)
    }
    return formatLocal(arrive)
}

/**
 * 是否跨零点（到达落在次日）。
 * 判据：归一化后的到达日期晚于发船日期。
 */
export function crossesMidnight(departTime, arriveTime) {
    const depart = parseLocalDateTime(departTime)
    const arrive = parseLocalDateTime(effectiveArrive(departTime, arriveTime))
    if (!depart || !arrive) return false
    const dayOf = (d) => Math.floor(new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime() / 86400000)
    return dayOf(arrive) > dayOf(depart)
}

/**
 * 展开日期区间（含首尾）：'2026-12-01' ~ '2026-12-03' → 3 个日期。
 * 任何一端非法或起始晚于结束都返回空数组。
 */
export function expandDates(dateFrom, dateTo) {
    const from = parseLocalDate(dateFrom)
    const to = parseLocalDate(dateTo)
    if (!from || !to || from.getTime() > to.getTime()) return []
    const out = []
    const cur = new Date(from.getTime())
    while (cur.getTime() <= to.getTime()) {
        out.push(formatDate(cur))
        cur.setDate(cur.getDate() + 1)
    }
    return out
}

/**
 * 批量生成的日期区间校验（§8.4.8）：
 * dateFrom 不能晚于 dateTo，跨度最多 62 天（含）。
 */
export function validateDateRange(dateFrom, dateTo) {
    const from = parseLocalDate(dateFrom)
    const to = parseLocalDate(dateTo)
    if (!from || !to) {
        return { ok: false, message: '日期范围不合法' }
    }
    if (from.getTime() > to.getTime()) {
        return { ok: false, message: '日期范围不合法' }
    }
    const days = expandDates(dateFrom, dateTo).length
    if (days > MAX_BATCH_SPAN) {
        return { ok: false, message: `批量生成最多 ${MAX_BATCH_SPAN} 天` }
    }
    return { ok: true, message: '' }
}

/**
 * 班次表单校验（新增/编辑单个班次，以及批量生成对话框复用同一套字段校验）。
 * 入参：{ routeId, shipId, departTime, price }
 *  可选入参 dateFrom/dateTo —— 批量生成时带上，会额外校验日期区间。
 *  可选入参 ignorePast —— 编辑班次且发船时间没改动时为 true：
 *    §8.4.4 只在「改发船时间」时才拦截过去时间，原样提交不该被前端挡下。
 *  可选第二参 now —— 默认取当前时间，单测里可注入固定值。
 * 返回 { ok, message }，message 为空字符串表示通过。
 */
export function validateScheduleForm(form = {}, now = new Date()) {
    const { routeId, shipId, departTime, price } = form

    if (routeId === null || routeId === undefined || routeId === '') {
        return { ok: false, message: '请选择航线' }
    }
    if (shipId === null || shipId === undefined || shipId === '') {
        return { ok: false, message: '请选择船舶' }
    }
    if (!departTime) {
        return { ok: false, message: '请选择发船时间' }
    }
    const depart = parseLocalDateTime(departTime)
    if (!depart) {
        return { ok: false, message: '发船时间格式不正确' }
    }
    if (!form.ignorePast && depart.getTime() < now.getTime()) {
        return { ok: false, message: '发船时间不能早于当前时间' }
    }
    if (price === null || price === undefined || price === '') {
        return { ok: false, message: '请填写票价' }
    }
    const priceNum = Number(price)
    if (!Number.isFinite(priceNum)) {
        return { ok: false, message: '票价格式不正确' }
    }
    if (priceNum < MIN_PRICE) {
        return { ok: false, message: '票价不能为负' }
    }

    // 批量生成时才需要日期区间
    if (form.dateFrom || form.dateTo) {
        const range = validateDateRange(form.dateFrom, form.dateTo)
        if (!range.ok) return range
    }
    return { ok: true, message: '' }
}

/**
 * 批量生成表单的完整校验：日期区间 + 每天发船时刻 + 票价。
 * departTime 为 'HH:mm:ss'（每天的发船时刻）。
 */
export function validateBatchForm(form = {}) {
    const range = validateDateRange(form.dateFrom, form.dateTo)
    if (!range.ok) return range
    if (!form.departTime || !TIME_RE.test(String(form.departTime).trim())) {
        return { ok: false, message: '发船时刻格式不正确' }
    }
    if (form.routeId === null || form.routeId === undefined || form.routeId === '') {
        return { ok: false, message: '请选择航线' }
    }
    if (form.shipId === null || form.shipId === undefined || form.shipId === '') {
        return { ok: false, message: '请选择船舶' }
    }
    if (form.price === null || form.price === undefined || form.price === '') {
        return { ok: false, message: '请填写票价' }
    }
    const priceNum = Number(form.price)
    if (!Number.isFinite(priceNum)) {
        return { ok: false, message: '票价格式不正确' }
    }
    if (priceNum < MIN_PRICE) {
        return { ok: false, message: '票价不能为负' }
    }
    return { ok: true, message: '' }
}

/**
 * 把 'HH:mm:ss' 拼成 'YYYY-MM-DDTHH:mm:ss'，供批量生成的每日班次预览。
 */
export function composeDepartTime(date, time) {
    const d = parseLocalDate(date)
    const m = TIME_RE.exec(String(time || '').trim())
    if (!d || !m) return ''
    const [, hh, mi, ss] = m
    return formatLocal(new Date(
        d.getFullYear(), d.getMonth(), d.getDate(), Number(hh), Number(mi), Number(ss || 0)
    ))
}
