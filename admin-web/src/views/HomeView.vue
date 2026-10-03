<script setup lang="ts">
import { useRouter } from 'vue-router'
import { authSession, currentUser } from '@/shared/http/client'
import { ElButton, ElCard, ElTag } from 'element-plus'
const router = useRouter()
function logout() { authSession.clear(); void router.replace('/login') }
</script>

<template>
  <main class="page-shell"><ElCard class="status-card" shadow="never">
    <h1>管理端</h1><p>当前用户：{{ currentUser?.displayName }}（{{ currentUser?.username }}）</p>
    <ElTag v-for="role in currentUser?.roles" :key="role">{{ role }}</ElTag>
    <div class="actions"><ElButton v-if="currentUser?.roles.includes('ADMIN')" @click="router.push('/users')">用户管理验证</ElButton><ElButton @click="logout">退出登录</ElButton></div>
  </ElCard></main>
</template>
