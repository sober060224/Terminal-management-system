import request from './request'

export const authApi = {
    // 前端首屏调一次，后端会种下 XSRF-TOKEN Cookie
    csrf: () => request.get('/auth/csrf'),
    register: (data) => request.post('/auth/register', data),
    login: (data) => request.post('/auth/login', data),
    current: () => request.get('/auth/current'),
    logout: () => request.post('/auth/logout')
}

export const scheduleApi = {
    list: () => request.get('/schedules'),
    detail: (id) => request.get(`/schedules/${id}`),
    seats: (id) => request.get(`/schedules/${id}/seats`)
}

export const orderApi = {
    mine: () => request.get('/orders/mine'),
    detail: (id) => request.get(`/orders/${id}`),
    create: (data) => request.post('/orders', data),
    pay: (id) => request.post(`/orders/${id}/pay`),
    cancel: (id) => request.post(`/orders/${id}/cancel`)
}

export const ticketApi = {
    refund: (id, reason) => request.post(`/tickets/${id}/refund`, { reason }),
    rebook: (id, data) => request.post(`/tickets/${id}/rebook`, data)
}

// 模块二：航线 / 船舶 / 班次管理（docs/api.md §8.2–§8.4）
// 列表接口统一是 PageResult：res.data.items / total / page / size（page 从 1 开始，size 默认 10）。
export const adminRouteApi = {
    list: (params) => request.get('/admin/routes', { params }),
    detail: (id) => request.get(`/admin/routes/${id}`),
    create: (data) => request.post('/admin/routes', data),
    update: (id, data) => request.put(`/admin/routes/${id}`, data),
    remove: (id) => request.delete(`/admin/routes/${id}`)
}

export const adminShipApi = {
    list: (params) => request.get('/admin/ships', { params }),
    detail: (id) => request.get(`/admin/ships/${id}`),
    create: (data) => request.post('/admin/ships', data),
    update: (id, data) => request.put(`/admin/ships/${id}`, data),
    remove: (id) => request.delete(`/admin/ships/${id}`)
}

export const adminScheduleApi = {
    list: (params) => request.get('/admin/schedules', { params }),
    detail: (id) => request.get(`/admin/schedules/${id}`),
    create: (data) => request.post('/admin/schedules', data),
    update: (id, data) => request.put(`/admin/schedules/${id}`, data),
    // 批量生成是 POST /admin/schedules/batch，不是 /admin/schedules/{id} 下的动作
    batchCreate: (data) => request.post('/admin/schedules/batch', data),
    publish: (id) => request.post(`/admin/schedules/${id}/publish`),
    cancel: (id) => request.post(`/admin/schedules/${id}/cancel`)
}

export const sellerOrderApi = {
    list: (params) => request.get('/seller/orders', { params }),
    create: (data) => request.post('/seller/orders', data),
    pay: (id) => request.post(`/seller/orders/${id}/pay`),
    cancel: (id) => request.post(`/seller/orders/${id}/cancel`),
    refund: (ticketId, reason) => request.post(`/seller/orders/tickets/${ticketId}/refund`, { reason }),
    rebook: (ticketId, data) => request.post(`/seller/orders/tickets/${ticketId}/rebook`, data)
}
