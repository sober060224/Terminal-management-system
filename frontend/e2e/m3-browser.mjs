// M3 真实浏览器验收：用 m3_fixture.py 造的新订单，走乘客端退票 / 改签真实提交。
// 跑法：cd frontend && node e2e/m3-browser.mjs
// 需 FERRY_FIXTURE 指向 fixture JSON；Chrome 用 FERRY_CHROME，未设置用 Playwright 自带 Chromium。
import { chromium } from 'playwright'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromePath } from './chrome.mjs'

const BASE = process.env.FERRY_BASE || 'http://localhost:8089'
// 截图目录：FERRY_ARTIFACTS（验收脚本会设），否则用仓库内的 temporary/dbpractice-m3/shots
const SHOTS = process.env.FERRY_ARTIFACTS
    ? path.join(process.env.FERRY_ARTIFACTS, 'shots')
    : fileURLToPath(new URL('../../temporary/dbpractice-m3/shots', import.meta.url))
fs.mkdirSync(SHOTS, { recursive: true })

const fixturePath = process.env.FERRY_FIXTURE
if (!fixturePath) {
    console.error('缺少 FERRY_FIXTURE 环境变量，先运行 verify/verify-m3-browser.cmd')
    process.exit(1)
}
const fixture = JSON.parse(fs.readFileSync(fixturePath, 'utf8'))

let failed = 0
function check(label, actual, expected) {
    const ok = JSON.stringify(actual) === JSON.stringify(expected)
    console.log(`${ok ? 'PASS' : 'FAIL'} ${label}  (got ${JSON.stringify(actual)}, want ${JSON.stringify(expected)})`)
    if (!ok) failed += 1
}

const launchOptions = { headless: true }
launchOptions.executablePath = chromePath()
const browser = await chromium.launch(launchOptions)
const context = await browser.newContext({ viewport: { width: 1400, height: 900 } })
const page = await context.newPage()
const errors = []
page.on('pageerror', (e) => errors.push(String(e)))
page.on('console', (m) => { if (m.type() === 'error' && !/favicon/.test(m.text())) errors.push(m.text()) })

const isRequest = (method, re) => (response) =>
    response.request().method() === method && re.test(new URL(response.url()).pathname)

async function login(username, password) {
    await page.goto(`${BASE}/#/login`)
    await page.getByPlaceholder('用户名').fill(username)
    await page.getByPlaceholder('密码').fill(password)
    await page.getByRole('button', { name: '登录' }).click()
}

// ① 乘客登录后落点 #/
await login(fixture.username, fixture.password)
await page.waitForFunction(() => location.hash === '#/', null, { timeout: 10000 })
check('乘客落点 /#/', await page.evaluate(() => location.hash), '#/')

// ② 我的船票能加载出 fixture 那笔已支付订单
await page.goto(`${BASE}/#/my-orders`)
await page.getByRole('button', { name: '退票' }).first().waitFor({ timeout: 15000 })
const myOrders = await page.locator('.order').allInnerTexts()
check('我的船票含 fixture 订单', myOrders.some((text) => text.includes(String(fixture.orderId))), true)
await page.screenshot({ path: `${SHOTS}/m3-01-my-orders.png` })

// ③ 退票：选一个预设原因 → 确认 → 断言 HTTP 200 + 页面成功提示/票状态已退票
await page.getByRole('button', { name: '退票' }).first().click()
const refundDialog = page.locator('.el-dialog').filter({ hasText: '确认退掉' })
await refundDialog.waitFor({ timeout: 5000 })
await refundDialog.locator('.el-select').click()
await page.locator('.el-select-dropdown__item:visible', { hasText: '误购' }).first().click()
await page.screenshot({ path: `${SHOTS}/m3-02-refund-confirm.png` })
const [refundResponse] = await Promise.all([
    page.waitForResponse(isRequest('POST', /^\/api\/tickets\/\d+\/refund$/)),
    refundDialog.getByRole('button', { name: '确认退票' }).click(),
])
check('退票响应 200', refundResponse.status(), 200)
// 成功提示 3 秒就消失，退票后列表要等 load() 刷新；直接轮询票状态变化更稳
let refunded = false
try {
    await page.waitForFunction(() => document.body.innerText.includes('已退票'), null, { timeout: 8000 })
    refunded = true
} catch { refunded = false }
check('退票后票状态变为已退票', refunded, true)
await page.screenshot({ path: `${SHOTS}/m3-03-after-refund.png` })

// ④ 改签：打开对话框 → 选目标班次 → 选一个可选座位 → 确认 → 断言 HTTP 200
await page.getByRole('button', { name: '改签' }).first().click()
const rebookDialog = page.locator('.el-dialog').filter({ hasText: '改签船票' })
await rebookDialog.waitFor({ timeout: 5000 })
check('改签对话框打开', await rebookDialog.locator('.el-dialog__title').innerText(), '改签船票')
// 目标班次：按 fixture 的发船时间匹配选项（下拉里没有班次 id），否则第一个
await rebookDialog.locator('.el-select').first().click()
await page.waitForTimeout(500)
const scheduleOptions = page.locator('.el-select-dropdown__item:visible')
// P3-3 回归：候选必须不含 1 小时内发船的班次——fixture 原班次发船都在 1 小时外，
// 界面若漏过滤会多出其它早班次；断言候选数不超过同航线班次数（fixture 只造了两个）
const optionTexts = await scheduleOptions.allInnerTexts()
check('改签候选非空', optionTexts.length > 0, true)
check('改签候选不含 1 小时内的班次', optionTexts.every((s) => new Date(s.match(/\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}/)[0]).getTime() > Date.now() + 60 * 60 * 1000), true)
const targetOption = scheduleOptions.filter({ hasText: String(fixture.targetDepartTime) })
if (await targetOption.count()) await targetOption.first().click()
else await scheduleOptions.first().click()
await page.waitForTimeout(500)
// 座位：点座位图里第一个「可选」
const seatButton = rebookDialog.locator('.seat.s-可选').first()
if (await seatButton.count()) {
    await seatButton.click()
}
await page.screenshot({ path: `${SHOTS}/m3-04-rebook-dialog.png` })
const [rebookResponse, rebookRequest] = await Promise.all([
    page.waitForResponse(isRequest('POST', /^\/api\/tickets\/\d+\/rebook$/)),
    page.waitForRequest((r) => r.method() === 'POST' && /^\/api\/tickets\/\d+\/rebook$/.test(new URL(r.url()).pathname)),
    rebookDialog.getByRole('button', { name: '确认改签' }).click(),
])
const rebookPayload = JSON.parse(rebookRequest.postData() || '{}')
check('改签响应 200', rebookResponse.status(), 200)
if (rebookResponse.status() !== 200) {
    console.log('改签失败响应:', await rebookResponse.text())
}
await page.screenshot({ path: `${SHOTS}/m3-05-after-rebook.png` })

// ④b 订单详情页：改签过的票按各自班次显示发船时间（P1-3 回归）
await page.getByRole('button', { name: '详情' }).first().click()
await page.waitForFunction(() => /#\/orders\/\d+/.test(location.hash), null, { timeout: 8000 })
const detailFirstTicket = page.locator('.el-table__row').first()
await detailFirstTicket.waitFor({ timeout: 8000 })
const detailRows = await page.locator('.el-table__row').allInnerTexts()
// 改签时浏览器可能选了另一个目标班次，所以用请求里真正用的那个班次来断言
const usedSchedule = await page.evaluate(async (sid) => {
    const res = await fetch('/api/schedules/' + sid)
    const body = await res.json()
    return body && body.data ? body.data.departTime : null
}, rebookPayload.targetScheduleId)
const digits = (s) => String(s).replace(/[^0-9]/g, '')
const wantDepart = digits(usedSchedule || '').slice(0, 12)
// 详情里改签后的那张票必须显示目标班次的发船时间（票级班次，不是订单级）
check('详情票级发船时间 = 改签目标班次', wantDepart.length > 0 && detailRows.some((row) => digits(row).includes(wantDepart)), true)
await page.screenshot({ path: `${SHOTS}/m3-06-order-detail.png` })
await page.goBack()

// ⑤ 无未捕获 JS 错误
check('页面没有 JS 报错', errors, [])

await browser.close()
console.log(failed === 0 ? '\n全部通过' : `\n失败 ${failed} 项`)
process.exit(failed === 0 ? 0 : 1)
