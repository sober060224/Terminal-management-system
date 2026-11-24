<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { scheduleApi, sellerOrderApi } from '../../api'
import SeatMap from '../../components/SeatMap.vue'

const router = useRouter()

const MAX_SEATS = 5
const cardPattern = /^\d{17}[\dXx]$/
const phonePattern = /^1[3-9]\d{9}$/

const schedules = ref([])
const target = ref(null)
const seats = ref([])
// 每位乘客一行，座位与乘客信息绑在同一行：删行即取消该座位。
const passengers = ref([])
const submitting = ref(false)
const confirmVisible = ref(false)

const selectedSeats = computed(() =>
    passengers.value.map(p => ({ seatId: p.seatId, seatNo: p.seatNo }))
)

const totalAmount = computed(() => {
    if (!target.value) return '0.00'
    return (Number(target.value.price) * passengers.value.length).toFixed(2)
})

async function loadSchedules() {
    const res = await scheduleApi.list()
    schedules.value = res.data || []
}

async function onPickSchedule(s) {
    target.value = s
    passengers.value = []
    const res = await scheduleApi.seats(s.id)
    seats.value = (res.data && res.data.seats) || []
}

function onSelect(seat) {
    const index = passengers.value.findIndex(p => p.seatId === seat.seatId)
    if (index >= 0) {
        passengers.value.splice(index, 1)
        return
    }
    if (passengers.value.length >= MAX_SEATS) {
        ElMessage.warning('最多选择 5 张票')
        return
    }
    passengers.value.push({
        seatId: seat.seatId,
        seatNo: seat.seatNo,
        passengerName: '',
        idCard: '',
        phone: ''
    })
}

function removeRow(index) {
    passengers.value.splice(index, 1)
}

function validate() {
    if (!target.value) {
        ElMessage.warning('请选择班次')
        return false
    }
    if (!passengers.value.length) {
        ElMessage.warning('请选择座位')
        return false
    }
    for (const p of passengers.value) {
        if (!p.passengerName.trim()) {
            ElMessage.warning(`请填写 ${p.seatNo} 的乘船人姓名`)
            return false
        }
        if (!cardPattern.test(p.idCard.trim())) {
            ElMessage.warning(`请填写 ${p.seatNo} 的 18 位身份证号`)
            return false
        }
        if (!phonePattern.test(p.phone.trim())) {
            ElMessage.warning(`请填写 ${p.seatNo} 的 11 位手机号`)
            return false
        }
    }
    return true
}

function openConfirm() {
    if (validate()) confirmVisible.value = true
}

async function submit() {
    submitting.value = true
    try {
        await sellerOrderApi.create({
            scheduleId: target.value.id,
            seats: passengers.value.map(p => ({
                seatNo: p.seatNo,
                passengerName: p.passengerName.trim(),
                idCard: p.idCard.trim(),
                phone: p.phone.trim()
            }))
        })
        confirmVisible.value = false
        ElMessage.success('代售成功，请在 15 分钟内完成支付')
        passengers.value = []
        try {
            await onPickSchedule(target.value)
        } catch {
            seats.value = []
        }
        try {
            await ElMessageBox.confirm('是否前往代售订单查看？', '代售成功', {
                confirmButtonText: '去代售订单',
                cancelButtonText: '继续售票',
                type: 'success'
            })
            router.push('/seller/orders')
        } catch {
            // 选择继续售票，留在当前页
        }
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
            <span class="label">选择座位（最多 5 个）</span>
            <SeatMap v-if="target" :seats="seats" :selected="selectedSeats" @select="onSelect" />
            <el-empty v-else description="请先选择班次" />
        </div>

        <div class="section-title">乘客信息</div>
        <el-table :data="passengers" border size="small" empty-text="点击上方座位开始填写">
            <el-table-column prop="seatNo" label="座位号" width="90" />
            <el-table-column label="姓名">
                <template #default="{ row }">
                    <el-input v-model="row.passengerName" placeholder="乘船人姓名" />
                </template>
            </el-table-column>
            <el-table-column label="身份证号">
                <template #default="{ row }">
                    <el-input v-model="row.idCard" placeholder="18 位身份证号" maxlength="18" />
                </template>
            </el-table-column>
            <el-table-column label="手机号">
                <template #default="{ row }">
                    <el-input v-model="row.phone" placeholder="11 位手机号" maxlength="11" />
                </template>
            </el-table-column>
            <el-table-column label="操作" width="80">
                <template #default="{ $index }">
                    <el-button link type="danger" @click="removeRow($index)">删除</el-button>
                </template>
            </el-table-column>
        </el-table>

        <div class="footer">
            <span class="total">合计 {{ totalAmount }} 元</span>
            <el-button type="primary" @click="openConfirm">确认订单</el-button>
        </div>

        <el-dialog v-model="confirmVisible" title="确认订单" width="760px" :close-on-click-modal="false">
            <el-descriptions v-if="target" :column="2" border size="small" style="margin-bottom: 14px">
                <el-descriptions-item label="航线">{{ target.routeName }}</el-descriptions-item>
                <el-descriptions-item label="船舶">{{ target.shipName }}</el-descriptions-item>
                <el-descriptions-item label="发船时间">{{ target.departTime }}</el-descriptions-item>
                <el-descriptions-item label="座位号">
                    {{ passengers.map(p => p.seatNo).join('、') }}
                </el-descriptions-item>
            </el-descriptions>

            <el-table :data="passengers" border size="small">
                <el-table-column prop="seatNo" label="座位号" width="90" />
                <el-table-column prop="passengerName" label="姓名" />
                <el-table-column prop="idCard" label="身份证号" width="190" />
                <el-table-column prop="phone" label="手机号" width="130" />
            </el-table>

            <div class="total dialog-total">共 {{ passengers.length }} 张，合计 {{ totalAmount }} 元</div>

            <template #footer>
                <el-button @click="confirmVisible = false">取消</el-button>
                <el-button type="primary" :loading="submitting" @click="submit">提交订单</el-button>
            </template>
        </el-dialog>
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

.section-title {
    margin: 16px 0 8px;
    font-size: 14px;
    color: #606266;
}

.footer {
    margin-top: 14px;
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 16px;
}

.total {
    color: #e6a23c;
    font-weight: 600;
}

.dialog-total {
    margin-top: 12px;
    text-align: right;
}
</style>
