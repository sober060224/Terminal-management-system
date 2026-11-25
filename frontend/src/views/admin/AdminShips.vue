<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminShipApi } from '../../api'

// 船舶管理（docs/api.md §8.3）
// 列表：GET /admin/ships?page&size&name
// 新增/编辑：POST /admin/ships、PUT /admin/ships/{id}；删除：DELETE /admin/ships/{id}

const SHIP_TYPES = ['普通船', '高速船', '豪华船']
const STATUS_OPTIONS = ['正常', '维修', '停用']

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)

const filters = reactive({ name: '' })

const dialogVisible = ref(false)
const dialogMode = ref('create')
const submitting = ref(false)
const formRef = ref(null)
const form = reactive({
    id: null,
    shipName: '',
    totalSeats: 120,
    shipType: '普通船',
    description: '',
    status: '正常'
})

const rules = {
    shipName: [
        { required: true, message: '船舶名称不能为空', trigger: 'blur' },
        { max: 100, message: '船舶名称最多 100 个字符', trigger: 'blur' }
    ],
    totalSeats: [
        { required: true, message: '总座位数不能为空', trigger: 'blur' },
        {
            type: 'integer', min: 1, max: 500,
            message: '总座位数必须在 1–500 之间', trigger: 'blur'
        }
    ],
    shipType: [
        { required: true, message: '请选择船舶类型', trigger: 'change' }
    ],
    description: [{ max: 200, message: '描述最多 200 个字符', trigger: 'blur' }]
}

async function load() {
    loading.value = true
    try {
        const res = await adminShipApi.list({
            page: page.value,
            size: size.value,
            name: filters.name.trim()
        })
        const data = res.data || {}
        rows.value = data.items || []
        total.value = data.total || 0
    } catch (e) {
        ElMessage.error(e.message || '加载船舶失败')
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
        shipName: '',
        totalSeats: 120,
        shipType: '普通船',
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
        shipName: row.shipName,
        totalSeats: row.totalSeats,
        shipType: row.shipType,
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
            shipName: form.shipName.trim(),
            totalSeats: Number(form.totalSeats),
            shipType: form.shipType,
            description: form.description.trim() ? form.description.trim() : null,
            status: form.status
        }
        if (dialogMode.value === 'create') {
            await adminShipApi.create(payload)
            ElMessage.success('新增船舶成功')
        } else {
            await adminShipApi.update(form.id, payload)
            ElMessage.success('修改船舶成功')
        }
        dialogVisible.value = false
        load()
    } catch (e) {
        ElMessage.error(e.message || '保存船舶失败')
    } finally {
        submitting.value = false
    }
}

async function remove(row) {
    try {
        await ElMessageBox.confirm(
            `确认删除船舶「${row.shipName}」？删除后不可恢复。`,
            '删除船舶',
            { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
        )
    } catch {
        return
    }
    try {
        await adminShipApi.remove(row.id)
        ElMessage.success('删除船舶成功')
        if (rows.value.length === 1 && page.value > 1) page.value -= 1
        load()
    } catch (e) {
        // 后端 409（船名重复 / 还有未完成班次 / 有历史班次）message 直接展示
        ElMessage.error(e.message || '删除船舶失败')
    }
}

function statusType(status) {
    return { '正常': 'success', '维修': 'warning', '停用': 'info' }[status] || ''
}

onMounted(load)
</script>

<template>
    <div>
        <div class="card search-card">
            <el-form :inline="true" @submit.prevent>
                <el-form-item label="船名">
                    <el-input
                        v-model="filters.name"
                        placeholder="模糊搜索"
                        clearable
                        style="width: 200px"
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
                <h2 class="page-title">船舶管理</h2>
                <el-button type="primary" @click="openCreate">新增船舶</el-button>
            </div>

            <el-table v-loading="loading" :data="rows" border empty-text="暂无船舶">
                <el-table-column prop="id" label="ID" width="70" />
                <el-table-column prop="shipName" label="船名" min-width="130" />
                <el-table-column prop="totalSeats" label="总座位数" width="100" />
                <el-table-column prop="shipType" label="船型" width="100" />
                <el-table-column label="描述" min-width="180">
                    <template #default="{ row }">
                        <span class="muted">{{ row.description || '—' }}</span>
                    </template>
                </el-table-column>
                <el-table-column label="状态" width="90">
                    <template #default="{ row }">
                        <el-tag size="small" :type="statusType(row.status)">{{ row.status }}</el-tag>
                    </template>
                </el-table-column>
                <el-table-column label="操作" width="140" fixed="right">
                    <template #default="{ row }">
                        <el-button size="small" link type="primary" @click="openEdit(row)">编辑</el-button>
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

        <el-dialog
            v-model="dialogVisible"
            :title="dialogMode === 'create' ? '新增船舶' : '编辑船舶'"
            width="560px"
            :close-on-click-modal="false"
        >
            <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
                <el-form-item label="船名" prop="shipName">
                    <el-input v-model="form.shipName" placeholder="如：粤港01" maxlength="100" />
                </el-form-item>
                <el-form-item label="总座位数" prop="totalSeats">
                    <el-input-number v-model="form.totalSeats" :min="1" :max="500" :step="10" />
                    <span class="muted unit">个（1–500）</span>
                </el-form-item>
                <el-form-item label="船型" prop="shipType">
                    <el-select v-model="form.shipType" placeholder="请选择船型" style="width: 100%">
                        <el-option v-for="t in SHIP_TYPES" :key="t" :label="t" :value="t" />
                    </el-select>
                </el-form-item>
                <el-form-item label="描述" prop="description">
                    <el-input
                        v-model="form.description"
                        type="textarea"
                        :rows="3"
                        maxlength="200"
                        show-word-limit
                        placeholder="船舶说明（选填）"
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

.unit {
    margin-left: 8px;
}
</style>
