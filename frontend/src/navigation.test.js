import { describe, it, expect } from 'vitest'
import { NAVIGATION, navigationFor } from './navigation'

const withoutRoles = items => items.map(({ path, title, icon }) => ({ path, title, icon }))

describe('navigation', () => {
    it('管理员菜单保持现有入口、标题和图标', () => {
        expect(withoutRoles(navigationFor('ADMIN'))).toEqual([
            { path: '/admin', title: '管理后台', icon: 'Setting' },
            { path: '/admin/orders', title: '全部订单', icon: 'List' },
            { path: '/seller', title: '售票工作台', icon: 'Tickets' },
            { path: '/seller/orders', title: '代售订单', icon: 'Document' },
            { path: '/', title: '查询班次', icon: 'Van' }
        ])
    })

    it('售票员只看到售票入口', () => {
        expect(withoutRoles(navigationFor('SELLER'))).toEqual([
            { path: '/seller', title: '售票工作台', icon: 'Tickets' },
            { path: '/seller/sell', title: '代售开票', icon: 'CirclePlus' },
            { path: '/seller/orders', title: '代售订单', icon: 'Document' }
        ])
    })

    it('乘客看到查询和我的船票', () => {
        expect(withoutRoles(navigationFor('PASSENGER'))).toEqual([
            { path: '/', title: '查询班次', icon: 'Van' },
            { path: '/my-orders', title: '我的船票', icon: 'Document' }
        ])
    })

    it('未知角色没有菜单，包括对象原型属性名', () => {
        expect(navigationFor('UNKNOWN')).toEqual([])
        expect(navigationFor('toString')).toEqual([])
    })

    it('每个菜单项都有路径、标题、图标和角色限制', () => {
        for (const items of Object.values(NAVIGATION)) {
            for (const item of items) {
                expect(item.path).toBeTruthy()
                expect(item.title).toBeTruthy()
                expect(item.icon).toBeTruthy()
                expect(item.roles.length).toBeGreaterThan(0)
            }
        }
    })
})
