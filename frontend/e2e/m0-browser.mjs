/**
 * M0 真实浏览器验收：干净浏览器上下文里完成「登录页 → 角色首页」，并覆盖
 * 「退出登录 → 换另一个账号登录」这条正常用户路径（CSRF 令牌必须重新获取）。
 *
 * 用法：node frontend/e2e/m0-browser.mjs [baseUrl]
 */
import { chromium } from 'playwright'
import { chromePath } from './chrome.mjs'

const BASE = process.argv[2] || 'http://localhost:8089'

const cases = [
    { username: 'admin', password: 'admin123', expect: '#/admin', title: '管理后台', label: '管理员落 /admin' },
    { username: 'seller1', password: 'seller123', expect: '#/seller', title: '售票工作台', label: '售票员落 /seller' },
    { username: 'passenger1', password: '123456', expect: '#/', title: '查询班次', label: '乘客落 /' }
]

const failures = []

function check(label, actual, expected) {
    const ok = JSON.stringify(actual) === JSON.stringify(expected)
    console.log(`${ok ? '  PASS ' : '  FAIL '}${label}  (got ${JSON.stringify(actual)}, want ${JSON.stringify(expected)})`)
    if (!ok) failures.push(label)
}

// 用本机已装的 Chrome，找不到就用 Playwright 自带的 Chromium。
const browser = await chromium.launch({ executablePath: chromePath() })
console.log(`=== M0 真实浏览器验收 base=${BASE} ===`)

/** 收集页面自身的报错：JS 异常 + 4xx/5xx 资源（favicon 不算）。 */
function watch(page) {
    const errors = []
    page.on('pageerror', (e) => errors.push(String(e)))
    page.on('console', (m) => {
        if (m.type() === 'error' && !m.text().includes('favicon')) errors.push(m.text())
    })
    page.on('response', (r) => {
        if (r.status() >= 400 && !r.url().includes('favicon')) {
            errors.push(`HTTP ${r.status()} ${r.url()}`)
        }
    })
    return errors
}

async function login(page, c) {
    await page.getByPlaceholder('用户名').fill(c.username)
    await page.getByPlaceholder('密码').fill(c.password)
    await page.getByRole('button', { name: '登录' }).click()
    await page.waitForFunction((h) => window.location.hash === h, c.expect, { timeout: 10000 }).catch(() => {})
}

async function logout(page) {
    await page.locator('.user').click()
    await page.getByText('退出登录').click()
    await page.waitForFunction(() => window.location.hash.startsWith('#/login'), null, { timeout: 10000 })
}

for (const c of cases) {
    // 每个角色一个全新 context：没有 Cookie，没有历史
    const context = await browser.newContext()
    const page = await context.newPage()
    const errors = watch(page)

    console.log(`\n[${c.label}] ${c.username}`)
    await page.goto(`${BASE}/#/login`, { waitUntil: 'networkidle' })
    check('登录页标题', (await page.title()).includes('登录'), true)

    await login(page, c)
    check('跳转到角色首页', await page.evaluate(() => window.location.hash), c.expect)
    // 首页正文里必须有该端自己的标题，body 非空不算证据
    check('角色首页内容渲染', (await page.locator('.page-title').first().innerText()).trim(), c.title)

    check('浏览器无 JS 报错', errors, [])

    await context.close()
}

console.log('\n[退出登录 → 换账号登录（CSRF 令牌必须重新获取）]')
{
    const context = await browser.newContext()
    const page = await context.newPage()
    const errors = watch(page)

    await page.goto(`${BASE}/#/login`, { waitUntil: 'networkidle' })
    await login(page, cases[0]) // admin
    check('先以管理员登录', await page.evaluate(() => window.location.hash), '#/admin')

    await logout(page)
    check('退出后回到登录页', await page.evaluate(() => window.location.hash), '#/login')

    // 后端登出会 deleteCookies(XSRF-TOKEN)；前端必须重新取一次令牌，否则这里 403
    await login(page, cases[1]) // seller1
    check('同一个 SPA 里换账号登录成功', await page.evaluate(() => window.location.hash), '#/seller')
    check('第二次登录没有 403', errors.filter((e) => e.includes('403')), [])

    await context.close()
}

console.log('\n[未登录访问受保护页面]')
{
    const context = await browser.newContext()
    const page = await context.newPage()
    await page.goto(`${BASE}/#/admin`, { waitUntil: 'networkidle' })
    await page.waitForTimeout(1500)
    const hash = await page.evaluate(() => window.location.hash)
    check('被踢回登录页', hash.startsWith('#/login'), true)
    check('带上 redirect 参数', hash.includes('redirect'), true)
    await context.close()
}

await browser.close()

console.log('\n=== 结果 ===')
if (failures.length) {
    console.log(`失败 ${failures.length} 项:`)
    failures.forEach((f) => console.log('  -', f))
    process.exit(1)
}
console.log('全部通过')