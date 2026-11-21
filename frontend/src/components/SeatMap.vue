<script setup>
import { computed } from 'vue'

// 座位号规则（database.sql CHECK）：1-2 个大写字母 + 1-4 数字，如 A1 / AB4。
// 按排（字母）分组，每排内按座位号排序，渲染成网格。
const props = defineProps({
    seats: { type: Array, required: true }, // [{ seatId, seatNo, state }]
    selected: { type: Object, default: null }, // 选中的 { seatId, seatNo }
    disabled: { type: Boolean, default: false }
})
const emit = defineEmits(['select'])

const rows = computed(() => {
    const map = new Map()
    for (const s of props.seats) {
        const row = s.seatNo.replace(/[0-9]+$/, '')
        if (!map.has(row)) map.set(row, [])
        map.get(row).push(s)
    }
    return [...map.entries()]
        .sort((a, b) => a[0].localeCompare(b[0]))
        .map(([row, list]) => ({
            row,
            seats: list.sort((a, b) => Number(a.seatNo.match(/\d+$/)[0]) - Number(b.seatNo.match(/\d+$/)[0]))
        }))
})

function isSelected(seat) {
    return props.selected && props.selected.seatId === seat.seatId
}

// 可选才允许点；锁定/已售一律禁用。
function selectable(seat) {
    return !props.disabled && seat.state === '可选'
}

function onClick(seat) {
    if (!selectable(seat)) return
    emit('select', { seatId: seat.seatId, seatNo: seat.seatNo })
}
</script>

<template>
    <div class="seat-map" v-if="rows.length">
        <div class="legend">
            <span class="lg"><i class="dot available"/>可选</span>
            <span class="lg"><i class="dot locked"/>锁定</span>
            <span class="lg"><i class="dot sold"/>已售</span>
            <span class="lg"><i class="dot picked"/>已选</span>
        </div>
        <div class="grid">
            <div class="row" v-for="r in rows" :key="r.row">
                <span class="row-label">{{ r.row }}排</span>
                <button
                    v-for="s in r.seats"
                    :key="s.seatId"
                    class="seat"
                    :class="[`s-${s.state}`, { picked: isSelected(s) }]"
                    :disabled="!selectable(s)"
                    @click="onClick(s)"
                >{{ s.seatNo }}</button>
            </div>
        </div>
    </div>
    <el-empty v-else description="该班次暂无座位" />
</template>

<style scoped>
.seat-map {
    max-height: 460px;
    overflow: auto;
    background: #fafbfc;
    border: 1px solid #ebeef5;
    border-radius: 6px;
    padding: 12px;
}

.legend {
    display: flex;
    gap: 16px;
    font-size: 12px;
    color: #606266;
    margin-bottom: 10px;
}

.lg {
    display: inline-flex;
    align-items: center;
    gap: 4px;
}

.dot {
    display: inline-block;
    width: 10px;
    height: 10px;
    border-radius: 2px;
}

.dot.available { background: #67c23a; }
.dot.locked { background: #e6a23c; }
.dot.sold { background: #c0c4cc; }
.dot.picked { background: #409eff; }

.row {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 6px;
}

.row-label {
    width: 42px;
    font-size: 12px;
    color: #909399;
}

.seat {
    width: 40px;
    height: 34px;
    border: 1px solid #dcdfe6;
    border-radius: 4px;
    background: #fff;
    cursor: pointer;
    font-size: 12px;
    color: #606266;
}

.seat.s-可选 {
    border-color: #b3e19d;
    background: #f0f9eb;
}

.seat.s-锁定,
.seat.s-已售 {
    cursor: not-allowed;
    color: #c0c4cc;
}

.seat.s-锁定 {
    background: #fdf6ec;
    border-color: #f5dab1;
}

.seat.s-已售 {
    background: #f4f4f5;
    border-color: #e9e9eb;
}

.seat.picked {
    background: #409eff;
    border-color: #409eff;
    color: #fff;
    font-weight: 600;
}

.seat:disabled {
    opacity: 1;
}
</style>
