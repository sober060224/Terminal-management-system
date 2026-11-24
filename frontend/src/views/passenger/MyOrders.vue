<script setup>
import OrderList from '../../components/OrderList.vue'
import { orderApi, ticketApi } from '../../api'

// 列单/支付/取消走订单接口，退票改签是票级接口（/api/tickets/{id}/refund|rebook）。
// 通用组件要的是 list/refund/rebook 这几个名字，订单接口里叫 mine，这里对齐，
// 缺一个按钮就点成 undefined is not a function。
const passengerOrderApi = {
    list: orderApi.mine,
    create: orderApi.create,
    pay: orderApi.pay,
    cancel: orderApi.cancel,
    refund: ticketApi.refund,
    rebook: ticketApi.rebook
}
</script>

<template>
    <OrderList title="我的船票" :api="passengerOrderApi" />
</template>