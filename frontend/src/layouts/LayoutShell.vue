<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore, ROLE_NAMES } from '../store/user'

// 三端共用这套外壳，差别只在菜单和配色：role 由各自的 layout 传进来。
const props = defineProps({
    role: { type: String, required: true }
})

const store = useUserStore()
const route = useRoute()
const router = useRouter()

const menus = computed(() => {
    if (props.role === 'ADMIN') {
        return [
            { path: '/admin', label: '管理后台', icon: 'Setting' },
            { path: '/seller', label: '售票工作台', icon: 'Tickets' },
            { path: '/', label: '乘客服务', icon: 'Van' }
        ]
    }
    if (props.role === 'SELLER') {
        return [{ path: '/seller', label: '售票工作台', icon: 'Tickets' }]
    }
    return [{ path: '/', label: '乘客服务', icon: 'Van' }]
})

async function handleLogout() {
    await store.logout()
    router.push('/login')
}

const displayName = computed(() =>
    (store.profile && store.profile.name) || (store.profile && store.profile.username) || '访客'
)
</script>

<template>
    <el-container class="layout" :class="'role-' + props.role.toLowerCase()">
        <el-aside width="210px" class="aside">
            <div class="brand">
                <div class="brand-title">轮渡码头</div>
                <div class="brand-sub">{{ ROLE_NAMES[props.role] }}</div>
            </div>
            <el-menu
                :default-active="route.path"
                router
                class="menu"
                background-color="transparent"
                text-color="#dbe4f0"
                active-text-color="#ffffff"
            >
                <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">
                    <el-icon><component :is="m.icon"/></el-icon>
                    <span>{{ m.label }}</span>
                </el-menu-item>
            </el-menu>
        </el-aside>

        <el-container>
            <el-header class="header">
                <div class="crumb">{{ route.meta.title || '首页' }}</div>
                <el-dropdown @command="handleLogout">
                    <span class="user">
                        {{ displayName }}
                        <el-tag size="small" type="info">{{ ROLE_NAMES[store.role] }}</el-tag>
                        <el-icon><ArrowDown/></el-icon>
                    </span>
                    <template #dropdown>
                        <el-dropdown-menu>
                            <el-dropdown-item command="logout">退出登录</el-dropdown-item>
                        </el-dropdown-menu>
                    </template>
                </el-dropdown>
            </el-header>

            <el-main class="main">
                <router-view/>
            </el-main>
        </el-container>
    </el-container>
</template>

<style scoped>
.layout {
    height: 100vh;
}

.aside {
    background: #1f2d3d;
    color: #fff;
}

.role-passenger .aside {
    background: #17516b;
}

.role-seller .aside {
    background: #2b3a4a;
}

.brand {
    padding: 18px 16px;
    border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.brand-title {
    font-size: 16px;
    font-weight: 600;
}

.brand-sub {
    font-size: 12px;
    opacity: 0.6;
}

.menu {
    border-right: none;
}

.header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    background: #fff;
    border-bottom: 1px solid #e6e8eb;
}

.crumb {
    font-size: 15px;
    font-weight: 500;
    color: #303133;
}

.user {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    cursor: pointer;
    color: #303133;
}

.main {
    background: #f5f7fa;
}
</style>
