<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { scheduleApi, sellerOrderApi } from '../../api'
import SeatMap from '../../components/SeatMap.vue'

const schedules = ref([])
const target = ref(null)
const seats = ref([])
const picked = ref(null)
const passengerName = ref('')
const idCard = ref('')
const submitting = ref(false)

async function loadSchedules() {
    const res = await scheduleApi.list()
    schedules.value = res.data || []
}

async function onPickSchedule(s) {
    target.value = s
    picked.value = null
    const res = await scheduleApi.seats(s.id)
    seats.value = (res.data && res.data.seats) || []
}

const cardPattern = /^\d{17}[\dXx]$/

async function submit() {
    if (!target.value) {
        ElMessage.warning('请选择班次')
        return
    }
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
        const res = await sellerOrderApi.create({
            scheduleId: target.value.id,
            seats: [{
                seatNo: picked.value.seatNo,
                passengerName: passengerName.value.trim(),
                idCard: idCard.value.trim()
            }]
        })
        await sellerOrderApi.pay(res.data.id)
        ElMessage.success('代售成功')
        passengerName.value = ''
        idCard.value = ''
        picked.value = null
        await onPickSchedule(target.value)
    } catch (e) {
        ElMessage.error(e.message || '代售失败')
    } finally {
        submitting.value = false
    }
}

onMounted(loadSchedules)
</script>

<template>
    <div class="card">
        <h2 class="page-title">代售开票</h2>

        <div class="field">
            <span class="label">选择班次</span>
            <el-select v-model="target" placeholder="请选择班次" style="width: 420px" @change="onPickSchedule">
                <el-option
                    v-for="s in schedules"
                    :key="s.id"
                    :label="`${s.routeName} ${s.departTime}（${s.shipName}）票价 ${s.price}`"
                    :value="s"
                />
            </el-select>
        </div>

        <div class="field">
            <span class="label">选择座位</span>
            <SeatMap v-if="target" :seats="seats" :selected="picked" @select="(s) => (picked = s)" />
            <el-empty v-else description="请先选择班次" />
        </div>

        <el-form label-width="90px" style="max-width: 420px">
            <el-form-item label="乘船人姓名">
                <el-input v-model="passengerName" placeholder="请输入姓名" />
            </el-form-item>
            <el-form-item label="身份证号">
                <el-input v-model="idCard" placeholder="18 位身份证号" maxlength="18" />
            </el-form-item>
            <el-form-item>
                <el-button type="primary" :loading="submitting" @click="submit">代售并支付</el-button>
            </el-form-item>
        </el-form>
    </div>
</template>

<style scoped>
.field {
    margin-bottom: 14px;
}

.label {
    display: block;
    font-size: 13px;
    color: #606266;
    margin-bottom: 6px;
}
</style>
