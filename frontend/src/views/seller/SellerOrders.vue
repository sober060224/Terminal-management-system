<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import OrderList from '../../components/OrderList.vue'
import { sellerOrderApi } from '../../api'

const orderId = ref('')
const idCard = ref('')
// 默认列自己经手的订单；填了订单号或身份证就跨经手人查，窗口代退要处理乘客线上自购的票
const params = ref(null)

function search() {
    const p = {}
    const id = orderId.value.trim()
    const card = idCard.value.trim()
    if (id && !/^[0-9]+$/.test(id)) {
        ElMessage.warning('订单号只能是数字')
        return
    }
    if (id) p.orderId = Number(id)
    if (card) p.idCard = card
    params.value = Object.keys(p).length ? p : null
}
</script>

<template>
    <div>
        <div class="card search-card">
            <el-form :inline="true">
                <el-form-item label="订单号">
                    <el-input v-model="orderId" placeholder="精确订单号" clearable style="width: 160px"
                              @keyup.enter="search" />
                </el-form-item>
                <el-form-item label="身份证号">
                    <el-input v-model="idCard" placeholder="乘船人 18 位身份证号" clearable style="width: 240px"
                              @keyup.enter="search" />
                </el-form-item>
                <el-form-item>
                    <el-button type="primary" @click="search">查询</el-button>
                </el-form-item>
            </el-form>
        </div>
        <OrderList title="代售订单" :api="sellerOrderApi" :params="params" detail-prefix="/seller/orders" />
    </div>
</template>
