import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useUserStore } from './user'
import { authApi } from '../api'

vi.mock('../api', () => ({
    authApi: {
        current: vi.fn(),
        login: vi.fn(),
        logout: vi.fn(),
        register: vi.fn(),
        csrf: vi.fn()
    }
}))

describe('user store', () => {
    beforeEach(() => {
        setActivePinia(createPinia())
        vi.clearAllMocks()
    })

    it('load() 会向服务端确认身份', async () => {
        authApi.current.mockResolvedValue({ id: 1, username: 'admin', role: 'ADMIN' })
        const store = useUserStore()
        await store.load()
        expect(store.isLogin).toBe(true)
        expect(store.isAdmin).toBe(true)
    })

    it('401 视为确定未登录，缓存否定结果', async () => {
        const err = new Error('请先登录')
        err.status = 401
        authApi.current.mockRejectedValue(err)
        const store = useUserStore()
        await store.load()
        expect(store.isLogin).toBe(false)
        expect(store.checked).toBe(true)
        // 第二次不应再请求
        await store.load()
        expect(authApi.current).toHaveBeenCalledTimes(1)
    })

    it('网络故障不当成未登录，还要再试一次', async () => {
        const err = new Error('网络异常')
        err.status = 500
        authApi.current.mockRejectedValue(err)
        const store = useUserStore()
        await store.load()
        expect(store.checked).toBe(false)
        authApi.current.mockResolvedValue({ id: 2, username: 'seller1', role: 'SELLER' })
        await store.load()
        expect(store.isLogin).toBe(true)
    })

    it('登录失败会清掉旧快照', async () => {
        const store = useUserStore()
        authApi.current.mockResolvedValue({ id: 1, username: 'admin', role: 'ADMIN' })
        await store.load()
        expect(store.isLogin).toBe(true)

        authApi.login.mockRejectedValue(new Error('用户名或密码错误'))
        await expect(store.login({ username: 'admin', password: 'x' })).rejects.toThrow()
        expect(store.isLogin).toBe(false)
    })

    it('clear() 让后续 load 不再空转', async () => {
        const store = useUserStore()
        authApi.current.mockResolvedValue({ id: 1, username: 'admin', role: 'ADMIN' })
        await store.load()
        store.clear()
        expect(store.loaded).toBe(false)
        expect(store.isLogin).toBe(false)
    })
})
