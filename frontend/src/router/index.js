import { createRouter, createWebHashHistory } from 'vue-router'
import { useUserStore } from '../store/user'
import { ElMessage } from 'element-plus'

const routes = [
    {
        path: '/login',
        name: 'login',
        component: () => import('../views/LoginView.vue'),
        meta: { public: true, title: '登录' }
    },
    {
        path: '/register',
        name: 'register',
        component: () => import('../views/RegisterView.vue'),
        meta: { public: true, title: '注册' }
    },
    {
        // 乘客端首页就是根路径 '/'（PLAN.md 与 docs/api.md 都按这个约定）
        path: '/',
        component: () => import('../layouts/PassengerLayout.vue'),
        children: [
            {
                path: '',
                name: 'home',
                component: () => import('../views/passenger/ScheduleSearch.vue'),
                meta: { roles: ['PASSENGER', 'ADMIN'], title: '查询班次' }
            },
            {
                path: 'my-orders',
                name: 'my-orders',
                component: () => import('../views/passenger/MyOrders.vue'),
                meta: { roles: ['PASSENGER', 'ADMIN'], title: '我的船票' }
            },
            {
                path: 'schedule/:id',
                name: 'seat-select',
                component: () => import('../views/passenger/SeatSelect.vue'),
                meta: { roles: ['PASSENGER', 'ADMIN'], title: '选座购票' }
            },
            {
                path: 'orders/:id',
                name: 'order-detail',
                component: () => import('../views/OrderDetail.vue'),
                meta: { roles: ['PASSENGER', 'ADMIN'], title: '订单详情' }
            }
        ]
    },
    {
        path: '/admin',
        component: () => import('../layouts/AdminLayout.vue'),
        children: [
            {
                path: '',
                name: 'admin',
                component: () => import('../views/admin/AdminHome.vue'),
                meta: { roles: ['ADMIN'], title: '管理后台' }
            },
            {
                path: 'orders',
                name: 'admin-orders',
                component: () => import('../views/admin/AdminOrders.vue'),
                meta: { roles: ['ADMIN'], title: '全部订单' }
            },
            {
                path: 'orders/:id',
                name: 'admin-order-detail',
                component: () => import('../views/OrderDetail.vue'),
                meta: { roles: ['ADMIN'], title: '订单详情' }
            }
        ]
    },
    {
        path: '/seller',
        component: () => import('../layouts/SellerLayout.vue'),
        children: [
            {
                path: '',
                name: 'seller',
                component: () => import('../views/seller/SellerHome.vue'),
                meta: { roles: ['SELLER', 'ADMIN'], title: '售票工作台' }
            },
            {
                path: 'sell',
                name: 'seller-sell',
                component: () => import('../views/seller/SellerSell.vue'),
                meta: { roles: ['SELLER', 'ADMIN'], title: '代售开票' }
            },
            {
                path: 'orders',
                name: 'seller-orders',
                component: () => import('../views/seller/SellerOrders.vue'),
                meta: { roles: ['SELLER', 'ADMIN'], title: '代售订单' }
            },
            {
                path: 'orders/:id',
                name: 'seller-order-detail',
                component: () => import('../views/OrderDetail.vue'),
                meta: { roles: ['SELLER', 'ADMIN'], title: '订单详情' }
            }
        ]
    },
    {
        path: '/:pathMatch(.*)*',
        name: 'not-found',
        component: () => import('../views/NotFoundView.vue'),
        meta: { public: true, title: '页面不存在' }
    }
]

const router = createRouter({
    // hash 模式：打包后由 Spring Boot 直接托管静态文件，hash 模式不需要
    // 服务端做「未知路径回退到 index.html」的重写规则，少一层坑。
    history: createWebHashHistory(),
    routes
})

// 各角色登录后该去哪个首页。ADMIN 也给售票台/乘客端入口，但默认落管理后台。
const LANDING = {
    ADMIN: '/admin',
    SELLER: '/seller',
    PASSENGER: '/'
}

// 抽成纯函数，才能被单测覆盖；beforeEach 只负责把 store 接进来。
export function checkAccess(to, store) {
    if (!store.isLogin) {
        // checked=false 表示 current 请求因网络/5xx 失败，尚不能判定为未登录。
        if (store.checked === false) return true
        return { path: '/login', query: { redirect: to.fullPath } }
    }
    // 带 roles 的路由做角色校验，管理员天然拥有全部权限
    const roles = to.meta.roles
    if (roles && !roles.includes(store.role)) {
        return { path: LANDING[store.role] || '/' }
    }
    return true
}

router.beforeEach(async (to) => {
    document.title = to.meta.title ? `${to.meta.title} - 轮渡码头客运管理系统` : '轮渡码头客运管理系统'
    if (to.meta.public) {
        return true
    }
    const store = useUserStore()
    await store.load()
    if (!store.isLogin && !store.checked) {
        ElMessage.error('暂时无法确认登录状态，请稍后重试')
    }
    const result = checkAccess(to, store)
    if (result !== true && result.path === LANDING[store.role]) {
        ElMessage.warning('当前角色没有访问该页面的权限')
    }
    return result
})

export { LANDING }
export default router
