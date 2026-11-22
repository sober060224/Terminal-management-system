<script setup>
import OrderList from '../../components/OrderList.vue'
import { sellerOrderApi } from '../../api'
</script>

<template>
    <OrderList title="代售订单" :api="sellerOrderApi" detail-prefix="/seller/orders" />
</template>
