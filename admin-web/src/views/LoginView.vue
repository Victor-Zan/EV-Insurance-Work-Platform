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
  <main class="page-shell"><ElCard class="status-card" shadow="never">
    <h1>管理端登录</h1><p>管理员与客服使用账号密码登录</p>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" role="alert" />
    <ElForm label-position="top" @submit.prevent="login">
      <ElFormItem label="账号"><ElInput v-model="username" autocomplete="username" maxlength="64" /></ElFormItem>
      <ElFormItem label="密码"><ElInput v-model="password" type="password" autocomplete="current-password" show-password /></ElFormItem>
      <ElButton type="primary" native-type="submit" :loading="loading">登录</ElButton>
    </ElForm>
  </ElCard></main>
</template>
