import { describe, it, expect, beforeEach, vi } from 'vitest'
import request from './request'

// 拦截器行为只有通过真实发请求才看得见：用假 adapter 记录请求顺序，
// 不碰网络。
describe('CSRF 令牌拦截器', () => {
    let calls
    let csrfToken

    beforeEach(() => {
        calls = []
        csrfToken = 'token-' + Math.random().toString(36).slice(2)
        request.defaults.adapter = async (config) => {
            calls.push(`${config.method} ${config.url}`)
            return {
                data: { success: true, message: '操作成功', data: { token: csrfToken, ready: true } },
                status: 200,
                statusText: 'OK',
                headers: {},
                config
            }
        }
        clearCookies()
        vi.restoreAllMocks()
    })

    function clearCookies() {
        document.cookie.split(';').forEach((c) => {
            const name = c.split('=')[0].trim()
            if (name) document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`
        })
    }

    function setCsrfCookie() {
        document.cookie = `XSRF-TOKEN=${csrfToken}; path=/`
    }

    it('没有 XSRF-TOKEN Cookie 时，写操作前先取一次令牌', async () => {
        await request.post('/auth/login', {})
        expect(calls).toEqual(['get /auth/csrf', 'post /auth/login'])
    })

    it('已有 Cookie 时不重复取令牌', async () => {
        setCsrfCookie()
        await request.post('/auth/login', {})
        await request.post('/auth/logout', {})
        expect(calls).toEqual(['post /auth/login', 'post /auth/logout'])
    })

    it('登出删掉 Cookie 后再登录，必须重新取令牌', async () => {
        setCsrfCookie()
        await request.post('/auth/login', {})
        // 后端登出会 deleteCookies(XSRF-TOKEN)
        clearCookies()
        await request.post('/auth/login', {})
        // 关键断言：第二次不能再复用上一次已完成的 token 请求
        expect(calls).toEqual(['post /auth/login', 'get /auth/csrf', 'post /auth/login'])
    })

    it('并发的写操作只取一次令牌', async () => {
        await Promise.all([request.post('/a', {}), request.post('/b', {})])
        expect(calls.filter((c) => c === 'get /auth/csrf')).toHaveLength(1)
    })

    it('GET 请求不触发令牌获取', async () => {
        await request.get('/auth/current')
        expect(calls).toEqual(['get /auth/current'])
    })
})