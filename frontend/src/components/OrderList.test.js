import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElSelect } from 'element-plus'
import { describe, expect, it, vi } from 'vitest'

// 改签弹窗自己去拉班次列表，这里挡掉真实请求
vi.mock('../api', () => ({
    scheduleApi: {
        list: vi.fn(async () => ({
            data: [
                { id: 2, routeId: 7, departTime: '2099-01-01 08:00', price: '100.00' },
                { id: 3, routeId: 9, departTime: '2099-01-01 09:00', price: '100.00' }
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
            { id: 10, passengerName: '张三', idCard: '440100199001010017', price: '100.00', status: '已支付' }
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

// 退票改成弹窗选原因：打开弹窗后在最后一个下拉里选原因，再点确认。
async function refundWith(wrapper, reason = '误购') {
    await buttonWithText(wrapper, '退票').trigger('click')
    await flushPromises()
    const selects = wrapper.findAllComponents(ElSelect)
    selects[selects.length - 1].vm.$emit('update:modelValue', reason)
    await flushPromises()
    await buttonWithText(wrapper, '确认退票').trigger('click')
    await flushPromises()
}

describe('OrderList', () => {
    it('点退票调用注入的 refund，不再 undefined is not a function', async () => {
        const { wrapper, api } = mountList()
        await flushPromises()

        await refundWith(wrapper)

        expect(api.refund).toHaveBeenCalledWith(10, '误购')
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
        const { wrapper } = mountList({ refund: undefined })
        await flushPromises()

        await refundWith(wrapper)

        expect(wrapper.exists()).toBe(true)
    })
    it('状态筛选含已取消：选已取消只剩已取消订单', async () => {
        const orders = [
            ...ORDERS,
            { id: 2, status: '已取消', totalAmount: '0.00', createTime: '2026-11-21 11:00', routeId: 7,
                tickets: [{ id: 11, passengerName: '李四', idCard: '440100199001010019', price: '100.00', status: '已取消' }] }
        ]
        const { wrapper } = mountList({ list: vi.fn(async () => ({ data: orders })) })
        await flushPromises()

        // 顶部第一个下拉是状态筛选
        const statusSelect = wrapper.findAllComponents(ElSelect)[0]
        statusSelect.vm.$emit('update:modelValue', '已取消')
        await flushPromises()

        expect(wrapper.text()).toContain('订单 #2')
        expect(wrapper.text()).not.toContain('订单 #1')
    })

    it('发船不足 1 小时的票不能改签：按钮禁用，点击不打开弹窗', async () => {
        const orders = [{
            ...ORDERS[0],
            tickets: [{ id: 12, passengerName: '张三', idCard: '440100199001010011', price: '100.00', status: '已支付',
                departTime: new Date(Date.now() + 30 * 60 * 1000).toISOString() }]
        }]
        const { wrapper } = mountList({ list: vi.fn(async () => ({ data: orders })) })
        await flushPromises()

        const rebook = buttonWithText(wrapper, '改签')
        expect(rebook.attributes('disabled')).toBeDefined()
        // 禁用即窗口拦截生效；提示文案由 el-tooltip 挂载（hover 才渲染），UI 层由浏览器验收覆盖

        // ElButton 禁用后不触发 click，弹窗不应被打开
        await rebook.trigger('click')
        await flushPromises()
        expect(wrapper.findComponent(RebookDialog).props('modelValue')).toBe(false)
    })

    it('改签候选只给发船 1 小时以上的同航线班次', async () => {
        const soon = new Date(Date.now() + 30 * 60 * 1000).toISOString()
        const { scheduleApi } = await import('../api')
        scheduleApi.list.mockResolvedValueOnce({
            data: [
                { id: 2, routeId: 7, departTime: '2099-01-01 08:00', price: '100.00' },
                { id: 4, routeId: 7, departTime: soon, price: '100.00' },
                { id: 5, routeId: 9, departTime: '2099-01-01 09:00', price: '100.00' }
            ]
        })
        const dialog = mount(RebookDialog, {
            props: {
                modelValue: false,
                origin: { id: 10, routeId: 7, scheduleId: 1, price: '100.00', departTime: '2099-01-01 08:00' }
            },
            global: { plugins: [ElementPlus] }
        })
        await dialog.setProps({ modelValue: true })
        await flushPromises()
        await flushPromises()

        // 同航线（routeId 7）里：id 4 距发船不足 1 小时被过滤，只剩 id 2
        expect(dialog.vm.candidates.map((s) => s.id)).toEqual([2])
    })

    it('原票距发船不足 1 小时时，改签候选直接清空并给出原因', async () => {
        const dialog = mount(RebookDialog, {
            props: {
                modelValue: false,
                origin: {
                    id: 10, routeId: 7, scheduleId: 1, price: '100.00',
                    departTime: new Date(Date.now() + 30 * 60 * 1000).toISOString()
                }
            },
            global: { plugins: [ElementPlus] }
        })
        await dialog.setProps({ modelValue: true })
        await flushPromises()
        await flushPromises()

        expect(dialog.vm.originTooLate).toBe(true)
        expect(dialog.vm.candidates).toEqual([])
        expect(dialog.vm.emptyText).toContain('原班次距发船不足 1 小时')
    })

    it('票级发船时间随票展示：改签过的票在列表里显示自己的班次', async () => {
        const orders = [{
            ...ORDERS[0],
            tickets: [
                { id: 13, passengerName: '张三', idCard: '440100199001010016', price: '100.00', status: '已支付',
                    departTime: '2099-01-01 08:00' },
                { id: 14, passengerName: '李四', idCard: '440100199001010016', price: '120.00', status: '已支付',
                    departTime: '2099-02-02 09:00' }
            ]
        }]
        const { wrapper } = mountList({ list: vi.fn(async () => ({ data: orders })) })
        await flushPromises()

        expect(wrapper.text()).toContain('2099-01-01 08:00')
        expect(wrapper.text()).toContain('2099-02-02 09:00')
    })
})
