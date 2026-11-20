<script setup>
// M0 占位页：查班次、选座购票、我的船票在 M1–M3 填。
</script>

<template>
    <div class="card">
        <h2 class="page-title">乘客服务</h2>
        <p class="muted">
            查询班次、选座购票、我的船票三个入口在此展开。
            M0 阶段先打通登录与路由，业务功能按 PLAN.md 的 M1–M6 顺序补齐。
        </p>
        <el-row :gutter="12" style="margin-top: 12px">
            <el-col :span="8"><el-card shadow="hover">查询班次</el-card></el-col>
            <el-col :span="8"><el-card shadow="hover">选座购票</el-card></el-col>
            <el-col :span="8"><el-card shadow="hover">我的船票</el-card></el-col>
        </el-row>
    </div>
</template>
