<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import SeatMap from '../../components/SeatMap.vue'
import { adminScheduleApi } from '../../api'
import { crossesMidnight, effectiveArrive } from './scheduleRules'

// 班次详情（docs/api.md §8.4.2）
// GET /admin/schedules/{id} → AdminScheduleRowView + seats: [{ seatId, seatNo, state }]
// 座位图复用 §7.2 的 SeatMap，管理员只读（disabled）。

const route = useRoute()
const router = useRouter()

const detail = ref(null)
const seats = ref([])
const loading = ref(false)

const stats = computed(() => {
    const counter = { 可选: 0, 锁定: 0, 已售: 0 }
    for (const s of seats.value) {
        if (counter[s.state] !== undefined) counter[s.state] += 1
    }
    return counter
})

const arriveText = computed(() => {
    if (!detail.value) return '—'
    const arrive = effectiveArrive(detail.value.departTime, detail.value.arriveTime)
    if (!arrive) return '—'
    return crossesMidnight(detail.value.departTime, detail.value.arriveTime)
        ? `${arrive}（+1天）`
        : arrive
})

async function load() {
    loading.value = true
    try {
        const res = await adminScheduleApi.detail(route.params.id)
        const data = res.data || null
        detail.value = data
        seats.value = (data && data.seats) || []
    } catch (e) {
        ElMessage.error(e.message || '加载班次详情失败')
    } finally {
        loading.value = false
    }
}

function statusType(status) {
    return {
        '待发布': 'info',
        '售票中': 'success',
        '已发船': 'warning',
        '已完成': '',
        '已取消': 'danger'
    }[status] || ''
}

function goBack() {
    router.push('/admin/schedules')
}

onMounted(load)
</script>

<template>
    <div v-loading="loading">
        <div class="card">
            <div class="head">
                <h2 class="page-title">班次详情</h2>
                <div class="head-actions">
                    <el-button @click="goBack">返回班次列表</el-button>
                </div>
            </div>

            <el-empty v-if="!loading && !detail" description="班次不存在" />

            <template v-else-if="detail">
                <el-descriptions :column="3" border size="small">
                    <el-descriptions-item label="班次 ID">{{ detail.id }}</el-descriptions-item>
                    <el-descriptions-item label="航线">{{ detail.routeName }}</el-descriptions-item>
                    <el-descriptions-item label="船舶">{{ detail.shipName }}</el-descriptions-item>
                    <el-descriptions-item label="出发港">{{ detail.startPort }}</el-descriptions-item>
                    <el-descriptions-item label="到达港">{{ detail.endPort }}</el-descriptions-item>
                    <el-descriptions-item label="状态">
                        <el-tag size="small" :type="statusType(detail.status)">{{ detail.status }}</el-tag>
                    </el-descriptions-item>
                    <el-descriptions-item label="发船时间">{{ detail.departTime }}</el-descriptions-item>
                    <el-descriptions-item label="到达时间">{{ arriveText }}</el-descriptions-item>
                    <el-descriptions-item label="票价">{{ detail.price }} 元</el-descriptions-item>
                    <el-descriptions-item label="总座位数">{{ detail.totalSeats }}</el-descriptions-item>
                    <el-descriptions-item label="已售座位数">{{ detail.soldSeats }}</el-descriptions-item>
                    <el-descriptions-item label="余票">{{ detail.remainingSeats }}</el-descriptions-item>
                </el-descriptions>
            </template>
        </div>

        <div class="card map-card">
            <div class="head">
                <h2 class="page-title">座位图</h2>
                <div class="seat-stats">
                    <el-tag size="small" type="success">可选 {{ stats['可选'] }}</el-tag>
                    <el-tag size="small" type="warning">锁定 {{ stats['锁定'] }}</el-tag>
                    <el-tag size="small" type="info">已售 {{ stats['已售'] }}</el-tag>
                </div>
            </div>
            <SeatMap :seats="seats" :disabled="true" />
        </div>
    </div>
</template>

<style scoped>
.head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 14px;
}

.head .page-title {
    margin-bottom: 0;
}

.head-actions {
    display: flex;
    gap: 10px;
}

.map-card {
    margin-top: 14px;
}

.seat-stats {
    display: flex;
    gap: 8px;
}
</style>