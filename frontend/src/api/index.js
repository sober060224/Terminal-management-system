import request from './request'

export const authApi = {
    // 前端首屏调一次，后端会种下 XSRF-TOKEN Cookie
    csrf: () => request.get('/auth/csrf'),
    register: (data) => request.post('/auth/register', data),
    login: (data) => request.post('/auth/login', data),
    current: () => request.get('/auth/current'),
    logout: () => request.post('/auth/logout')
}
