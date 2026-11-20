<script setup>
import { ElMessage } from 'element-plus'

// 404 页面：给一个回首页的出口，否则用户刷新错误地址后没有路可走。
ElMessage.warning('页面不存在')
</script>

<template>
    <div class="auth-page">
        <div class="auth-card">
            <h2 class="auth-title">404</h2>
            <p class="auth-sub">页面不存在或已被移除</p>
            <router-link to="/">
                <el-button type="primary" style="width: 100%">返回首页</el-button>
            </router-link>
        </div>
    </div>
</template>
