<script setup>
import OrderList from '../../components/OrderList.vue'
import { sellerOrderApi } from '../../api'
</script>

<template>
    <OrderList title="全部订单" :api="sellerOrderApi" />
</template>
