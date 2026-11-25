/**
 * M2 真实浏览器验收：管理员依次进入 /admin/routes、/admin/ships、/admin/schedules。
 *
 * 断言：
 *   - 三个页面的文档标题与 .page-title 都含对应关键词（航线 / 船舶 / 班次）
 *   - 表格已渲染且有数据行（database.sql 有种子数据）
 *   - 全程无未捕获 JS 报错、无 HTTP >= 400 响应（favicon 除外）
 *   - 可选：在班次页尝试打开「新建班次」对话框并尽力填票价（失败不阻塞）
 *
 * 用法：cd frontend && node e2e/m2-browser.mjs
 * 环境变量：FERRY_BASE（默认 http://localhost:8089）、FERRY_CHROME（Chrome 路径）、
 *           FERRY_ARTIFACTS（截图目录，默认 temporary/dbpractice-m2/shots）。
 */
import { chromium } from 'playwright'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromePath } from './chrome.mjs'

const BASE = process.env.FERRY_BASE || 'http://localhost:8089'
const SHOTS = process.env.FERRY_ARTIFACTS
    ? path.join(process.env.FERRY_ARTIFACTS, 'shots')
    : fileURLToPath(new URL('../../temporary/dbpractice-m2/shots', import.meta.url))
fs.mkdirSync(SHOTS, { recursive: true })

const failures = []

function check(label, actual, expected) {
    const ok = JSON.stringify(actual) === JSON.stringify(expected)
    console.log(`${ok ? '  PASS ' : '  FAIL '}${label}  (got ${JSON.stringify(actual)}, want ${JSON.stringify(expected)})`)
    if (!ok) failures.push(label)
}

const browser = await chromium.launch({ headless: true, executablePath: chromePath() })
const context = await browser.newContext({ viewport: { width: 1400, height: 900 } })
const page = await context.newPage()

// 收集页面自身的报错：JS 异常 + console error + 4xx/5xx 响应（favicon 不算）。
const errors = []
page.on('pageerror', (e) => errors.push(String(e)))
page.on('console', (m) => {
    if (m.type() === 'error' && !/favicon/.test(m.text())) errors.push(m.text())
})
page.on('response', (r) => {
    if (r.status() >= 400 && !r.url().includes('favicon')) {
        errors.push(`HTTP ${r.status()} ${r.url()}`)
    }
})

console.log(`=== M2 真实浏览器验收 base=${BASE} ===`)

// 管理员登录（走 CSRF：前端首屏会先取令牌）
await page.goto(`${BASE}/#/login`, { waitUntil: 'networkidle' })
await page.getByPlaceholder('用户名').fill('admin')
await page.getByPlaceholder('密码').fill('admin123')
await page.getByRole('button', { name: '登录' }).click()
await page.waitForFunction(() => window.location.hash === '#/admin', null, { timeout: 10000 })
    .catch(() => {})
check('管理员登录后落 /admin', await page.evaluate(() => window.location.hash), '#/admin')

const pages = [
    { hash: '#/admin/routes', keyword: '航线', label: '航线管理', shot: 'm2-01-routes.png' },
    { hash: '#/admin/ships', keyword: '船舶', label: '船舶管理', shot: 'm2-02-ships.png' },
    { hash: '#/admin/schedules', keyword: '班次', label: '班次管理', shot: 'm2-03-schedules.png' }
]

for (const p of pages) {
    console.log(`\n[${p.label}] ${p.hash}`)
    await page.goto(`${BASE}/${p.hash}`, { waitUntil: 'networkidle' })
    await page.waitForTimeout(800)

    // 文档标题（路由 meta.title + 系统名）
    check(`${p.label} 文档标题含「${p.keyword}」`, (await page.title()).includes(p.keyword), true)
    // 页面正文标题：body 非空不算证据，必须有 .page-title
    const titleText = (await page.locator('.page-title').first().innerText().catch(() => '')).trim()
    check(`${p.label} 页面标题含「${p.keyword}」`, titleText.includes(p.keyword), true)
    // 表格渲染
    check(`${p.label} 表格已渲染`, (await page.locator('.el-table').count()) >= 1, true)
    check(`${p.label} 表格有数据行`, (await page.locator('.el-table__row').count()) >= 1, true)

    await page.screenshot({ path: path.join(SHOTS, p.shot), fullPage: true })
}

// 可选：在班次页尝试打开「新建班次」对话框并尽量填一条（失败不阻塞整体结果）
console.log('\n[可选] 班次页新建对话框')
try {
    await page.goto(`${BASE}/#/admin/schedules`, { waitUntil: 'networkidle' })
    await page.waitForTimeout(500)
    const addBtn = page.getByRole('button', { name: /新增班次|新建班次|新增|新建/ }).first()
    if (await addBtn.count()) {
        await addBtn.click()
        const dialog = page.locator('.el-dialog:visible').first()
        await dialog.waitFor({ timeout: 5000 })
        console.log('  INFO 新建班次对话框已打开')
        // 尽力填写：票价（el-input-number）+ 发船时间（datetime 选择器的输入框），仅作可选
        const priceInput = dialog.locator('.el-input-number input, input[placeholder*="票价"]').first()
        if (await priceInput.count()) await priceInput.fill('150').catch(() => {})
        const timeInput = dialog.locator('input[placeholder="选择发船时间"], input[placeholder*="发船"]').first()
        if (await timeInput.count()) {
            const tomorrow = new Date(Date.now() + 24 * 3600 * 1000)
            const pad = (n) => String(n).padStart(2, '0')
            const text = `${tomorrow.getFullYear()}-${pad(tomorrow.getMonth() + 1)}-${pad(tomorrow.getDate())} 08:00:00`
            await timeInput.fill(text).catch(() => {})
        }
        await page.screenshot({ path: path.join(SHOTS, 'm2-04-schedule-dialog.png'), fullPage: true })
        // 关闭对话框，避免影响后续（不提交，避免制造脏数据）
        const cancel = dialog.getByRole('button', { name: /取消|关闭/ }).first()
        if (await cancel.count()) await cancel.click()
    } else {
        console.log('  INFO 未找到新增按钮，跳过可选对话框检查')
    }
} catch (e) {
    console.log('  INFO 可选对话框检查跳过: ' + (e && e.message ? e.message : e))
}

check('浏览器无 JS 报错 / 无 >=400 响应', errors, [])

await browser.close()

console.log('\n=== 结果 ===')
if (failures.length) {
    console.log(`失败 ${failures.length} 项:`)
    failures.forEach((f) => console.log('  -', f))
    process.exit(1)
}
console.log('全部通过')
