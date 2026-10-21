<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ElButton } from 'element-plus'
import { authSession } from '@/shared/http/client'
defineProps<{ message: string; retry: boolean }>()
const router = useRouter()
function logout() { authSession.clear(); void router.replace('/login') }
</script>
<template><main class="page-shell"><section class="access-state" role="alert"><p class="access-code">{{ retry ? '网络连接' : '访问权限' }}</p><h1>{{ message }}</h1><p>{{ retry ? '请检查网络或稍后重试，当前操作不会自动重复提交。' : '此入口不属于当前账号的权限范围。' }}</p><div class="access-actions"><ElButton type="primary" @click="router.replace('/')">{{ retry ? '重试连接' : '返回工作台' }}</ElButton><ElButton @click="logout">退出登录</ElButton></div></section></main></template>
<style scoped>.access-state{width:min(520px,100%);padding:36px;border:1px solid var(--color-border);border-radius:12px;background:#fff}.access-code{color:var(--color-accent);font-size:11px;font-weight:700;letter-spacing:.12em}.access-state h1{font-size:26px}.access-actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:22px}.access-actions .el-button{margin:0}</style>
