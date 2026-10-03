<script setup lang="ts">
import { useRouter } from 'vue-router'
import { authSession } from '@/shared/http/client'
defineProps<{ message: string; retry: boolean }>()
const router = useRouter()
function logout() { authSession.clear(); void router.replace('/login') }
</script>
<template><main class="page-shell"><section><h1>{{ message }}</h1>
  <button v-if="retry" @click="router.replace('/')">重试连接</button>
  <button v-else @click="router.replace('/')">返回首页</button><button @click="logout">退出登录</button>
</section></main></template>
