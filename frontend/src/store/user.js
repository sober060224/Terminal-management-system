import { defineStore } from 'pinia'
import { authApi } from '../api'

const ROLE_NAMES = {
    ADMIN: '系统管理员',
    SELLER: '售票员',
    PASSENGER: '乘客'
}

export const useUserStore = defineStore('user', {
    state: () => ({
        profile: null,
        loaded: false,
        // 身份已确认过（含「确认是未登录」）。
        // 只用 loaded 记录「拿到过 profile」时，匿名会话永远 loaded=false，
        // 每次路由守卫都会再发一次 /auth/current。
        checked: false
    }),

    getters: {
        isLogin: (state) => !!state.profile,
        role: (state) => (state.profile ? state.profile.role : null),
        roleName: (state) => (state.profile ? (ROLE_NAMES[state.profile.role] || state.profile.role) : '访客'),
        isAdmin: (state) => !!state.profile && state.profile.role === 'ADMIN',
        isSeller: (state) => !!state.profile && state.profile.role === 'SELLER',
        isPassenger: (state) => !!state.profile && state.profile.role === 'PASSENGER'
    },

    actions: {
        // 刷新页面后先用会话向服务端确认身份；只有 401 才是确定未登录
        async load() {
            if (this.loaded || this.checked) {
                return this.profile
            }
            try {
                this.profile = (await authApi.current()).data
                this.loaded = true
            } catch (e) {
                // 只有 401 是「确定未登录」，这个否定结果可以缓存（checked）；
                // 网络抖动/后端 5xx 不能当成未登录，否则一次瞬断就把用户踢到登录页。
                this.profile = null
                this.loaded = false
                this.checked = !!e && e.status === 401
            }
            return this.profile
        },

        async login(form) {
            try {
                this.profile = (await authApi.login(form)).data
                this.loaded = true
                this.checked = true
                return this.profile
            } catch (e) {
                // 登录失败必须清掉旧快照：上一个账号的 profile 留在 state 里时，
                // isLogin 仍为 true，接口却已是未登录，store 与真实会话不一致。
                this.clear()
                throw e
            }
        },

        async logout() {
            try {
                await authApi.logout()
            } finally {
                this.clear()
            }
        },

        // loaded 必须置回 false：一旦置 true，load() 就会一直空转，
        // 之后所有路由守卫都拿不到 profile，只能整页刷新才能恢复。
        clear() {
            this.profile = null
            this.loaded = false
            // 到 clear 就意味着「确定未登录」（退出/401/登录失败），不必再发 /auth/current
            this.checked = true
        }
    }
})

export { ROLE_NAMES }
