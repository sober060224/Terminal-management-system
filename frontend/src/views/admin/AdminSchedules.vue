<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminScheduleApi, adminRouteApi, adminShipApi } from '../../api'
import {
    arriveAt,
    crossesMidnight,
    effectiveArrive,
    expandDates,
    composeDepartTime,
    validateScheduleForm,
    validateBatchForm
} from './scheduleRules'

// 班次管理（docs/api.md §8.4）
// 列表：GET /admin/schedules?routeId&shipId&dateFrom&dateTo&status&page&size
// 新增/编辑：POST /admin/schedules、PUT /admin/schedules/{id}
// 批量生成：POST /admin/schedules/batch
// 发布/取消：POST /admin/schedules/{id}/publish、POST /admin/schedules/{id}/cancel

const router = useRouter()

const STATUS_OPTIONS = ['待发布', '售票中', '已发船', '已完成', '已取消']

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)

// 筛选用的航线 / 船舶下拉选项（一次取最多 100 条，后端 size 上限）
const routeOptions = ref([])
const shipOptions = ref([])

const filters = reactive({
    routeId: null,
    shipId: null,
    dateRange: [],
    status: ''
})

// 新增 / 编辑班次
const dialogVisible = ref(false)
const dialogMode = ref('create')
const submitting = ref(false)
const form = reactive({
    id: null,
    routeId: null,
    shipId: null,
    departTime: '',
    price: 0
})

// 编辑时记住原发船时间，用来判断「发船时间是否真的改了」
const originalDepartTime = ref('')

// 批量生成
const batchVisible = ref(false)
const batchSubmitting = ref(false)
const batchResult = ref(null)
const batchForm = reactive({
    routeId: null,
    shipId: null,
    dateRange: [],
    departTime: '08:00:00',
    price: 0
})

// 发布/取消共用一个在途标志：连点不会重复发请求、不会弹两次报错
const acting = ref(false)

// 选中的航线对象：算预计到达时间要它的 durationMin
const selectedRoute = computed(() =>
    routeOptions.value.find(r => r.id === form.routeId) || null
)

// 预计到达时间：发船时间 + 航线航程时长（与后端 §8.4.3 同一口径）
const estimatedArrive = computed(() => {
    if (!form.departTime || !selectedRoute.value) return ''
    return arriveAt(form.departTime, selectedRoute.value.durationMin)
})

const estimatedCrosses = computed(() => {
    if (!estimatedArrive.value) return false
    return crossesMidnight(form.departTime, estimatedArrive.value)
})

const batchDates = computed(() => {
    if (!batchForm.dateRange || batchForm.dateRange.length !== 2) return []
    return expandDates(batchForm.dateRange[0], batchForm.dateRange[1])
})

// 批量生成的首日预览：把「日期 + 每日时刻 + 航程」拼出来，提前让人确认排班口径
const batchPreview = computed(() => {
    if (!batchDates.value.length) return null
    const route = routeOptions.value.find(r => r.id === batchForm.routeId)
    const depart = composeDepartTime(batchDates.value[0], batchForm.departTime)
    if (!route || !depart) return null
    const arrive = arriveAt(depart, route.durationMin)
    return {
        depart,
        arrive,
        crosses: crossesMidnight(depart, arrive)
    }
})

async function load() {
    loading.value = true
    try {
        const params = {
            page: page.value,
            size: size.value
        }
        if (filters.routeId) params.routeId = filters.routeId
        if (filters.shipId) params.shipId = filters.shipId
        if (filters.dateRange && filters.dateRange.length === 2) {
            params.dateFrom = filters.dateRange[0]
            params.dateTo = filters.dateRange[1]
        }
        if (filters.status) params.status = filters.status

        const res = await adminScheduleApi.list(params)
        const data = res.data || {}
        rows.value = data.items || []
        total.value = data.total || 0
    } catch (e) {
        ElMessage.error(e.message || '加载班次失败')
    } finally {
        loading.value = false
    }
}

async function loadOptions() {
    try {
        const [r, s] = await Promise.all([
            adminRouteApi.list({ page: 1, size: 100 }),
            adminShipApi.list({ page: 1, size: 100 })
        ])
        routeOptions.value = (r.data && r.data.items) || []
        shipOptions.value = (s.data && s.data.items) || []
    } catch (e) {
        ElMessage.error(e.message || '加载航线/船舶选项失败')
    }
}

function search() {
    page.value = 1
    load()
}

function reset() {
    filters.routeId = null
    filters.shipId = null
    filters.dateRange = []
    filters.status = ''
    page.value = 1
    load()
}

// 改每页条数时 Element Plus 会连带触发 current-change，统一用 change 只加载一次
function onPageQuery(p, s) {
    page.value = p
    size.value = s
    load()
}

function openCreate() {
    dialogMode.value = 'create'
    Object.assign(form, {
        id: null,
        routeId: null,
        shipId: null,
        departTime: '',
        price: 0
    })
    originalDepartTime.value = ''
    dialogVisible.value = true
}

function openEdit(row) {
    dialogMode.value = 'edit'
    Object.assign(form, {
        id: row.id,
        routeId: row.routeId,
        shipId: row.shipId,
        departTime: row.departTime ? row.departTime.slice(0, 19) : '',
        price: row.price
    })
    // 记住原发船时间：§8.4.4 只在「改发船时间」时拦过去时间，没改就不该被前端挡下
    originalDepartTime.value = form.departTime
    dialogVisible.value = true
}

async function submit() {
    const departChanged = dialogMode.value === 'create' ||
        form.departTime !== originalDepartTime.value
    const check = validateScheduleForm({
        routeId: form.routeId,
        shipId: form.shipId,
        departTime: form.departTime,
        price: form.price,
        // 编辑且没改发船时间时，跳过「不能早于当前」这条
        ignorePast: !departChanged
    })
    if (!check.ok) {
        ElMessage.error(check.message)
        return
    }
    submitting.value = true
    try {
        if (dialogMode.value === 'create') {
            await adminScheduleApi.create({
                routeId: form.routeId,
                shipId: form.shipId,
                departTime: form.departTime,
                price: Number(form.price)
            })
            ElMessage.success('新增班次成功，该班次为「待发布」，需手动发布后才能售票')
        } else {
            // §8.4.4 的编辑体只有 shipId / departTime / price：航线不可改
            await adminScheduleApi.update(form.id, {
                shipId: form.shipId,
                departTime: form.departTime,
                price: Number(form.price)
            })
            ElMessage.success('修改班次成功')
        }
        dialogVisible.value = false
        load()
    } catch (e) {
        ElMessage.error(e.message || '保存班次失败')
    } finally {
        submitting.value = false
    }
}

function openBatch() {
    Object.assign(batchForm, {
        routeId: null,
        shipId: null,
        dateRange: [],
        departTime: '08:00:00',
        price: 0
    })
    batchResult.value = null
    batchVisible.value = true
}

async function submitBatch() {
    const check = validateBatchForm({
        routeId: batchForm.routeId,
        shipId: batchForm.shipId,
        dateFrom: batchForm.dateRange && batchForm.dateRange.length === 2 ? batchForm.dateRange[0] : '',
        dateTo: batchForm.dateRange && batchForm.dateRange.length === 2 ? batchForm.dateRange[1] : '',
        departTime: batchForm.departTime,
        price: batchForm.price
    })
    if (!check.ok) {
        ElMessage.error(check.message)
        return
    }
    batchSubmitting.value = true
    try {
        const res = await adminScheduleApi.batchCreate({
            routeId: batchForm.routeId,
            shipId: batchForm.shipId,
            dateFrom: batchForm.dateRange[0],
            dateTo: batchForm.dateRange[1],
            departTime: batchForm.departTime,
            price: Number(batchForm.price)
        })
        batchResult.value = res.data || { created: 0, skipped: [] }
        ElMessage.success(res.message || '批量生成完成')
        load()
    } catch (e) {
        ElMessage.error(e.message || '批量生成失败')
    } finally {
        batchSubmitting.value = false
    }
}

async function publish(row) {
    try {
        await ElMessageBox.confirm(
            `确认发布班次「${row.routeName} ${row.departTime}」？发布后乘客即可购票。`,
            '发布班次',
            { type: 'warning', confirmButtonText: '确认发布', cancelButtonText: '取消' }
        )
    } catch {
        return
    }
    if (acting.value) return
    acting.value = true
    try {
        await adminScheduleApi.publish(row.id)
        ElMessage.success('班次已发布')
        load()
    } catch (e) {
        ElMessage.error(e.message || '发布班次失败')
    } finally {
        acting.value = false
    }
}

async function cancel(row) {
    try {
        await ElMessageBox.confirm(
            `确认取消班次「${row.routeName} ${row.departTime}」？` +
            '取消后将对该班次全部已支付票全额退款，待支付订单将一并取消。',
            '取消班次',
            { type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '返回' }
        )
    } catch {
        return
    }
    if (acting.value) return
    acting.value = true
    try {
        const res = await adminScheduleApi.cancel(row.id)
        const data = res.data || {}
        const refund = `退款 ${data.refundedTickets || 0} 张，共 ${data.refundedAmount || 0} 元，` +
            `取消待支付订单 ${data.cancelledOrders || 0} 个`
        ElMessage.success(`${res.message || '班次已取消'}（${refund}）`)
        load()
    } catch (e) {
        ElMessage.error(e.message || '取消班次失败')
    } finally {
        acting.value = false
    }
}

function openDetail(row) {
    router.push(`/admin/schedules/${row.id}`)
}

// 到达时间：优先用后端给的；跨零点时补 +1 天标记
function arriveText(row) {
    const arrive = effectiveArrive(row.departTime, row.arriveTime)
    if (!arrive) return '—'
    return crossesMidnight(row.departTime, row.arriveTime) ? `${arrive}（+1天）` : arrive
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

onMounted(() => {
    load()
    loadOptions()
})
</script>

<template>
    <div>
        <div class="card search-card">
            <el-form :inline="true" @submit.prevent>
                <el-form-item label="航线">
                    <el-select
                        v-model="filters.routeId"
                        placeholder="全部航线"
                        clearable
                        filterable
                        style="width: 220px"
                    >
                        <el-option
                            v-for="r in routeOptions"
                            :key="r.id"
                            :label="`${r.routeName}（${r.startPort}-${r.endPort}）`"
                            :value="r.id"
                        />
                    </el-select>
                </el-form-item>
                <el-form-item label="船舶">
                    <el-select
                        v-model="filters.shipId"
                        placeholder="全部船舶"
                        clearable
                        filterable
                        style="width: 180px"
                    >
                        <el-option v-for="s in shipOptions" :key="s.id" :label="s.shipName" :value="s.id" />
                    </el-select>
                </el-form-item>
                <el-form-item label="发船日期">
                    <el-date-picker
                        v-model="filters.dateRange"
                        type="daterange"
                        value-format="YYYY-MM-DD"
                        range-separator="至"
                        start-placeholder="开始日期"
                        end-placeholder="结束日期"
                        style="width: 240px"
                    />
                </el-form-item>
                <el-form-item label="状态">
                    <el-select v-model="filters.status" placeholder="全部状态" clearable style="width: 130px">
                        <el-option v-for="s in STATUS_OPTIONS" :key="s" :label="s" :value="s" />
                    </el-select>
                </el-form-item>
                <el-form-item>
                    <el-button type="primary" @click="search">搜索</el-button>
                    <el-button @click="reset">重置</el-button>
                </el-form-item>
            </el-form>
        </div>

        <div class="card">
            <div class="head">
                <h2 class="page-title">班次管理</h2>
                <div class="head-actions">
                    <el-button @click="openBatch">批量生成</el-button>
                    <el-button type="primary" @click="openCreate">新增班次</el-button>
                </div>
            </div>

            <el-table v-loading="loading" :data="rows" border empty-text="暂无班次">
                <el-table-column prop="id" label="ID" width="70" />
                <el-table-column label="航线" min-width="150">
                    <template #default="{ row }">
                        <div>{{ row.routeName }}</div>
                        <div class="muted">{{ row.startPort }} → {{ row.endPort }}</div>
                    </template>
                </el-table-column>
                <el-table-column prop="shipName" label="船舶" width="110" />
                <el-table-column prop="departTime" label="发船时间" width="165" />
                <el-table-column label="到达时间" width="200">
                    <template #default="{ row }">
                        {{ arriveText(row) }}
                    </template>
                </el-table-column>
                <el-table-column prop="price" label="票价" width="90" />
                <el-table-column label="已售/余票" width="110">
                    <template #default="{ row }">
                        {{ row.soldSeats }} / {{ row.remainingSeats }}
                    </template>
                </el-table-column>
                <el-table-column label="状态" width="95">
                    <template #default="{ row }">
                        <el-tag size="small" :type="statusType(row.status)">{{ row.status }}</el-tag>
                    </template>
                </el-table-column>
                <el-table-column label="操作" width="210" fixed="right">
                    <template #default="{ row }">
                        <el-button
                            v-if="row.status === '待发布'"
                            size="small"
                            link
                            type="success"
                            @click="publish(row)"
                        >发布</el-button>
                        <el-button
                            v-if="row.status === '待发布' || row.status === '售票中'"
                            size="small"
                            link
                            type="danger"
                            @click="cancel(row)"
                        >取消</el-button>
                        <el-button size="small" link type="primary" @click="openDetail(row)">详情</el-button>
                        <el-button
                            v-if="row.status === '待发布' || row.status === '售票中'"
                            size="small"
                            link
                            @click="openEdit(row)"
                        >编辑</el-button>
                    </template>
                </el-table-column>
            </el-table>

            <el-pagination
                v-model:current-page="page"
                v-model:page-size="size"
                class="pager"
                layout="total, sizes, prev, pager, next"
                :total="total"
                :page-sizes="[10, 20, 50]"
                @change="onPageQuery"
            />
        </div>

        <!-- 新增 / 编辑班次 -->
        <el-dialog
            v-model="dialogVisible"
            :title="dialogMode === 'create' ? '新增班次' : '编辑班次'"
            width="560px"
            :close-on-click-modal="false"
        >
            <el-form :model="form" label-width="100px">
                <el-form-item label="航线" required>
                    <el-select
                        v-model="form.routeId"
                        placeholder="请选择航线"
                        filterable
                        :disabled="dialogMode === 'edit'"
                        style="width: 100%"
                    >
                        <el-option
                            v-for="r in routeOptions"
                            :key="r.id"
                            :label="`${r.routeName}（${r.startPort}-${r.endPort}，航程 ${r.durationMin} 分钟）`"
                            :value="r.id"
                        />
                    </el-select>
                    <div v-if="dialogMode === 'edit'" class="muted arrive-tip">
                        班次所属航线不可修改（§8.4.4 编辑接口只接受船舶、发船时间、票价）
                    </div>
                </el-form-item>
                <el-form-item label="船舶" required>
                    <el-select v-model="form.shipId" placeholder="请选择船舶" filterable style="width: 100%">
                        <el-option
                            v-for="s in shipOptions"
                            :key="s.id"
                            :label="`${s.shipName}（${s.shipType}，${s.totalSeats} 座）`"
                            :value="s.id"
                        />
                    </el-select>
                </el-form-item>
                <el-form-item label="发船时间" required>
                    <el-date-picker
                        v-model="form.departTime"
                        type="datetime"
                        value-format="YYYY-MM-DDTHH:mm:ss"
                        placeholder="选择发船时间"
                        style="width: 100%"
                    />
                </el-form-item>
                <el-form-item label="预计到达">
                    <span v-if="estimatedArrive">
                        {{ estimatedArrive }}
                        <el-tag v-if="estimatedCrosses" size="small" type="warning">+1天</el-tag>
                        <span class="muted arrive-tip">
                            （发船 + {{ selectedRoute.durationMin }} 分钟，跨零点班次由服务端自动加 1 天）
                        </span>
                    </span>
                    <span v-else class="muted">选择航线和发船时间后自动计算</span>
                </el-form-item>
                <el-form-item label="票价" required>
                    <el-input-number v-model="form.price" :min="0" :precision="2" :step="10" placeholder="票价" />
                    <span class="muted unit">元（不能为负）</span>
                </el-form-item>
            </el-form>
            <template #footer>
                <el-button @click="dialogVisible = false">取消</el-button>
                <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
            </template>
        </el-dialog>

        <!-- 批量生成班次 -->
        <el-dialog
            v-model="batchVisible"
            title="批量生成班次"
            width="640px"
            :close-on-click-modal="false"
        >
            <el-form :model="batchForm" label-width="100px">
                <el-form-item label="航线" required>
                    <el-select v-model="batchForm.routeId" placeholder="请选择航线" filterable style="width: 100%">
                        <el-option
                            v-for="r in routeOptions"
                            :key="r.id"
                            :label="`${r.routeName}（航程 ${r.durationMin} 分钟）`"
                            :value="r.id"
                        />
                    </el-select>
                </el-form-item>
                <el-form-item label="船舶" required>
                    <el-select v-model="batchForm.shipId" placeholder="请选择船舶" filterable style="width: 100%">
                        <el-option
                            v-for="s in shipOptions"
                            :key="s.id"
                            :label="`${s.shipName}（${s.shipType}，${s.totalSeats} 座）`"
                            :value="s.id"
                        />
                    </el-select>
                </el-form-item>
                <el-form-item label="日期区间" required>
                    <el-date-picker
                        v-model="batchForm.dateRange"
                        type="daterange"
                        value-format="YYYY-MM-DD"
                        range-separator="至"
                        start-placeholder="开始日期"
                        end-placeholder="结束日期"
                        style="width: 100%"
                    />
                    <div class="muted arrive-tip">
                        两端都含，最多 62 天；共 {{ batchDates.length }} 天
                    </div>
                </el-form-item>
                <el-form-item label="每日发船时刻" required>
                    <el-time-picker
                        v-model="batchForm.departTime"
                        value-format="HH:mm:ss"
                        format="HH:mm:ss"
                        placeholder="选择每日发船时刻"
                        style="width: 100%"
                    />
                </el-form-item>
                <el-form-item label="票价" required>
                    <el-input-number v-model="batchForm.price" :min="0" :precision="2" :step="10" placeholder="票价" />
                    <span class="muted unit">元（不能为负）</span>
                </el-form-item>
            </el-form>

            <div v-if="batchResult" class="batch-result">
                <div class="batch-summary">
                    成功生成 <strong>{{ batchResult.created }}</strong> 个班次
                    <template v-if="batchResult.skipped && batchResult.skipped.length">
                        ，跳过 {{ batchResult.skipped.length }} 天
                    </template>
                </div>
                <el-table
                    v-if="batchResult.skipped && batchResult.skipped.length"
                    :data="batchResult.skipped"
                    size="small"
                    border
                    empty-text="没有被跳过的日期"
                >
                    <el-table-column prop="date" label="日期" width="130" />
                    <el-table-column prop="reason" label="跳过原因" min-width="220" />
                </el-table>
                <div class="muted arrive-tip">生成的班次均为「待发布」，需要到列表里逐个发布。</div>
            </div>

            <template #footer>
                <el-button @click="batchVisible = false">关闭</el-button>
                <el-button type="primary" :loading="batchSubmitting" @click="submitBatch">开始生成</el-button>
            </template>
        </el-dialog>
    </div>
</template>

<style scoped>
.search-card {
    margin-bottom: 14px;
}

.search-card :deep(.el-form-item) {
    margin-bottom: 0;
}

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

.pager {
    margin-top: 14px;
    justify-content: flex-end;
}

.unit {
    margin-left: 8px;
}

.arrive-tip {
    margin-top: 4px;
    display: block;
}

.batch-result {
    margin-top: 6px;
    padding-top: 12px;
    border-top: 1px solid #ebeef5;
}

.batch-summary {
    margin-bottom: 8px;
    font-size: 13px;
    color: #606266;
}
</style>