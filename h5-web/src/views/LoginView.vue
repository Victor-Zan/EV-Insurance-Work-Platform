<script setup lang="ts">
import { ref,onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { httpClient,authSession } from '@/shared/http/client'
import {unifiedLogin,consumeHandoff} from '@/shared/auth/account-login'
import { Button, Field, Form, Icon } from 'vant'
import chargingScene from '@/assets/e-scooter-charging-login.webp'
const router = useRouter()
const username = ref(''), password = ref(''), error = ref(''), loading = ref(false)
async function login() {
  if (loading.value) return
  if (!username.value.trim() || !password.value) { error.value = '请输入账号和密码'; return }
  loading.value = true; error.value = ''
  try { const redirect=await unifiedLogin(httpClient,authSession,username.value.trim(),password.value);password.value='';if(redirect)location.assign(redirect);else await router.replace('/') }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '登录失败，请重试'; password.value = '' }
  finally { loading.value = false }
}
onMounted(async()=>{try{if(await consumeHandoff(httpClient,authSession))await router.replace('/')}catch(e){error.value=e instanceof Error?e.message:'登录交接失效，请重新登录'}})
</script>
<template>
  <main class="login-page"><section class="login-hero"><img class="charging-scene" :src="chargingScene" alt="" aria-hidden="true"><div class="hero-shade" aria-hidden="true"></div><div class="login-brand"><span><Icon name="shield-o"/></span><p><strong>e保</strong><small>保险维修服务</small></p></div><div class="login-copy"><p class="eyebrow">MOBILE CASE OPERATIONS</p><h1>把当前案件，<br>稳稳推进到下一步。</h1><p>维修进度随时查，车辆交付更安心。</p></div><span class="scene-caption" aria-hidden="true">ELECTRIC · SERVICE · CASE</span></section>
    <section class="login-form"><h2>账号登录</h2><p>统一账号密码登录，按后台授权进入对应端。受邀账号请先通过邀请链接开通，无公众注册。</p><p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <Form label-align="top" @submit="login">
      <Field v-model="username" name="username" label="账号" autocomplete="username" maxlength="64" placeholder="请输入账号" />
      <Field v-model="password" name="password" type="password" label="密码" autocomplete="current-password" placeholder="请输入密码" />
      <div class="actions"><Button block type="primary" native-type="submit" :loading="loading">登录</Button></div>
    </Form></section>
  </main>
</template>
<style scoped>
.login-page {
  min-height: 100dvh;
  overflow: hidden;
  background: var(--color-canvas);
}
.login-hero {
  position: relative;
  min-height: clamp(360px, 50dvh, 480px);
  overflow: hidden;
  padding: 24px 22px 48px;
  background: #f1eee8;
}
.charging-scene {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: 60% 67%;
  filter: grayscale(.7) sepia(.06) contrast(.9);
  transform: scale(1.02);
  animation: scene-arrive 760ms var(--motion-fluid) both;
}
.hero-shade {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, rgba(250,248,243,.95), rgba(250,248,243,.64) 39%, rgba(250,248,243,.12) 68%, rgba(250,248,243,.4));
}
.hero-shade::after {
  position: absolute;
  inset: 0;
  background-image: linear-gradient(rgba(65,55,38,.045) 1px, transparent 1px), linear-gradient(90deg,rgba(65,55,38,.045) 1px, transparent 1px);
  background-size: 24px 24px;
  content: '';
  mask-image: linear-gradient(#000, transparent 64%);
}
.login-brand {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 46px;
}
.login-brand>span {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  border: 1px solid rgba(255,255,255,.94);
  border-radius: 10px;
  background: var(--glass-surface);
  box-shadow: inset 0 1px 0 #fff, var(--shadow-surface);
  color: var(--color-accent);
  font-size: 22px;
}
.login-brand p,.login-brand strong,.login-brand small { display: block; margin: 0; }
.login-brand small { color: var(--color-text-secondary); font-size: 10px; }
.login-copy {
  position: relative;
  z-index: 2;
  width: min(310px, 88%);
  padding: 18px 18px 20px;
  border: 1px solid rgba(255,255,255,.9);
  border-radius: 14px;
  background: rgba(255,254,250,.7);
  box-shadow: inset 0 1px 0 #fff, var(--shadow-floating);
  backdrop-filter: blur(12px) saturate(.8);
  animation: copy-arrive 520ms 80ms var(--motion-fluid) both;
}
.login-copy h1 { margin: 8px 0 14px; font-size: 30px; line-height: 1.2; letter-spacing: -.04em; }
.login-copy>p:last-child { max-width: 31em; margin: 0; color: var(--color-text-secondary); font-size: 13px; line-height: 1.72; }
.scene-caption {
  position: absolute;
  right: 18px;
  bottom: 20px;
  z-index: 2;
  color: rgba(41,43,45,.54);
  font: 9px ui-monospace,SFMono-Regular,Consolas,monospace;
  letter-spacing: .18em;
  writing-mode: vertical-rl;
}
.login-form {
  position: relative;
  z-index: 3;
  margin: -30px 14px 20px;
  padding: 26px 18px calc(24px + env(safe-area-inset-bottom));
  border: 1px solid rgba(255,255,255,.95);
  border-radius: 16px;
  background: var(--glass-surface);
  box-shadow: inset 0 1px 0 #fff, var(--shadow-floating);
  backdrop-filter:blur(18px) saturate(.8);
  animation: form-rise 520ms 130ms var(--motion-fluid) both;
}
.login-form h2 { margin: 0 4px 4px; font-size: 24px; letter-spacing: -.02em; }
.login-form>p { margin: 0 4px 18px; color: var(--color-text-secondary); font-size: 13px; line-height: 1.6; }
.login-form :deep(.van-field) {
  margin-bottom: 12px;
  padding: 10px 12px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: rgba(255,255,255,.76);
}
.login-form :deep(.van-cell::after) { display: none; }
.actions { padding: 10px 0 0; }
.actions :deep(.van-button) { min-height: 48px; border-radius: 8px; }
@keyframes scene-arrive { from { opacity: 0; transform: scale(1.06); } to { opacity: 1; transform: scale(1.02); } }
@keyframes copy-arrive { from { opacity: 0; transform: translate3d(-8px,8px,0); } to { opacity: 1; transform: translate3d(0,0,0); } }
@keyframes form-rise { from { opacity: 0; transform: translate3d(0,18px,0); } to { opacity: 1; transform: translate3d(0,0,0); } }
@media(min-width:700px) {
  .login-page { display: grid; grid-template-columns: minmax(0,1fr) minmax(380px,460px); }
  .login-hero { min-height: 100dvh; }
  .login-form { align-self: center; margin: 0 32px; }
}
@media(prefers-reduced-transparency:reduce) {
  .login-brand>span,.login-copy,.login-form { background: #fffefa; backdrop-filter: none; }
}
@supports not (backdrop-filter:blur(1px)) {
  .login-copy,.login-form { background: #fffefa; }
}
@media(prefers-reduced-motion:reduce) {
  .charging-scene,.login-copy,.login-form { animation: none; }
}
</style>