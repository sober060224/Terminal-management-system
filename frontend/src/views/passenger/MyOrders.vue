<script setup>
import OrderList from '../../components/OrderList.vue'
import { orderApi } from '../../api'
</script>

<template>
    <OrderList title="我的船票" :api="orderApi" />
</template>
