<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import OrderList from '../../components/OrderList.vue'
import { scheduleApi, sellerOrderApi } from '../../api'

// 管理员的 /api/seller/orders 返回全部订单，带筛选参数即可。
const schedules = ref([])
const dateRange = ref([])
const scheduleId = ref(null)
const passengerName = ref('')
const params = ref(null)

async function loadSchedules() {
    try {
        const res = await scheduleApi.list()
        schedules.value = res.data || []
    } catch (e) {
        ElMessage.error(e.message || '加载班次失败')
    }
}

function search() {
    const p = {}
    if (dateRange.value && dateRange.value.length === 2) {
        p.dateFrom = dateRange.value[0]
        p.dateTo = dateRange.value[1]
    }
    if (scheduleId.value) {
        p.scheduleId = scheduleId.value
    }
    if (passengerName.value.trim()) {
        p.passengerName = passengerName.value.trim()
    }
    params.value = p
}

function reset() {
    dateRange.value = []
    scheduleId.value = null
    passengerName.value = ''
    params.value = {}
}

onMounted(loadSchedules)
</script>

<template>
    <div>
        <div class="card search-card">
            <el-form :inline="true">
                <el-form-item label="发船日期">
                    <el-date-picker
                        v-model="dateRange"
                        type="daterange"
                        value-format="YYYY-MM-DD"
                        range-separator="至"
                        start-placeholder="开始日期"
                        end-placeholder="结束日期"
                    />
                </el-form-item>
                <el-form-item label="班次">
                    <el-select v-model="scheduleId" placeholder="全部班次" clearable style="width: 240px">
                        <el-option
                            v-for="s in schedules"
                            :key="s.id"
                            :label="`${s.routeName} ${s.departTime}`"
                            :value="s.id"
                        />
                    </el-select>
                </el-form-item>
                <el-form-item label="乘客姓名">
                    <el-input v-model="passengerName" placeholder="模糊搜索" clearable />
                </el-form-item>
                <el-form-item>
                    <el-button type="primary" @click="search">搜索</el-button>
                    <el-button @click="reset">重置</el-button>
                </el-form-item>
            </el-form>
        </div>

        <OrderList
            title="全部订单"
            :api="sellerOrderApi"
            :params="params"
            detail-prefix="/admin/orders"
        />
    </div>
</template>

<style scoped>
.search-card {
    margin-bottom: 14px;
}

.search-card :deep(.el-form-item) {
    margin-bottom: 0;
}
</style>
