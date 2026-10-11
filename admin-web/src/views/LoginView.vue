<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/shared/http/client'
import { ElButton, ElCard, ElForm, ElFormItem, ElInput, ElAlert } from 'element-plus'
const router = useRouter()
const username = ref(''), password = ref(''), error = ref(''), loading = ref(false)
async function login() {
  if (loading.value) return
  if (!username.value.trim() || !password.value) { error.value = '请输入账号和密码'; return }
  loading.value = true; error.value = ''
  try { await api.login(username.value.trim(), password.value); password.value = ''; await router.replace('/') }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '登录失败，请重试'; password.value = '' }
  finally { loading.value = false }
}
</script>

<template>
  <main class="login-page"><div class="login-illustration"><p class="login-kicker">INSURANCE CASE OPERATIONS</p><div><span class="brand-caption">e保 / 保险协作台 · 设计占位</span><h1>让案件处理<br/>清晰、有序、可追溯。</h1><p>从建单、报价核损到维修交付，在一个克制的工作空间里衔接每一步。</p></div><svg viewBox="0 0 460 190" fill="none" stroke="currentColor" aria-hidden="true"><path d="M30 152h400M78 151a41 41 0 1 0 82 0 41 41 0 0 0-82 0Zm246 0a41 41 0 1 0 82 0 41 41 0 0 0-82 0Z"/><path d="m119 151 55-80h103l88 80M174 71l49 80 54-80M223 151h-104M277 71l48-21 40 101M222 151h143M172 71l-26-19h-29M299 50h58M62 37h318M62 32v10m318-10v10M90 12h261"/><path stroke-dasharray="4 5" d="M119 27v143m246-143v143M223 62v101"/></svg></div><ElCard class="login-card" shadow="never">
    <span class="login-kicker">ADMIN & CUSTOMER SERVICE</span><h2>登录管理端</h2><p>仅管理员与客服可进入；网点和车主请使用移动端。</p>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" role="alert" />
    <ElForm label-position="top" @submit.prevent="login">
      <ElFormItem label="账号"><ElInput v-model="username" autocomplete="username" maxlength="64" /></ElFormItem>
      <ElFormItem label="密码"><ElInput v-model="password" type="password" autocomplete="current-password" show-password /></ElFormItem>
      <ElButton class="login-submit" type="primary" native-type="submit" :loading="loading">登录工作台</ElButton>
    </ElForm>
  </ElCard></main>
</template>
<style scoped>.login-page{display:grid;grid-template-columns:minmax(0,1.1fr) minmax(400px,.9fr);min-height:100vh}.login-illustration{position:relative;display:flex;flex-direction:column;justify-content:space-between;overflow:hidden;padding:50px 8vw;background-color:var(--color-canvas);background-image:linear-gradient(rgba(82,110,110,.05) 1px,transparent 1px),linear-gradient(90deg,rgba(82,110,110,.05) 1px,transparent 1px);background-size:24px 24px}.login-illustration h1{margin:18px 0;font-size:clamp(32px,3.5vw,54px);line-height:1.22;letter-spacing:-.03em}.login-illustration p{max-width:450px}.login-illustration svg{align-self:end;width:min(500px,100%);color:#bdc9c9}.brand-caption,.login-kicker{color:var(--color-accent);font-size:11px;font-weight:700;letter-spacing:.1em}.login-card{align-self:center;justify-self:center;width:min(420px,calc(100% - 48px));border:0}.login-card h2{margin:14px 0 6px;font-size:28px}.login-card p{margin-bottom:24px}.login-card .el-form{margin-top:0}.login-submit{width:100%;height:44px;margin-top:8px}@media(max-width:850px){.login-page{grid-template-columns:1fr}.login-illustration{min-height:180px;padding:28px}.login-illustration h1{font-size:26px}.login-illustration svg{position:absolute;right:0;bottom:-32px;width:220px;opacity:.35}.login-card{margin:30px 0}}@media(prefers-reduced-motion:reduce){.login-page *{animation:none}}</style>
