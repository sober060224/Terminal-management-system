// M3 真实浏览器验收：乘客端退票 / 改签两个入口在界面上点得通，改签候选不是空的。
// 跑法：cd frontend && node e2e/m3-browser.mjs
import { chromium } from 'playwright'
import fs from 'node:fs'

const BASE = process.env.FERRY_BASE || 'http://localhost:8089'
const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const SHOTS = 'D:/AAA_Software/Code/Code/temporary/dbpractice-m3/shots'
fs.mkdirSync(SHOTS, { recursive: true })

let failed = 0
function check(label, actual, expected) {
    const ok = JSON.stringify(actual) === JSON.stringify(expected)
    console.log(`${ok ? 'PASS' : 'FAIL'} ${label}  (got ${JSON.stringify(actual)}, want ${JSON.stringify(expected)})`)
    if (!ok) failed += 1
}

const browser = await chromium.launch({ executablePath: CHROME, headless: true })
const context = await browser.newContext({ viewport: { width: 1400, height: 900 } })
const page = await context.newPage()
const calls = []
const errors = []
page.on('response', (r) => calls.push(`${r.request().method()} ${new URL(r.url()).pathname} -> ${r.status()}`))
page.on('pageerror', (e) => errors.push(String(e)))
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })

async function login(username, password) {
    await page.goto(`${BASE}/#/login`)
    await page.getByPlaceholder('用户名').fill(username)
    await page.getByPlaceholder('密码').fill(password)
    await page.getByRole('button', { name: '登录' }).click()
    await page.waitForTimeout(1200)
}

// ---------- 乘客端 ----------
await login('passenger1', '123456')
check('乘客落点 /#/', await page.evaluate(() => location.hash), '#/')

await page.goto(`${BASE}/#/my-orders`)
await page.getByRole('button', { name: '退票' }).first().waitFor({ timeout: 15000 })
check('我的船票列表加载出已支付票', (await page.locator('.order').count()) > 0, true)
await page.screenshot({ path: `${SHOTS}/m3-01-my-orders.png` })

// 改签候选：点第一行的「改签」，对话框里的班次下拉必须至少有一个选项
await page.getByRole('button', { name: '改签' }).first().click()
await page.waitForSelector('.el-dialog', { timeout: 5000 })
check('改签对话框打开', await page.locator('.el-dialog__title').innerText(), '改签船票')
await page.locator('.el-dialog .el-select').click()
await page.waitForTimeout(600)
const options = await page.locator('.el-select-dropdown__item:visible').count()
check('改签候选班次不为空（修掉 routeId 缺失）', options > 0, true)
await page.screenshot({ path: `${SHOTS}/m3-02-rebook-candidates.png` })
await page.keyboard.press('Escape')
await page.locator('.el-dialog__headerbtn').click().catch(() => {})
await page.waitForTimeout(400)

// 退票：点第一行的「退票」→ 二次确认 → 请求真的打到 /api/tickets/{id}/refund
calls.length = 0
await page.getByRole('button', { name: '退票' }).first().click()
await page.waitForSelector('.el-message-box', { timeout: 5000 })
await page.screenshot({ path: `${SHOTS}/m3-03-refund-confirm.png` })
// Element Plus 默认英文 locale，确认框按钮是 OK / Cancel
await page.locator('.el-message-box').getByRole('button', { name: 'OK' }).click()
await page.waitForTimeout(1500)
const refundCall = calls.find((c) => /POST \/api\/tickets\/\d+\/refund/.test(c))
check('退票请求打到 /api/tickets/{id}/refund（修掉 api 缺方法）', Boolean(refundCall), true)
const messageText = await page.locator('.el-message').innerText().catch(() => '')
check('界面提示不是 undefined 错误', !/undefined|not a function/.test(messageText), true)
await page.screenshot({ path: `${SHOTS}/m3-04-after-refund.png` })

// ---------- 售票员端 ----------
await context.clearCookies()
await login('admin', 'admin123')
check('管理员登录后落点 /#/admin', await page.evaluate(() => location.hash), '#/admin')
await page.goto(`${BASE}/#/seller/orders`)
await page.getByRole('button', { name: '改签' }).first().waitFor({ timeout: 15000 })
await page.getByRole('button', { name: '改签' }).first().click()
await page.waitForSelector('.el-dialog', { timeout: 5000 })
await page.locator('.el-dialog .el-select').click()
await page.waitForTimeout(600)
const sellerOptions = await page.locator('.el-select-dropdown__item:visible').count()
check('代售订单页改签候选也不为空', sellerOptions > 0, true)
await page.screenshot({ path: `${SHOTS}/m3-05-seller-rebook.png` })

check('页面没有 JS 报错', errors.filter((e) => !/favicon/.test(e)), [])

await browser.close()
console.log(failed === 0 ? '\n全部通过' : `\n失败 ${failed} 项`)
process.exit(failed === 0 ? 0 : 1)