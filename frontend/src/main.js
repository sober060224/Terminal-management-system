import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import './styles/main.css'

const app = createApp(App)

// 图标全量注册：菜单里用 <component :is="'Tickets'"/> 这种字符串形式，
// 不注册的话 Element Plus 只认组件引用，动态字符串会渲染成空白。
for (const [name, comp] of Object.entries(ElementPlusIconsVue)) {
    app.component(name, comp)
}

// pinia 必须先于 router 安装：路由守卫里会用 useUserStore()
app.use(createPinia())
    .use(router)
    .use(ElementPlus)
    .mount('#app')
