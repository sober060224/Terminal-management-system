const MENU_ITEMS = [
    { path: '/admin', title: '管理后台', icon: 'Setting', roles: ['ADMIN'] },
    { path: '/admin/orders', title: '全部订单', icon: 'List', roles: ['ADMIN'] },
    { path: '/seller', title: '售票工作台', icon: 'Tickets', roles: ['ADMIN', 'SELLER'] },
    { path: '/seller/sell', title: '代售开票', icon: 'CirclePlus', roles: ['SELLER'] },
    { path: '/seller/orders', title: '代售订单', icon: 'Document', roles: ['ADMIN', 'SELLER'] },
    { path: '/', title: '查询班次', icon: 'Van', roles: ['ADMIN', 'PASSENGER'] },
    { path: '/my-orders', title: '我的船票', icon: 'Document', roles: ['PASSENGER'] }
]

const ROLE_KEYS = ['ADMIN', 'SELLER', 'PASSENGER']

// 菜单的唯一配置源。新增模块时只追加已经存在的真实路由，不在这里造占位入口。
export const NAVIGATION = Object.freeze(
    Object.fromEntries(ROLE_KEYS.map(role => [
        role,
        MENU_ITEMS.filter(item => item.roles.includes(role))
    ]))
)

export function navigationFor(role) {
    return Object.prototype.hasOwnProperty.call(NAVIGATION, role) ? NAVIGATION[role] : []
}
