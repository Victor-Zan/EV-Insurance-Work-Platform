<script setup lang="ts">
import { ElCard, ElTag } from 'element-plus'
</script>

<template>
  <main class="page-shell">
    <ElCard class="status-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>电动车保险维修案件协同平台</span>
          <ElTag type="success">工程骨架已就绪</ElTag>
        </div>
      </template>
      <h1>管理端</h1>
      <p>供管理员和客服使用。当前阶段仅建立可启动工程，不包含业务功能。</p>
    </ElCard>
  </main>
</template>
