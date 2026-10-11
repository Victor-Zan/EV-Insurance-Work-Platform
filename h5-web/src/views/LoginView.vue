<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/shared/http/client'
import { Button, Field, Form, Icon } from 'vant'
import chargingScene from '@/assets/e-scooter-charging-login.webp'
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
  <main class="login-page"><section class="login-hero"><img class="charging-scene" :src="chargingScene" alt="" aria-hidden="true"><div class="hero-shade" aria-hidden="true"></div><div class="login-brand"><span><Icon name="shield-o"/></span><p><strong>e保</strong><small>保险协作台 · 设计占位</small></p></div><div class="login-copy"><p class="eyebrow">MOBILE CASE OPERATIONS</p><h1>把当前案件，<br>稳稳推进到下一步。</h1><p>网点处理接单、材料和维修进度；车主查看本人案件、照片、收车与投诉。</p></div><span class="scene-caption" aria-hidden="true">ELECTRIC · SERVICE · CASE</span></section>
    <section class="login-form"><h2>账号登录</h2><p>仅维修网点与车主可进入移动端。</p><p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <Form label-align="top" @submit="login">
      <Field v-model="username" name="username" label="账号" autocomplete="username" maxlength="64" placeholder="请输入账号" />
      <Field v-model="password" name="password" type="password" label="密码" autocomplete="current-password" placeholder="请输入密码" />
      <div class="actions"><Button block type="primary" native-type="submit" :loading="loading">登录</Button></div>
    </Form></section>
  </main>
</template>
<style scoped>
.login-page{min-height:100dvh;overflow:hidden;background:#f7f8f8}.login-hero{position:relative;min-height:440px;padding:24px 22px 48px;overflow:hidden;background:#edf1f2}.charging-scene{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;object-position:60% 67%;filter:saturate(.52) contrast(.94);transform:scale(1.02);animation:scene-arrive 760ms cubic-bezier(.16,1,.3,1) both}.hero-shade{position:absolute;inset:0;background:linear-gradient(180deg,rgba(247,248,248,.93) 0%,rgba(247,248,248,.7) 38%,rgba(247,248,248,.18) 67%,rgba(247,248,248,.48) 100%)}.hero-shade::after{position:absolute;inset:0;background-image:linear-gradient(rgba(82,110,110,.045) 1px,transparent 1px),linear-gradient(90deg,rgba(82,110,110,.045) 1px,transparent 1px);background-size:24px 24px;content:'';mask-image:linear-gradient(#000,transparent 64%)}
.login-brand{position:relative;z-index:2;display:flex;align-items:center;gap:10px;margin-bottom:46px}.login-brand>span{display:grid;width:38px;height:38px;place-items:center;border:1px solid rgba(255,255,255,.94);border-radius:11px;background:rgba(255,255,255,.74);box-shadow:inset 0 1px 0 #fff,0 8px 24px rgba(82,110,110,.08);backdrop-filter:blur(12px);color:var(--color-accent);font-size:22px}.login-brand p,.login-brand strong,.login-brand small{display:block;margin:0}.login-brand small{color:var(--color-text-secondary);font-size:10px}
.login-copy{position:relative;z-index:2;width:min(310px,88%);padding:18px 18px 20px;border:1px solid rgba(255,255,255,.9);border-radius:14px;background:rgba(255,255,255,.58);box-shadow:inset 0 1px 0 #fff,0 18px 48px rgba(82,110,110,.075);backdrop-filter:blur(12px) saturate(.72);animation:copy-arrive 520ms 80ms cubic-bezier(.16,1,.3,1) both}.login-copy h1{margin:8px 0 14px;font-size:30px;line-height:1.2;letter-spacing:-.04em}.login-copy>p:last-child{max-width:31em;margin:0;color:var(--color-text-secondary);font-size:13px;line-height:1.72}.scene-caption{position:absolute;right:18px;bottom:20px;z-index:2;color:rgba(44,49,51,.54);font:9px ui-monospace,SFMono-Regular,Consolas,monospace;letter-spacing:.18em;writing-mode:vertical-rl}
.login-form{position:relative;z-index:3;margin:-28px 12px 0;padding:28px 16px calc(24px + env(safe-area-inset-bottom));border:1px solid rgba(255,255,255,.95);border-radius:18px 18px 0 0;background:rgba(255,255,255,.9);box-shadow:inset 0 1px 0 #fff,0 -16px 46px rgba(82,110,110,.09);backdrop-filter:blur(18px) saturate(.8);animation:form-rise 520ms 130ms cubic-bezier(.16,1,.3,1) both}.login-form h2{margin:0 4px 4px;font-size:22px}.login-form>p{margin:0 4px 18px;color:var(--color-text-secondary);font-size:13px}.actions{padding:22px 4px 0}
@keyframes scene-arrive{from{opacity:0;transform:scale(1.06)}to{opacity:1;transform:scale(1.02)}}@keyframes copy-arrive{from{opacity:0;transform:translate3d(-8px,8px,0)}to{opacity:1;transform:translate3d(0,0,0)}}@keyframes form-rise{from{opacity:0;transform:translate3d(0,18px,0)}to{opacity:1;transform:translate3d(0,0,0)}}
@media(min-width:700px){.login-page{display:grid;grid-template-columns:minmax(0,1fr) minmax(380px,460px)}.login-hero{min-height:100dvh}.login-form{align-self:center;margin:0 32px;border-radius:18px}}
@media(prefers-reduced-transparency:reduce){.login-brand>span,.login-copy,.login-form{background:#fff;backdrop-filter:none}}@media(prefers-reduced-motion:reduce){.charging-scene,.login-copy,.login-form{animation:none}}
</style>
