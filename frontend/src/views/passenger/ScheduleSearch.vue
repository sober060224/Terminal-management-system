<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { scheduleApi } from '../../api'

const router = useRouter()
const schedules = ref([])
const loading = ref(false)
const startPort = ref('')
const endPort = ref('')
const departDate = ref('')

const startPorts = computed(() => [...new Set(schedules.value.map(s => s.startPort).filter(Boolean))])
const endPorts = computed(() => [...new Set(schedules.value.map(s => s.endPort).filter(Boolean))])

const filtered = computed(() => schedules.value.filter(s =>
    (!startPort.value || s.startPort === startPort.value)
    && (!endPort.value || s.endPort === endPort.value)
    && (!departDate.value || (s.departTime || '').slice(0, 10) === departDate.value)
))

const emptyText = computed(() =>
    schedules.value.length ? '没有符合条件的班次' : '当前没有在售票的班次'
)

async function load() {
    loading.value = true
    try {
        const res = await scheduleApi.list()
        schedules.value = res.data || []
    } catch (e) {
        ElMessage.error(e.message || '加载班次失败')
    } finally {
        loading.value = false
    }
}

function buy(s) {
    router.push(`/schedule/${s.id}`)
}

function reset() {
    startPort.value = ''
    endPort.value = ''
    departDate.value = ''
}

onMounted(load)
</script>

<template>
    <div class="card">
        <div class="head">
            <h2 class="page-title">查询班次</h2>
            <el-button :loading="loading" @click="load">刷新</el-button>
        </div>

        <el-form :inline="true" style="margin-bottom: 14px">
            <el-form-item label="出发港口">
                <el-select v-model="startPort" placeholder="全部" clearable style="width: 160px">
                    <el-option v-for="p in startPorts" :key="p" :label="p" :value="p" />
                </el-select>
            </el-form-item>
            <el-form-item label="到达港口">
                <el-select v-model="endPort" placeholder="全部" clearable style="width: 160px">
                    <el-option v-for="p in endPorts" :key="p" :label="p" :value="p" />
                </el-select>
            </el-form-item>
            <el-form-item label="出发日期">
                <el-date-picker v-model="departDate" type="date" value-format="YYYY-MM-DD" placeholder="全部" clearable />
            </el-form-item>
            <el-form-item>
                <el-button @click="reset">重置</el-button>
            </el-form-item>
        </el-form>

        <el-empty v-if="!loading && !filtered.length" :description="emptyText" />

        <el-table :data="filtered" v-loading="loading" border>
            <el-table-column prop="routeName" label="航线" min-width="120" />
            <el-table-column label="出发 → 到达" min-width="160">
                <template #default="{ row }">
                    {{ row.startPort }} → {{ row.endPort }}
                </template>
            </el-table-column>
            <el-table-column prop="shipName" label="船舶" min-width="100" />
            <el-table-column prop="departTime" label="发船时间" min-width="160" />
            <el-table-column prop="price" label="票价" width="80" />
            <el-table-column prop="remainingSeats" label="余票" width="80" />
            <el-table-column label="操作" width="100" fixed="right">
                <template #default="{ row }">
                    <el-button type="primary" link @click="buy(row)">购票</el-button>
                </template>
            </el-table-column>
        </el-table>
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
