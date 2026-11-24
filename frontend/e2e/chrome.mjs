import fs from 'node:fs'

// 本机 Chrome 查找：FERRY_CHROME / CHROME_PATH 优先，其次几个常见安装位置。
// 都找不到返回 undefined，浏览器启动时回落到 Playwright 自带的 Chromium。
const CANDIDATES = [
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
    process.env.LOCALAPPDATA && `${process.env.LOCALAPPDATA}/Google/Chrome/Application/chrome.exe`,
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/usr/bin/google-chrome',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser'
]

export function chromePath() {
    const explicit = process.env.FERRY_CHROME || process.env.CHROME_PATH
    if (explicit) return explicit
    return CANDIDATES.find((p) => p && fs.existsSync(p))
}
