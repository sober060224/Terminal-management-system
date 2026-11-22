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

export const sellerOrderApi = {
    list: (params) => request.get('/seller/orders', { params }),
    create: (data) => request.post('/seller/orders', data),
    pay: (id) => request.post(`/seller/orders/${id}/pay`),
    cancel: (id) => request.post(`/seller/orders/${id}/cancel`),
    refund: (ticketId, reason) => request.post(`/seller/orders/tickets/${ticketId}/refund`, { reason }),
    rebook: (ticketId, data) => request.post(`/seller/orders/tickets/${ticketId}/rebook`, data)
}
