<script setup lang="ts">
import { useRouter } from 'vue-router'
import { Button } from 'vant'
import { authSession } from '@/shared/http/client'
defineProps<{ message: string; retry: boolean }>()
const router = useRouter()
function logout() { authSession.clear(); void router.replace('/login') }
</script>
<template><main class="access-page"><section><p class="eyebrow">CONNECTION / 访问状态</p><h1>{{ message }}</h1><p>{{ retry?'请检查网络后手动重试，当前操作不会自动重复提交。':'当前账号无法访问这个页面。' }}</p><Button v-if="retry" block type="primary" @click="router.replace('/')">重试连接</Button><Button v-else block type="primary" @click="router.replace('/')">返回首页</Button><Button block plain @click="logout">退出登录</Button></section></main></template>
<style scoped>.access-page{display:grid;min-height:100dvh;padding:24px;place-items:center;background:var(--color-canvas)}section{width:min(100%,420px);padding:28px 22px;border:1px solid var(--color-border);border-radius:14px;background:#fff}h1{margin:8px 0;font-size:24px}p:not(.eyebrow){margin:0 0 22px;color:var(--color-text-secondary);line-height:1.7}.van-button+.van-button{margin-top:10px}</style>
