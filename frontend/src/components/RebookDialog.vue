<script setup>
import { ref, watch, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { scheduleApi } from '../api'
import SeatMap from './SeatMap.vue'

// 改签对话框：选「同航线 + 售票中」的目标班次，再选目标座位，确认后调改签接口。
const props = defineProps({
    modelValue: { type: Boolean, default: false },
    origin: { type: Object, default: null } // 原票 { id, scheduleId, routeId, price, ... }
})
const emit = defineEmits(['update:modelValue', 'done'])

const schedules = ref([])
const targetSchedule = ref(null)
const seats = ref([])
const picked = ref(null)
const submitting = ref(false)

const visible = computed({
    get: () => props.modelValue,
    set: (v) => emit('update:modelValue', v)
})

// 同航线可改签班次（排除原班次自身）。
const candidates = computed(() =>
    schedules.value.filter(s => s.routeId === props.origin?.routeId && s.id !== props.origin?.scheduleId)
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
    const res = await scheduleApi.list()
    schedules.value = res.data || []
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
    submitting.value = true
    try {
        // 改签接口由调用方注入，这里只抛事件带齐参数。
        emit('done', {
            ticketId: props.origin.id,
            targetScheduleId: targetSchedule.value.id,
            targetSeatId: picked.value.seatId
        })
        visible.value = false
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
                <div class="label">选择目标班次（仅同航线）</div>
                <el-select
                    v-model="targetSchedule"
                    placeholder="请选择目标班次"
                    style="width: 100%"
                    @change="onPickSchedule"
                >
                    <el-option
                        v-for="s in candidates"
                        :key="s.id"
                        :label="`${s.routeName} ${s.departTime}（${s.shipName}）`"
                        :value="s"
                    />
                </el-select>
            </div>

            <div class="field">
                <div class="label">选择目标座位</div>
                <SeatMap
                    v-if="targetSchedule"
                    :seats="seats"
                    :selected="picked"
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
</style>
