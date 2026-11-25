<script setup>
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

// 管理后台首页：M2 起航线/船舶/班次三个模块已可用，直接点卡片进对应页面。
const router = useRouter()

const cards = [
    { title: '航线管理', desc: '维护航线名称、港口、航程时长与状态', icon: 'Guide', path: '/admin/routes' },
    { title: '船舶管理', desc: '维护船名、座位数、船型与状态', icon: 'Ship', path: '/admin/ships' },
    { title: '班次管理', desc: '排班、批量生成、发布与取消班次', icon: 'Calendar', path: '/admin/schedules' },
    // 统计报表属于后续模块，先保留占位入口
    { title: '统计报表', desc: '营收与客流统计（后续模块开放）', icon: 'DataLine', path: '' }
]

function go(card) {
    if (!card.path) {
        ElMessage.info('统计报表模块尚未开放')
        return
    }
    router.push(card.path)
}
</script>

<template>
    <div class="card">
        <h2 class="page-title">管理后台</h2>
        <p class="muted">
            航线与班次、船舶、订单在此统一管理；统计数据在后续模块开放。
        </p>
        <el-row :gutter="12" class="cards">
            <el-col v-for="c in cards" :key="c.title" :span="6">
                <el-card
                    shadow="hover"
                    class="entry"
                    :class="{ disabled: !c.path }"
                    @click="go(c)"
                >
                    <div class="entry-head">
                        <el-icon class="entry-icon"><component :is="c.icon"/></el-icon>
                        <span class="entry-title">{{ c.title }}</span>
                    </div>
                    <div class="muted entry-desc">{{ c.desc }}</div>
                </el-card>
            </el-col>
        </el-row>
    </div>
</template>

<style scoped>
.cards {
    margin-top: 12px;
}

.entry {
    cursor: pointer;
    transition: border-color 0.2s;
}

.entry:hover {
    border-color: #409eff;
}

.entry.disabled {
    cursor: default;
    opacity: 0.65;
}

.entry.disabled:hover {
    border-color: #e4e7ed;
}

.entry-head {
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: 600;
}

.entry-icon {
    color: #409eff;
}

.entry-desc {
    margin-top: 8px;
    line-height: 1.6;
}
</style>