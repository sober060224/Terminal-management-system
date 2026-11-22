<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import RebookDialog from './RebookDialog.vue'

// 三端通用订单列表：乘客、售票员、管理员只是在传进来的 api 上不同，
// 列表/支付/取消/退票/改签的交互完全一致，抽出来避免三份重复实现。
const props = defineProps({
    title: { type: String, required: true },
    api: { type: Object, required: true }, // { list, pay?, cancel?, refund, rebook }
    params: { type: Object, default: null }, // 可选查询参数，变化时重新加载
    detailPrefix: { type: String, default: '/orders' }
})

const router = useRouter()

const REFUND_REASONS = ['行程变更', '重复购票', '误购', '其他']

const orders = ref([])
const loading = ref(false)
const rebookTicket = ref(null)
const rebookVisible = ref(false)
const statusFilter = ref('全部')
const refundVisible = ref(false)
const refundTicket = ref(null)
const refundReason = ref('')
const refunding = ref(false)

const filteredOrders = computed(() =>
    statusFilter.value === '全部'
        ? orders.value
        : orders.value.filter(o => o.status === statusFilter.value)
)

async function load() {
    loading.value = true
    try {
        const res = await props.api.list(props.params || undefined)
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

function openRefund(ticket) {
    refundTicket.value = ticket
    refundReason.value = ''
    refundVisible.value = true
}

async function confirmRefund() {
    if (!refundReason.value) {
        ElMessage.warning('请选择退票原因')
        return
    }
    refunding.value = true
    try {
        await props.api.refund(refundTicket.value.id, refundReason.value)
        refundVisible.value = false
        ElMessage.success('退票成功')
        load()
    } catch (e) {
        ElMessage.error(e.message || '退票失败')
    } finally {
        refunding.value = false
    }
}

function openDetail(order) {
    router.push(`${props.detailPrefix}/${order.id}`)
}

function openRebook(ticket, order) {
    // 票视图里没有 routeId，改签候选要靠订单级的 routeId 筛同航线，这里合并进去
    rebookTicket.value = { ...ticket, routeId: order?.routeId }
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

watch(() => props.params, load, { deep: true })
onMounted(load)
</script>

<template>
    <div class="card">
        <div class="head">
            <h2 class="page-title">{{ title }}</h2>
            <div class="head-actions">
                <el-select v-model="statusFilter" style="width: 130px">
                    <el-option v-for="s in ['全部', '待支付', '已支付', '已退票']" :key="s" :label="s" :value="s" />
                </el-select>
                <el-button :loading="loading" @click="load">刷新</el-button>
            </div>
        </div>

        <el-empty v-if="!loading && !filteredOrders.length" description="暂无订单" />

        <div class="order" v-for="o in filteredOrders" :key="o.id">
            <div class="order-head">
                <span class="oid">订单 #{{ o.id }}</span>
                <el-tag :type="statusType(o.status)">{{ o.status }}</el-tag>
                <span class="amount">实收 {{ o.totalAmount }} 元</span>
                <span class="time">{{ o.createTime }}</span>
                <el-button size="small" link type="primary" @click="openDetail(o)">详情</el-button>
            </div>

            <el-table :data="o.tickets" size="small" border>
                <el-table-column prop="seatNo" label="座位号" width="90" />
                <el-table-column prop="passengerName" label="乘船人" width="100" />
                <el-table-column prop="idCard" label="身份证号" width="180" />
                <el-table-column prop="phone" label="手机号" width="130" />
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
                            @click="openRefund(row)"
                        >退票</el-button>
                        <el-button
                            v-if="row.status === '已支付'"
                            size="small"
                            type="primary"
                            link
                            @click="openRebook(row, o)"
                        >改签</el-button>
                    </template>
                </el-table-column>
            </el-table>

            <div class="order-actions" v-if="o.status === '待支付'">
                <el-button type="primary" size="small" @click="pay(o)">去支付</el-button>
                <el-button size="small" @click="cancel(o)">取消订单</el-button>
            </div>
        </div>

        <el-dialog v-model="refundVisible" title="退票" width="440px" :close-on-click-modal="false">
            <div v-if="refundTicket" class="refund-tip">
                确认退掉 {{ refundTicket.passengerName }} 的这张 {{ refundTicket.price }} 元船票？可退金额以提交结果为准。
            </div>
            <el-select v-model="refundReason" placeholder="请选择退票原因" style="width: 100%">
                <el-option v-for="r in REFUND_REASONS" :key="r" :label="r" :value="r" />
            </el-select>
            <template #footer>
                <el-button @click="refundVisible = false">取消</el-button>
                <el-button type="danger" :loading="refunding" @click="confirmRefund">确认退票</el-button>
            </template>
        </el-dialog>

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

.head-actions {
    display: flex;
    align-items: center;
    gap: 10px;
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

.refund-tip {
    margin-bottom: 12px;
    color: #606266;
    font-size: 13px;
    line-height: 1.6;
}
</style>
