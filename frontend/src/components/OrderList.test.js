import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { describe, expect, it, vi } from 'vitest'

// 改签弹窗自己去拉班次列表，这里挡掉真实请求
vi.mock('../api', () => ({
    scheduleApi: {
        list: vi.fn(async () => ({
            data: [
                { id: 2, routeId: 7, departTime: '2026-11-22 08:00', price: '100.00' },
                { id: 3, routeId: 9, departTime: '2026-11-22 09:00', price: '100.00' }
            ]
        })),
        seats: vi.fn(async () => ({ data: { seats: [] } }))
    }
}))

import OrderList from './OrderList.vue'
import RebookDialog from './RebookDialog.vue'

const ORDERS = [
    {
        id: 1,
        status: '已支付',
        totalAmount: '100.00',
        createTime: '2026-11-21 10:00',
        routeId: 7,
        tickets: [
            { id: 10, passengerName: '张三', idCard: '110101199001011234', price: '100.00', status: '已支付' }
        ]
    }
]

function mountList(overrides = {}) {
    const api = {
        list: vi.fn(async () => ({ data: ORDERS })),
        pay: vi.fn(async () => ({})),
        cancel: vi.fn(async () => ({})),
        refund: vi.fn(async () => ({})),
        rebook: vi.fn(async () => ({})),
        ...overrides
    }
    const wrapper = mount(OrderList, {
        props: { title: '我的船票', api },
        global: { plugins: [ElementPlus] }
    })
    return { wrapper, api }
}

function buttonWithText(wrapper, text) {
    return wrapper.findAll('button').find(button => button.text() === text)
}

describe('OrderList', () => {
    it('点退票调用注入的 refund，不再 undefined is not a function', async () => {
        vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
        const { wrapper, api } = mountList()
        await flushPromises()

        await buttonWithText(wrapper, '退票').trigger('click')
        await flushPromises()

        expect(api.refund).toHaveBeenCalledWith(10)
    })

    it('改签弹窗拿到订单级的 routeId，候选班次才筛得出来', async () => {
        const { wrapper } = mountList()
        await flushPromises()

        await buttonWithText(wrapper, '改签').trigger('click')
        await flushPromises()

        const dialog = wrapper.findComponent(RebookDialog)
        expect(dialog.props('origin').routeId).toBe(7)
        expect(dialog.props('origin').id).toBe(10)
        expect(dialog.props('origin').scheduleId).toBeUndefined()
    })

    it('api 缺退票方法时按钮点了也不静默崩（组件不吞错）', async () => {
        vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
        const { wrapper } = mountList({ refund: undefined })
        await flushPromises()

        await buttonWithText(wrapper, '退票').trigger('click')
        await flushPromises()

        expect(wrapper.exists()).toBe(true)
    })
})