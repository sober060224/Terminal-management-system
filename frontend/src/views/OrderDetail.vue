<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { orderApi } from '../api'

// 三端共用：乘客 / 售票员 / 管理员都从这里看订单详情，权限由后端判。
const route = useRoute()
const router = useRouter()

const order = ref(null)
const loading = ref(false)

async function load() {
    loading.value = true
    try {
        const res = await orderApi.detail(route.params.id)
        order.value = res.data
    } catch (e) {
        ElMessage.error(e.message || '加载订单失败')
    } finally {
        loading.value = false
    }
}

function statusType(status) {
    return {
        '待支付': 'warning',
        '已支付': 'success',
        '已检票': 'success',
        '已完成': 'info',
        '已取消': 'info',
        '已退票': 'danger'
    }[status] || ''
}

onMounted(load)
</script>

<template>
    <div class="card" v-loading="loading">
        <div class="head">
            <h2 class="page-title">订单详情</h2>
            <el-button @click="router.back()">返回</el-button>
        </div>

        <el-empty v-if="!loading && !order" description="订单不存在" />

        <template v-if="order">
            <el-descriptions title="订单信息" :column="2" border size="small" style="margin-bottom: 16px">
                <el-descriptions-item label="航线">{{ order.routeName }}</el-descriptions-item>
                <el-descriptions-item label="船舶">{{ order.shipName }}</el-descriptions-item>
                <el-descriptions-item label="下单时间">{{ order.createTime }}</el-descriptions-item>
                <el-descriptions-item label="实收金额">{{ order.totalAmount }} 元</el-descriptions-item>
                <el-descriptions-item label="订单号">{{ order.id }}</el-descriptions-item>
                <el-descriptions-item label="状态">
                    <el-tag :type="statusType(order.status)">{{ order.status }}</el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="支付时间">{{ order.payTime || '未支付' }}</el-descriptions-item>
            </el-descriptions>

            <div class="section-title">船票信息（改签过的票按各自班次显示发船时间）</div>
            <el-table :data="order.tickets" border size="small">
                <el-table-column prop="seatNo" label="座位号" width="90" />
                <el-table-column prop="passengerName" label="乘船人" width="100" />
                <el-table-column prop="idCard" label="身份证号" width="180" />
                <el-table-column prop="phone" label="手机号" width="130" />
                <el-table-column prop="price" label="票价" width="80" />
                <el-table-column prop="departTime" label="发船时间" min-width="160" />
                <el-table-column prop="status" label="票状态" width="90">
                    <template #default="{ row }">
                        <el-tag size="small" :type="statusType(row.status)">{{ row.status }}</el-tag>
                    </template>
                </el-table-column>
                <el-table-column prop="refundAmount" label="退票金额" width="100" />
                <el-table-column prop="refundReason" label="退票原因" width="120" />
                <el-table-column prop="refundTime" label="退票时间" min-width="160" />
            </el-table>
        </template>
    </div>
</template>

<style scoped>
.head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 14px;
}

.section-title {
    margin: 0 0 8px;
    font-size: 16px;
    font-weight: 700;
    color: #303133;
}
</style>
