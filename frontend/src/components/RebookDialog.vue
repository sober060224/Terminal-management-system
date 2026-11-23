<script setup>
import { ref, watch, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { scheduleApi } from '../api'
import SeatMap from './SeatMap.vue'

// 改签对话框：选「同航线 + 售票中 + 距发船 1 小时以上」的目标班次，再选目标座位。
// 失败时弹窗不关、已选班次与座位保留，用户换个座位就能重试（M-11）。
const props = defineProps({
    modelValue: { type: Boolean, default: false },
    origin: { type: Object, default: null }, // 原票 { id, scheduleId, routeId, price, departTime, ... }
    // 提交回调：返回 Promise，失败请抛错，弹窗据此决定关不关
    submit: { type: Function, default: null }
})
const emit = defineEmits(['update:modelValue', 'done'])

// 与后端 OrderService 的改签窗口一致：原班次与目标班次都要距发船 1 小时以上
const ACTION_WINDOW_MS = 60 * 60 * 1000

const schedules = ref([])
const targetSchedule = ref(null)
const seats = ref([])
const picked = ref(null)
const submitting = ref(false)

const visible = computed({
    get: () => props.modelValue,
    set: (v) => emit('update:modelValue', v)
})

function farEnough(time) {
    return !!time && new Date(time).getTime() > Date.now() + ACTION_WINDOW_MS
}

// 原班次已经不足 1 小时：后端必然 409，直接把候选清空并说明原因
const originTooLate = computed(() => !!props.origin?.departTime && !farEnough(props.origin.departTime))

// 同航线可改签班次（排除原班次自身，且距发船 1 小时以上）
const candidates = computed(() =>
    originTooLate.value
        ? []
        : schedules.value.filter(s =>
            s.routeId === props.origin?.routeId
            && s.id !== props.origin?.scheduleId
            && farEnough(s.departTime)
        )
)

const emptyText = computed(() =>
    originTooLate.value
        ? '原班次距发船不足 1 小时，已经不能改签'
        : '没有满足条件的同航线班次（目标班次需在发船前 1 小时以上）'
)

watch(visible, async (open) => {
    if (open) {
        targetSchedule.value = null
        picked.value = null
        seats.value = []
        await loadSchedules()
    }
})

async function loadSchedules() {
    try {
        const res = await scheduleApi.list()
        schedules.value = res.data || []
    } catch (e) {
        schedules.value = []
        ElMessage.error(e.message || '加载班次失败')
    }
}

async function onPickSchedule(s) {
    targetSchedule.value = s
    picked.value = null
    const res = await scheduleApi.seats(s.id)
    seats.value = (res.data && res.data.seats) || []
}

async function confirm() {
    if (!targetSchedule.value) {
        ElMessage.warning('请先选择目标班次')
        return
    }
    if (!picked.value) {
        ElMessage.warning('请选择目标座位')
        return
    }
    const payload = {
        ticketId: props.origin.id,
        targetScheduleId: targetSchedule.value.id,
        targetSeatId: picked.value.seatId
    }
    submitting.value = true
    try {
        if (props.submit) {
            await props.submit(payload)
        } else {
            emit('done', payload)
        }
        ElMessage.success('改签成功')
        visible.value = false
    } catch (e) {
        // 不关弹窗、不清空：用户能直接换目标班次或座位重试
        ElMessage.error(e.message || '改签失败')
    } finally {
        submitting.value = false
    }
}
</script>

<template>
    <el-dialog v-model="visible" title="改签船票" width="720px" :close-on-click-modal="false">
        <template v-if="origin">
            <el-descriptions :column="2" border size="small" style="margin-bottom: 14px">
                <el-descriptions-item label="原班次">{{ origin.scheduleId }}</el-descriptions-item>
                <el-descriptions-item label="原票价">{{ origin.price }}</el-descriptions-item>
            </el-descriptions>

            <div class="field">
                <div class="label">选择目标班次（仅同航线，且发船前 1 小时以上）</div>
                <el-select
                    v-model="targetSchedule"
                    placeholder="请选择目标班次"
                    style="width: 100%"
                    :no-data-text="emptyText"
                    :disabled="!candidates.length"
                    @change="onPickSchedule"
                >
                    <el-option
                        v-for="s in candidates"
                        :key="s.id"
                        :label="`${s.routeName} ${s.departTime}（${s.shipName}）`"
                        :value="s"
                    />
                </el-select>
                <div v-if="!candidates.length" class="empty-tip">{{ emptyText }}</div>
            </div>

            <div class="field">
                <div class="label">选择目标座位</div>
                <SeatMap
                    v-if="targetSchedule"
                    :seats="seats"
                    :selected="picked ? [picked] : []"
                    @select="(s) => (picked = s)"
                />
                <el-empty v-else description="请先选择目标班次" />
            </div>
        </template>

        <template #footer>
            <el-button @click="visible = false">取消</el-button>
            <el-button type="primary" :loading="submitting" @click="confirm">确认改签</el-button>
        </template>
    </el-dialog>
</template>

<style scoped>
.field {
    margin-bottom: 14px;
}

.label {
    font-size: 13px;
    color: #606266;
    margin-bottom: 6px;
}

.empty-tip {
    margin-top: 6px;
    font-size: 12px;
    color: #909399;
}
</style>