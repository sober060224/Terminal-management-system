import axios from 'axios'
import { useUserStore } from '../store/user'

// 所有请求走同源 /api：开发时由 Vite 代理到 8089，打包后由后端自己托管静态页面。
// withCredentials 保证 JSESSIONID 随请求带上；CSRF 令牌由 axios 自动
// 从 XSRF-TOKEN Cookie 读取并放进 X-XSRF-TOKEN 请求头，无需手写。
const request = axios.create({
    baseURL: '/api',
    withCredentials: true,
    timeout: 30000
})

// 多个请求同时 401 时，每个都会走到这里：clear() 和 location.href 会被反复执行，
// 后面的 401 还会在页面已经跳转时再触发一次导航。用一个标志位只跳一次。
let redirectingToLogin = false

request.interceptors.response.use(
    response => {
        const body = response.data
        // 后端统一响应体 { success, message, data }：这里只判断成功与否，
        // 整个响应体原样交给调用方。页面都是按 res.data 取业务数据的，
        // 在这里提前拆掉 data 会让所有列表读成 undefined、显示「暂无数据」。
        if (body && typeof body === 'object' && 'success' in body) {
            if (!body.success) {
                return Promise.reject(withStatus(new Error(body.message || '操作失败'), response.status))
            }
            return body
        }
        return body
    },
    error => {
        const status = error.response && error.response.status
        // 未登录（或会话过期）时后端返回 401，清空本地状态并跳回登录页。
        // 必须带上 redirect：否则用户在某个页面被踢下线后，登录成功只会回到首页，
        // 得自己重新找回去。LoginView 会读这个参数。
        if (status === 401) {
            useUserStore().clear()
            if (!redirectingToLogin && !window.location.hash.startsWith('#/login')) {
                redirectingToLogin = true
                // hash 模式下路由信息在 hash 里，pathname 永远是 '/'，
                // 只取 pathname 会把 redirect 变成 '/'，原页面就丢了。
                const back = encodeURIComponent(window.location.hash.replace(/^#/, '') || '/')
                window.location.href = `/#/login?redirect=${back}`
            }
        }
        if (error.code === 'ERR_CANCELED') {
            return Promise.reject(withStatus(error, status))
        }
        const data = error.response && error.response.data
        // 没有任何响应（断网、后端没起）时 error.message 是 axios 的英文原文，
        // 对用户无意义；这时直接给中文兜底。有响应时后端 message 优先。
        const message = !error.response
            ? '网络异常，请稍后重试'
            : (data && typeof data === 'object' && data.message)
            || FRIENDLY[status]
            || '网络异常，请稍后重试'
        return Promise.reject(withStatus(new Error(message), status))
    }
)

// 写操作前确保 XSRF-TOKEN Cookie 已存在。
// Spring Security 的 CSRF 令牌是延迟加载的：不先取一次，Cookie 根本不会被种下，
// 第一个 POST 就会被 403 拦下。这里在真正发请求前补一次，保证首次登录就能成功。
// 只在「请求进行中」时复用：后端登出会删掉 XSRF-TOKEN Cookie，缓存一个已完成的
// Promise 会让「退出后换账号登录」直接 403（Cookie 没了却不再去取）。
let csrfPending = null
function ensureCsrfToken() {
    if (!csrfPending) {
        csrfPending = request.get('/auth/csrf').finally(() => {
            csrfPending = null
        })
    }
    return csrfPending
}

request.interceptors.request.use(async (config) => {
    const method = (config.method || 'get').toLowerCase()
    if (method !== 'get' && method !== 'head' && !hasCsrfCookie()) {
        await ensureCsrfToken()
    }
    return config
})

function hasCsrfCookie() {
    return document.cookie.split(';').some(c => c.trim().startsWith('XSRF-TOKEN='))
}

// 调用方需要能区分「未登录」与「网络故障/后端 5xx」
function withStatus(err, status) {
    err.status = status
    return err
}

const FRIENDLY = {
    400: '请求参数有误，请检查后重试',
    403: '当前角色没有执行该操作的权限',
    404: '请求的资源不存在',
    409: '数据冲突，请检查后重试',
    500: '服务器处理失败，请稍后重试'
}

export default request
