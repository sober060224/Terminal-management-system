<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../store/user'
import { LANDING } from '../router'

const store = useUserStore()
const route = useRoute()
const router = useRouter()

const form = reactive({ username: '', password: '' })
const loading = ref(false)
const errorText = ref('')

async function submit() {
    if (!form.username || !form.password) {
        errorText.value = '请输入用户名和密码'
        return
    }
    loading.value = true
    errorText.value = ''
    try {
        await store.login({ username: form.username, password: form.password })
        ElMessage.success('登录成功')
        // 登录前被踢下线时带了 redirect，回原页面；否则按角色落地
        const back = route.query.redirect
        router.push(back ? String(back) : (LANDING[store.role] || '/'))
    } catch (e) {
        errorText.value = e.message || '登录失败'
    } finally {
        loading.value = false
    }
}
</script>

<template>
    <div class="auth-page">
        <div class="auth-card">
            <h2 class="auth-title">轮渡码头客运管理系统</h2>
            <p class="auth-sub">请选择账号登录</p>

            <el-form label-position="top" @submit.prevent="submit">
                <el-form-item label="用户名">
                    <el-input v-model="form.username" placeholder="用户名" autocomplete="username"/>
                </el-form-item>
                <el-form-item label="密码">
                    <el-input
                        v-model="form.password"
                        type="password"
                        placeholder="密码"
                        show-password
                        autocomplete="current-password"
                        @keyup.enter="submit"
                    />
                </el-form-item>
                <el-alert v-if="errorText" :title="errorText" type="error" :closable="false" show-icon/>
                <el-button type="primary" class="submit" :loading="loading" @click="submit">
                    登录
                </el-button>
            </el-form>

            <p class="auth-foot">还没有账号？<router-link to="/register">注册乘客账号</router-link></p>
        </div>
    </div>
</template>

<style scoped>
.submit {
    width: 100%;
    margin-top: 12px;
}
</style>
