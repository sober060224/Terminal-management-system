<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { scheduleApi, orderApi } from '../../api'
import SeatMap from '../../components/SeatMap.vue'

const route = useRoute()
const router = useRouter()
const scheduleId = Number(route.params.id)

const schedule = ref(null)
const seats = ref([])
const picked = ref(null)
const passengerName = ref('')
const idCard = ref('')
const loading = ref(false)
const submitting = ref(false)

async function load() {
    loading.value = true
    try {
        const [sRes, seatsRes] = await Promise.all([
            scheduleApi.detail(scheduleId),
            scheduleApi.seats(scheduleId)
        ])
        schedule.value = sRes.data
        seats.value = (seatsRes.data && seatsRes.data.seats) || []
    } catch (e) {
        ElMessage.error(e.message || '加载班次失败')
    } finally {
        loading.value = false
    }
}

const cardPattern = /^\d{17}[\dXx]$/

async function submit() {
    if (!picked.value) {
        ElMessage.warning('请选择座位')
        return
    }
    if (!passengerName.value.trim()) {
        ElMessage.warning('请填写乘船人姓名')
        return
    }
    if (!cardPattern.test(idCard.value.trim())) {
        ElMessage.warning('身份证号需为 18 位')
        return
    }
    submitting.value = true
    try {
        const createRes = await orderApi.create({
            scheduleId,
            seats: [{
                seatNo: picked.value.seatNo,
                passengerName: passengerName.value.trim(),
                idCard: idCard.value.trim()
            }]
        })
        const order = createRes.data
        await orderApi.pay(order.id)
        ElMessage.success('购票成功')
        router.push('/my-orders')
    } catch (e) {
        ElMessage.error(e.message || '购票失败')
    } finally {
        submitting.value = false
    }
}

onMounted(load)
</script>

<template>
    <div class="card" v-loading="loading">
        <div class="head">
            <h2 class="page-title">选座购票</h2>
            <el-button @click="router.push('/')">返回班次</el-button>
        </div>

        <el-descriptions v-if="schedule" :column="3" border size="small" style="margin-bottom: 14px">
            <el-descriptions-item label="航线">{{ schedule.routeName }}</el-descriptions-item>
            <el-descriptions-item label="船舶">{{ schedule.shipName }}</el-descriptions-item>
            <el-descriptions-item label="发船时间">{{ schedule.departTime }}</el-descriptions-item>
            <el-descriptions-item label="票价">{{ schedule.price }} 元</el-descriptions-item>
            <el-descriptions-item label="余票">{{ schedule.remainingSeats }}</el-descriptions-item>
            <el-descriptions-item label="已选座位">{{ picked ? picked.seatNo : '未选择' }}</el-descriptions-item>
        </el-descriptions>

        <SeatMap :seats="seats" :selected="picked" @select="(s) => (picked = s)" />

        <el-form label-width="90px" style="margin-top: 16px; max-width: 420px">
            <el-form-item label="乘船人姓名">
                <el-input v-model="passengerName" placeholder="请输入姓名" />
            </el-form-item>
            <el-form-item label="身份证号">
                <el-input v-model="idCard" placeholder="18 位身份证号" maxlength="18" />
            </el-form-item>
            <el-form-item>
                <el-button type="primary" :loading="submitting" @click="submit">下单并支付</el-button>
            </el-form-item>
        </el-form>
    </div>
</template>

<style scoped>
.head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 14px;
}
</style>
