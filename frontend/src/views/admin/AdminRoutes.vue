<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminRouteApi } from '../../api'

// 航线管理（docs/api.md §8.2）
// 列表：GET /admin/routes?page&size&name&startPort&endPort
// 详情：GET /admin/routes/{id}（data 里带该航线的全部班次）

const STATUS_OPTIONS = ['正常', '停运']

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)

const filters = reactive({ name: '', startPort: '', endPort: '' })

const dialogVisible = ref(false)
const dialogMode = ref('create') // create | edit
const submitting = ref(false)
const formRef = ref(null)
const form = reactive({
    id: null,
    routeName: '',
    startPort: '',
    endPort: '',
    durationMin: 120,
    description: '',
    status: '正常'
})

const rules = {
    routeName: [
        { required: true, message: '航线名称不能为空', trigger: 'blur' },
        { max: 100, message: '航线名称最多 100 个字符', trigger: 'blur' }
    ],
    startPort: [
        { required: true, message: '出发港不能为空', trigger: 'blur' },
        { max: 50, message: '出发港最多 50 个字符', trigger: 'blur' }
    ],
    endPort: [
        { required: true, message: '到达港不能为空', trigger: 'blur' },
        { max: 50, message: '到达港最多 50 个字符', trigger: 'blur' }
    ],
    durationMin: [
        { required: true, message: '航程时长不能为空', trigger: 'blur' },
        {
            type: 'integer', min: 1, max: 1440,
            message: '航程时长必须在 1–1440 分钟之间', trigger: 'blur'
        }
    ],
    description: [{ max: 200, message: '描述最多 200 个字符', trigger: 'blur' }]
}

// 「查看班次」抽屉
const drawerVisible = ref(false)
const detail = ref(null)
const detailLoading = ref(false)

async function load() {
    loading.value = true
    try {
        const res = await adminRouteApi.list({
            page: page.value,
            size: size.value,
            name: filters.name.trim(),
            startPort: filters.startPort.trim(),
            endPort: filters.endPort.trim()
        })
        const data = res.data || {}
        rows.value = data.items || []
        total.value = data.total || 0
    } catch (e) {
        ElMessage.error(e.message || '加载航线失败')
    } finally {
        loading.value = false
    }
}

function search() {
    page.value = 1
    load()
}

function reset() {
    filters.name = ''
    filters.startPort = ''
    filters.endPort = ''
    page.value = 1
    load()
}

// 用 @change 统一处理：改每页条数时 Element Plus 会连带触发 current-change，
// 两个事件分别发请求就会重复加载一次，change 只发一次。
function onPageQuery(p, s) {
    page.value = p
    size.value = s
    load()
}

function openCreate() {
    dialogMode.value = 'create'
    Object.assign(form, {
        id: null,
        routeName: '',
        startPort: '',
        endPort: '',
        durationMin: 120,
        description: '',
        status: '正常'
    })
    dialogVisible.value = true
    if (formRef.value) formRef.value.clearValidate()
}

function openEdit(row) {
    dialogMode.value = 'edit'
    Object.assign(form, {
        id: row.id,
        routeName: row.routeName,
        startPort: row.startPort,
        endPort: row.endPort,
        durationMin: row.durationMin,
        description: row.description || '',
        status: row.status || '正常'
    })
    dialogVisible.value = true
    if (formRef.value) formRef.value.clearValidate()
}

async function submit() {
    const valid = await formRef.value.validate().catch(() => false)
    if (!valid) return
    submitting.value = true
    try {
        const payload = {
            routeName: form.routeName.trim(),
            startPort: form.startPort.trim(),
            endPort: form.endPort.trim(),
            durationMin: Number(form.durationMin),
            description: form.description.trim() ? form.description.trim() : null,
            status: form.status
        }
        if (dialogMode.value === 'create') {
            await adminRouteApi.create(payload)
            ElMessage.success('新增航线成功')
        } else {
            await adminRouteApi.update(form.id, payload)
            ElMessage.success('修改航线成功')
        }
        dialogVisible.value = false
        load()
    } catch (e) {
        ElMessage.error(e.message || '保存航线失败')
    } finally {
        submitting.value = false
    }
}

async function remove(row) {
    try {
        await ElMessageBox.confirm(
            `确认删除航线「${row.routeName}」？删除后不可恢复。`,
            '删除航线',
            { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
        )
    } catch {
        return
    }
    try {
        await adminRouteApi.remove(row.id)
        ElMessage.success('删除航线成功')
        // 删掉当前页最后一条时回退一页，避免停在空白页
        if (rows.value.length === 1 && page.value > 1) page.value -= 1
        load()
    } catch (e) {
        // 后端的 409 message（航线名称已存在 / 还有未完成班次 等）直接展示
        ElMessage.error(e.message || '删除航线失败')
    }
}

async function openSchedules(row) {
    drawerVisible.value = true
    detailLoading.value = true
    detail.value = null
    try {
        const res = await adminRouteApi.detail(row.id)
        detail.value = res.data || null
    } catch (e) {
        ElMessage.error(e.message || '加载航线详情失败')
    } finally {
        detailLoading.value = false
    }
}

function statusType(status) {
    return { '正常': 'success', '停运': 'info' }[status] || ''
}

onMounted(load)
</script>

<template>
    <div>
        <div class="card search-card">
            <el-form :inline="true" @submit.prevent>
                <el-form-item label="航线名称">
                    <el-input
                        v-model="filters.name"
                        placeholder="模糊搜索"
                        clearable
                        style="width: 180px"
                        @keyup.enter="search"
                    />
                </el-form-item>
                <el-form-item label="出发港">
                    <el-input
                        v-model="filters.startPort"
                        placeholder="模糊搜索"
                        clearable
                        style="width: 160px"
                        @keyup.enter="search"
                    />
                </el-form-item>
                <el-form-item label="到达港">
                    <el-input
                        v-model="filters.endPort"
                        placeholder="模糊搜索"
                        clearable
                        style="width: 160px"
                        @keyup.enter="search"
                    />
                </el-form-item>
                <el-form-item>
                    <el-button type="primary" @click="search">搜索</el-button>
                    <el-button @click="reset">重置</el-button>
                </el-form-item>
            </el-form>
        </div>

        <div class="card">
            <div class="head">
                <h2 class="page-title">航线管理</h2>
                <el-button type="primary" @click="openCreate">新增航线</el-button>
            </div>

            <el-table v-loading="loading" :data="rows" border empty-text="暂无航线">
                <el-table-column prop="id" label="ID" width="70" />
                <el-table-column prop="routeName" label="航线名称" min-width="140" />
                <el-table-column prop="startPort" label="出发港" width="120" />
                <el-table-column prop="endPort" label="到达港" width="120" />
                <el-table-column prop="durationMin" label="航程（分钟）" width="110" />
                <el-table-column label="描述" min-width="160">
                    <template #default="{ row }">
                        <span class="muted">{{ row.description || '—' }}</span>
                    </template>
                </el-table-column>
                <el-table-column label="状态" width="90">
                    <template #default="{ row }">
                        <el-tag size="small" :type="statusType(row.status)">{{ row.status }}</el-tag>
                    </template>
                </el-table-column>
                <el-table-column label="操作" width="220" fixed="right">
                    <template #default="{ row }">
                        <el-button size="small" link type="primary" @click="openEdit(row)">编辑</el-button>
                        <el-button size="small" link @click="openSchedules(row)">查看班次</el-button>
                        <el-button size="small" link type="danger" @click="remove(row)">删除</el-button>
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

        <!-- 新增 / 编辑航线 -->
        <el-dialog
            v-model="dialogVisible"
            :title="dialogMode === 'create' ? '新增航线' : '编辑航线'"
            width="560px"
            :close-on-click-modal="false"
        >
            <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
                <el-form-item label="航线名称" prop="routeName">
                    <el-input v-model="form.routeName" placeholder="如：广州-香港" maxlength="100" />
                </el-form-item>
                <el-form-item label="出发港" prop="startPort">
                    <el-input v-model="form.startPort" placeholder="如：广州港" maxlength="50" />
                </el-form-item>
                <el-form-item label="到达港" prop="endPort">
                    <el-input v-model="form.endPort" placeholder="如：香港港" maxlength="50" />
                </el-form-item>
                <el-form-item label="航程时长" prop="durationMin">
                    <el-input-number v-model="form.durationMin" :min="1" :max="1440" :step="10" />
                    <span class="muted unit">分钟（1–1440）</span>
                </el-form-item>
                <el-form-item label="描述" prop="description">
                    <el-input
                        v-model="form.description"
                        type="textarea"
                        :rows="3"
                        maxlength="200"
                        show-word-limit
                        placeholder="航线说明（选填）"
                    />
                </el-form-item>
                <el-form-item label="状态" prop="status">
                    <el-radio-group v-model="form.status">
                        <el-radio v-for="s in STATUS_OPTIONS" :key="s" :value="s">{{ s }}</el-radio>
                    </el-radio-group>
                </el-form-item>
            </el-form>
            <template #footer>
                <el-button @click="dialogVisible = false">取消</el-button>
                <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
            </template>
        </el-dialog>

        <!-- 查看该航线的班次 -->
        <el-drawer v-model="drawerVisible" title="航线班次" size="60%">
            <div v-loading="detailLoading">
                <el-descriptions v-if="detail" :column="2" border size="small">
                    <el-descriptions-item label="航线名称">{{ detail.routeName }}</el-descriptions-item>
                    <el-descriptions-item label="状态">
                        <el-tag size="small" :type="statusType(detail.status)">{{ detail.status }}</el-tag>
                    </el-descriptions-item>
                    <el-descriptions-item label="出发港">{{ detail.startPort }}</el-descriptions-item>
                    <el-descriptions-item label="到达港">{{ detail.endPort }}</el-descriptions-item>
                    <el-descriptions-item label="航程时长">{{ detail.durationMin }} 分钟</el-descriptions-item>
                    <el-descriptions-item label="描述">{{ detail.description || '—' }}</el-descriptions-item>
                </el-descriptions>

                <div class="section-title">班次列表（共 {{ (detail && detail.schedules) ? detail.schedules.length : 0 }} 个）</div>
                <el-table
                    :data="(detail && detail.schedules) || []"
                    border
                    size="small"
                    empty-text="该航线暂无班次"
                >
                    <el-table-column prop="id" label="ID" width="70" />
                    <el-table-column prop="shipName" label="船舶" width="110" />
                    <el-table-column prop="departTime" label="发船时间" width="160" />
                    <el-table-column prop="arriveTime" label="到达时间" width="160" />
                    <el-table-column prop="price" label="票价" width="90" />
                    <el-table-column prop="soldSeats" label="已售" width="80" />
                    <el-table-column prop="remainingSeats" label="余票" width="80" />
                    <el-table-column label="状态" width="90">
                        <template #default="{ row }">
                            <el-tag size="small">{{ row.status }}</el-tag>
                        </template>
                    </el-table-column>
                </el-table>
            </div>
        </el-drawer>
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

.pager {
    margin-top: 14px;
    justify-content: flex-end;
}

.section-title {
    margin: 16px 0 8px;
    font-size: 14px;
    color: #606266;
}

.unit {
    margin-left: 8px;
}
</style>
