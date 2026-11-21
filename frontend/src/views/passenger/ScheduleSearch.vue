<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { scheduleApi } from '../../api'

const router = useRouter()
const schedules = ref([])
const loading = ref(false)

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

onMounted(load)
</script>

<template>
    <div class="card">
        <div class="head">
            <h2 class="page-title">查询班次</h2>
            <el-button :loading="loading" @click="load">刷新</el-button>
        </div>

        <el-empty v-if="!loading && !schedules.length" description="当前没有在售票的班次" />

        <el-table :data="schedules" v-loading="loading" border>
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
