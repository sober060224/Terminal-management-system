import { describe, it, expect } from 'vitest'
import { checkAccess, LANDING } from './index'

// 直接测 router/index.js 里真正被 beforeEach 调用的那个纯函数，
// 不用复制一份逻辑进来（复制品改起来会和真身漂移）。
function to(fullPath, meta = {}) {
    return { fullPath, meta }
}

function store(role) {
    return { isLogin: !!role, role }
}

describe('checkAccess', () => {
    it('未登录跳登录页并带 redirect', () => {
        const r = checkAccess(to('/admin'), store(null))
        expect(r).toEqual({ path: '/login', query: { redirect: '/admin' } })
    })

    it('管理员可以进 admin', () => {
        expect(checkAccess(to('/admin', { roles: ['ADMIN'] }), store('ADMIN'))).toBe(true)
    })

    it('售票员不能进 admin，被弹回自己的首页', () => {
        const r = checkAccess(to('/admin', { roles: ['ADMIN'] }), store('SELLER'))
        expect(r.path).toBe(LANDING.SELLER)
    })

    it('乘客不能进 seller', () => {
        const r = checkAccess(to('/seller', { roles: ['SELLER', 'ADMIN'] }), store('PASSENGER'))
        expect(r.path).toBe(LANDING.PASSENGER)
    })

    it('管理员也能进 seller 和 passenger', () => {
        expect(checkAccess(to('/seller', { roles: ['SELLER', 'ADMIN'] }), store('ADMIN'))).toBe(true)
        expect(checkAccess(to('/', { roles: ['PASSENGER', 'ADMIN'] }), store('ADMIN'))).toBe(true)
    })

    it('没有 roles 限制的页面，任何已登录角色都能进', () => {
        expect(checkAccess(to('/'), store('PASSENGER'))).toBe(true)
    })

    it('乘客的落点是根路径 /', () => {
        expect(LANDING.PASSENGER).toBe('/')
    })
})
