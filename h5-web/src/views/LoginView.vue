<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/shared/http/client'
import { Button, Field, Form, NavBar } from 'vant'
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
  <main class="mobile-shell"><NavBar title="H5 登录" /><section class="intro"><h1>账号登录</h1><p>维修网点与车主使用账号密码登录</p></section>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <Form @submit="login">
      <Field v-model="username" name="username" label="账号" autocomplete="username" maxlength="64" placeholder="请输入账号" />
      <Field v-model="password" name="password" type="password" label="密码" autocomplete="current-password" placeholder="请输入密码" />
      <div class="actions"><Button block type="primary" native-type="submit" :loading="loading">登录</Button></div>
    </Form>
  </main>
</template>
