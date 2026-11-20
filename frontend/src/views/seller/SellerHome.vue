<script setup>
// M0 占位页：售票、代买代退、检票三个工作区在 M2–M3 填。
</script>

<template>
    <div class="card">
        <h2 class="page-title">售票工作台</h2>
        <p class="muted">
            班次查询、选座开票、线下代买代退、扫码检票四个工作区在此展开。
            M0 阶段先打通登录与路由，业务功能按 PLAN.md 的 M1–M6 顺序补齐。
        </p>
        <el-row :gutter="12" style="margin-top: 12px">
            <el-col :span="8"><el-card shadow="hover">班次查询</el-card></el-col>
            <el-col :span="8"><el-card shadow="hover">选座开票</el-card></el-col>
            <el-col :span="8"><el-card shadow="hover">扫码检票</el-card></el-col>
        </el-row>
    </div>
</template>
