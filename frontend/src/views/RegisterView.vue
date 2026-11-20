<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '../api'

const router = useRouter()
const form = reactive({
    username: '',
    password: '',
    confirmPassword: '',
    name: '',
    phone: '',
    idCard: ''
})
const loading = ref(false)
const errorText = ref('')

async function submit() {
    if (form.password !== form.confirmPassword) {
        errorText.value = '两次输入的密码不一致'
        return
    }
    loading.value = true
    errorText.value = ''
    try {
        await authApi.register({
            username: form.username,
            password: form.password,
            name: form.name,
            phone: form.phone,
            idCard: form.idCard
        })
        ElMessage.success('注册成功，请登录')
        router.push('/login')
    } catch (e) {
        errorText.value = e.message || '注册失败'
    } finally {
        loading.value = false
    }
}
</script>

<template>
    <div class="auth-page">
        <div class="auth-card wide">
            <h2 class="auth-title">注册乘客账号</h2>
            <p class="auth-sub">注册后默认以乘客身份登录</p>

            <el-form label-position="top" @submit.prevent="submit">
                <el-form-item label="用户名" required>
                    <el-input v-model="form.username" placeholder="用于登录"/>
                </el-form-item>
                <el-form-item label="密码" required>
                    <el-input v-model="form.password" type="password" show-password/>
                </el-form-item>
                <el-form-item label="确认密码" required>
                    <el-input v-model="form.confirmPassword" type="password" show-password/>
                </el-form-item>
                <el-form-item label="姓名" required>
                    <el-input v-model="form.name" placeholder="真实姓名"/>
                </el-form-item>
                <el-form-item label="手机号" required>
                    <el-input v-model="form.phone" placeholder="11 位手机号"/>
                </el-form-item>
                <el-form-item label="身份证号" required>
                    <el-input v-model="form.idCard" placeholder="18 位身份证"/>
                </el-form-item>
                <el-alert v-if="errorText" :title="errorText" type="error" :closable="false" show-icon/>
                <el-button type="primary" class="submit" :loading="loading" @click="submit">
                    注册
                </el-button>
            </el-form>

            <p class="auth-foot">已有账号？<router-link to="/login">返回登录</router-link></p>
        </div>
    </div>
</template>

<style scoped>
.wide {
    width: 420px;
}

.submit {
    width: 100%;
    margin-top: 12px;
}
</style>
