<script setup>
// M0 占位页：管理端的真实模块（航线/船舶/班次/用户/报表）在 M2–M5 填。
</script>

<template>
    <div class="card">
        <h2 class="page-title">管理后台</h2>
        <p class="muted">
            航线与班次、船舶、用户与权限、统计报表四个模块在此展开。
            M0 阶段先打通登录与路由，业务功能按 PLAN.md 的 M1–M6 顺序补齐。
        </p>
        <el-row :gutter="12" style="margin-top: 12px">
            <el-col :span="6"><el-card shadow="hover">航线管理</el-card></el-col>
            <el-col :span="6"><el-card shadow="hover">船舶管理</el-card></el-col>
            <el-col :span="6"><el-card shadow="hover">班次管理</el-card></el-col>
            <el-col :span="6"><el-card shadow="hover">统计报表</el-card></el-col>
        </el-row>
    </div>
</template>
