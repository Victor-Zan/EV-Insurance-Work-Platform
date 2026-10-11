<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/shared/http/client'
import { Button, Field, Form, Icon } from 'vant'
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
  <main class="login-page"><section class="login-hero"><div class="login-brand"><span><Icon name="shield-o"/></span><p><strong>e保</strong><small>保险协作台 · 设计占位</small></p></div><p class="eyebrow">MOBILE CASE OPERATIONS</p><h1>把当前案件，<br>稳稳推进到下一步。</h1><p>网点处理接单、材料和维修进度；车主查看本人案件、照片、收车与投诉。</p><div class="bike-line" aria-hidden="true"></div></section>
    <section class="login-form"><h2>账号登录</h2><p>仅维修网点与车主可进入移动端。</p><p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <Form label-align="top" @submit="login">
      <Field v-model="username" name="username" label="账号" autocomplete="username" maxlength="64" placeholder="请输入账号" />
      <Field v-model="password" name="password" type="password" label="密码" autocomplete="current-password" placeholder="请输入密码" />
      <div class="actions"><Button block type="primary" native-type="submit" :loading="loading">登录</Button></div>
    </Form></section>
  </main>
</template>
<style scoped>
.login-page{min-height:100dvh;background:#fff}.login-hero{position:relative;min-height:330px;padding:26px 22px 34px;overflow:hidden;background:linear-gradient(180deg,#f7f8f8 0%,#f1f3f3 100%)}
.login-brand{display:flex;align-items:center;gap:10px;margin-bottom:48px}.login-brand>span{display:grid;width:38px;height:38px;place-items:center;border:1px solid var(--color-border);border-radius:10px;background:#fff;color:var(--color-accent);font-size:22px}.login-brand p,.login-brand strong,.login-brand small{display:block;margin:0}.login-brand small{color:var(--color-text-secondary);font-size:10px}
.login-hero h1{position:relative;z-index:1;margin:8px 0 14px;font-size:30px;line-height:1.2;letter-spacing:-.04em}.login-hero>p:last-of-type{position:relative;z-index:1;max-width:31em;margin:0;color:var(--color-text-secondary);font-size:14px;line-height:1.75}
.bike-line{position:absolute;right:-42px;bottom:4px;width:250px;height:108px;border:1px solid rgba(82,110,110,.11);border-radius:50% 45% 12% 20%;transform:skewX(-16deg);opacity:.8}.bike-line::before,.bike-line::after{position:absolute;bottom:-4px;width:62px;height:62px;border:2px solid rgba(82,110,110,.13);border-radius:50%;content:''}.bike-line::before{left:26px}.bike-line::after{right:20px}
.login-form{position:relative;margin-top:-12px;padding:28px 16px 24px;border-radius:16px 16px 0 0;background:#fff}.login-form h2{margin:0 4px 4px;font-size:22px}.login-form>p{margin:0 4px 18px;color:var(--color-text-secondary);font-size:13px}.actions{padding:22px 4px 0}
</style>
