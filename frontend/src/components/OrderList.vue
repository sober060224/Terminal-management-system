<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import RebookDialog from './RebookDialog.vue'

// 三端通用订单列表：乘客、售票员、管理员只是在传进来的 api 上不同，
// 列表/支付/取消/退票/改签的交互完全一致，抽出来避免三份重复实现。
const props = defineProps({
    title: { type: String, required: true },
    api: { type: Object, required: true } // { list, pay?, cancel?, refund, rebook }
})

const orders = ref([])
const loading = ref(false)
const rebookTicket = ref(null)
const rebookVisible = ref(false)

async function load() {
    loading.value = true
    try {
        const res = await props.api.list()
        orders.value = res.data || []
    } catch (e) {
        ElMessage.error(e.message || '加载订单失败')
    } finally {
        loading.value = false
    }
}

async function pay(order) {
    try {
        await props.api.pay(order.id)
        ElMessage.success('支付成功')
        load()
    } catch (e) {
        ElMessage.error(e.message || '支付失败')
    }
}

async function cancel(order) {
    try {
        await props.api.cancel(order.id)
        ElMessage.success('订单已取消')
        load()
    } catch (e) {
        ElMessage.error(e.message || '取消失败')
    }
}

async function refund(ticket) {
    try {
        await ElMessageBox.confirm(
            `确认退掉 ${ticket.passengerName} 的这张 ${ticket.price} 元船票？可退金额以提交结果为准。`,
            '确认退票',
            { type: 'warning' }
        )
    } catch {
        return
    }
    try {
        await props.api.refund(ticket.id)
        ElMessage.success('退票成功')
        load()
    } catch (e) {
        ElMessage.error(e.message || '退票失败')
    }
}

function openRebook(ticket) {
    rebookTicket.value = ticket
    rebookVisible.value = true
}

async function onRebookDone(payload) {
    try {
        await props.api.rebook(payload.ticketId, {
            targetScheduleId: payload.targetScheduleId,
            targetSeatId: payload.targetSeatId
        })
        ElMessage.success('改签成功')
        load()
    } catch (e) {
        ElMessage.error(e.message || '改签失败')
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
    <div class="card">
        <div class="head">
            <h2 class="page-title">{{ title }}</h2>
            <el-button :loading="loading" @click="load">刷新</el-button>
        </div>

        <el-empty v-if="!loading && !orders.length" description="暂无订单" />

        <div class="order" v-for="o in orders" :key="o.id">
            <div class="order-head">
                <span class="oid">订单 #{{ o.id }}</span>
                <el-tag :type="statusType(o.status)">{{ o.status }}</el-tag>
                <span class="amount">实收 {{ o.totalAmount }} 元</span>
                <span class="time">{{ o.createTime }}</span>
            </div>

            <el-table :data="o.tickets" size="small" border>
                <el-table-column prop="passengerName" label="乘船人" width="100" />
                <el-table-column prop="idCard" label="身份证号" width="180" />
                <el-table-column prop="price" label="票价" width="80" />
                <el-table-column prop="status" label="票状态" width="90">
                    <template #default="{ row }">
                        <el-tag size="small" :type="statusType(row.status)">{{ row.status }}</el-tag>
                    </template>
                </el-table-column>
                <el-table-column label="操作" min-width="160">
                    <template #default="{ row }">
                        <el-button
                            v-if="row.status === '已支付'"
                            size="small"
                            type="danger"
                            link
                            @click="refund(row)"
                        >退票</el-button>
                        <el-button
                            v-if="row.status === '已支付'"
                            size="small"
                            type="primary"
                            link
                            @click="openRebook(row)"
                        >改签</el-button>
                    </template>
                </el-table-column>
            </el-table>

            <div class="order-actions" v-if="o.status === '待支付'">
                <el-button type="primary" size="small" @click="pay(o)">去支付</el-button>
                <el-button size="small" @click="cancel(o)">取消订单</el-button>
            </div>
        </div>

        <RebookDialog
            v-model="rebookVisible"
            :origin="rebookTicket || {}"
            @done="onRebookDone"
        />
    </div>
</template>

<style scoped>
.head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 14px;
}

.order {
    border: 1px solid #ebeef5;
    border-radius: 6px;
    padding: 12px;
    margin-bottom: 14px;
}

.order-head {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 10px;
    flex-wrap: wrap;
}

.oid {
    font-weight: 600;
}

.amount {
    color: #e6a23c;
    font-weight: 600;
}

.time {
    color: #909399;
    font-size: 12px;
}

.order-actions {
    margin-top: 10px;
    display: flex;
    gap: 10px;
}
</style>
