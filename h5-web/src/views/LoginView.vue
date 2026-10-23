<script setup lang="ts">
import { ref,onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { httpClient,authSession } from '@/shared/http/client'
import {unifiedLogin,consumeHandoff} from '@/shared/auth/account-login'
import { Button, Field, Form } from 'vant'
import chargingScene from '@/assets/e-scooter-charging-login.webp'
import batteryArtwork from '@/assets/engineering-battery.webp'
import gearArtwork from '@/assets/engineering-gear.webp'
import wrenchArtwork from '@/assets/engineering-wrench.webp'
const router = useRouter()
const username = ref(''), password = ref(''), error = ref(''), loading = ref(false), passwordVisible = ref(false)
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
  <main class="login-page">
    <section class="login-hero">
      <img class="charging-scene" :src="chargingScene" alt="" aria-hidden="true">
      <div class="hero-shade" aria-hidden="true"></div>
      <div class="login-brand"><span><svg viewBox="0 0 32 32" aria-hidden="true"><path d="M16 2 28 7v9c0 8-5 12-12 14C9 28 4 24 4 16V7L16 2Z" fill="currentColor"/><path d="m9 16 5 5 9-10" fill="none" stroke="white" stroke-width="2.5"/></svg></span><p><strong>e保</strong><small>保险维修服务</small></p></div>
      <div class="login-copy"><p class="eyebrow">电动车保险协作</p><span class="brand-caption">e保 / 保险维修服务</span><h1>让案件处理<br>清晰、有序。</h1><p>维修进度随时查，车辆交付更安心。</p></div>
      <span class="scene-caption" aria-hidden="true">ELECTRIC · SERVICE · CASE</span>
    </section>
    <div class="engineering-parts" aria-hidden="true"><img class="gear-rotor" :src="gearArtwork" alt=""><img class="gear-satellite" :src="gearArtwork" alt=""><img class="metal-wrench" :src="wrenchArtwork" alt=""></div>
    <section class="login-side">
      <div class="login-form"><p class="form-kicker">车主 / 维修网点</p><h2>统一登录</h2><p>统一账号密码登录，按后台授权进入对应端。受邀账号请先通过邀请链接开通，无公众注册。</p><p v-if="error" class="error-message" role="alert">{{ error }}</p>
        <Form @submit="login">
          <label class="field-label" for="login-username">账号</label>
          <Field id="login-username" v-model="username" name="username" autocomplete="username" maxlength="64" placeholder="请输入账号" />
          <label class="field-label" for="login-password">密码</label>
          <Field id="login-password" v-model="password" name="password" :type="passwordVisible ? 'text' : 'password'" autocomplete="current-password" placeholder="请输入密码"><template #button><button class="password-toggle" type="button" :aria-label="passwordVisible ? '隐藏密码' : '显示密码'" :aria-pressed="passwordVisible" @click="passwordVisible = !passwordVisible">{{ passwordVisible ? '隐藏' : '显示' }}</button></template></Field>
          <div class="actions"><Button block type="primary" native-type="submit" :loading="loading">登录工作台</Button></div>
        </Form>
      </div>
      <div class="metal-battery" aria-hidden="true"><img :src="batteryArtwork" alt=""><svg viewBox="0 0 1774 887"><rect class="battery-cell charge-one" x="348" y="270" width="146" height="382" rx="28"/><rect class="battery-cell charge-two" x="540" y="270" width="146" height="382" rx="28"/><rect class="battery-cell charge-three" x="732" y="270" width="146" height="382" rx="28"/><rect class="battery-cell charge-four" x="924" y="270" width="146" height="382" rx="28"/><rect class="battery-cell charge-five" x="1116" y="270" width="146" height="382" rx="28"/></svg></div>
    </section>
  </main>
</template>
<style scoped>
.login-page {
  position: relative;
  isolation: isolate;
  display: grid;
  grid-template-columns: minmax(0,1.18fr) minmax(380px,.82fr);
  min-height: 100dvh;
  overflow: hidden;
  background: linear-gradient(115deg,var(--color-canvas),#fffefa);
}
.login-hero {
  position: relative;
  min-height: 100dvh;
  overflow: hidden;
  padding: 40px 6vw;
  border-right: 1px solid #e0dcd380;
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
  background: linear-gradient(180deg,rgba(250,248,243,.97),rgba(250,248,243,.8) 30%,rgba(250,248,243,.12) 72%,rgba(250,248,243,.3));
}
.hero-shade::after {
  position: absolute;
  inset: 0;
  background-image: linear-gradient(rgba(65,55,38,.045) 1px,transparent 1px),linear-gradient(90deg,rgba(65,55,38,.045) 1px,transparent 1px);
  background-size: 24px 24px;
  content: '';
  mask-image: linear-gradient(#000,transparent 64%);
}
.login-brand,.login-copy { position: relative; z-index: 2; }
.login-brand { display: flex; align-items: center; gap: 10px; }
.login-brand>span {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  border: 1px solid rgba(255,255,255,.94);
  border-radius: 10px;
  background: var(--glass-surface);
  box-shadow: inset 0 1px 0 #fff,var(--shadow-surface);
  color: var(--color-accent);
}
.login-brand svg { width: 22px; height: 22px; }
.login-brand p,.login-brand strong,.login-brand small { display: block; margin: 0; }
.login-brand strong { font-size: 16px; }
.login-brand small { color: var(--color-text-secondary); font-size: 11px; }
.login-copy {
  max-width: 570px;
  margin-top: 60px;
  animation: copy-arrive 520ms 80ms var(--motion-fluid) both;
}
.login-copy .eyebrow { margin: 0 0 12px; color: var(--color-accent); font-size: 13px; font-weight: 600; letter-spacing: .08em; }
.brand-caption { color: var(--color-text-secondary); font-size: 13px; }
.login-copy h1 { margin: 18px 0; font-size: clamp(32px,3.5vw,50px); font-weight: 600; line-height: 1.25; letter-spacing: -.03em; }
.login-copy>p:last-child { margin: 0; color: var(--color-text-secondary); font-size: 14px; line-height: 1.7; }
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
.engineering-parts {
  position: absolute;
  left: calc(59% - 180px);
  top: 28px;
  z-index: 2;
  width: 160px;
  height: 140px;
  opacity: .58;
  pointer-events: none;
}
.engineering-parts .gear-rotor,.engineering-parts .gear-satellite { position: absolute; width: 95px; height: 95px; animation: engineering-turn 28s linear infinite; }
.engineering-parts .gear-rotor { top: 0; left: 0; }
.engineering-parts .gear-satellite { top: 48px; left: 64px; animation-direction: reverse; }
.engineering-parts .metal-wrench { position: absolute; top: 53px; left: 62px; width: 120px; transform: rotate(-30deg); }
.login-side {
  position: relative;
  z-index: 3;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-width: 0;
  padding: 24px;
}
.login-form {
  width: min(420px,100%);
  padding: 30px;
  border: 1px solid rgba(255,255,255,.95);
  border-radius: 16px;
  background: var(--glass-surface);
  box-shadow: inset 0 1px 0 #fff,var(--shadow-floating);
  backdrop-filter:blur(18px) saturate(.8);
  animation: form-rise 520ms 130ms var(--motion-fluid) both;
}
.form-kicker { margin: 0 0 8px; color: var(--color-accent); font-size: 12px; font-weight: 600; letter-spacing: .06em; }
.login-form h2 { margin: 0 0 8px; font-size: 27px; font-weight: 600; letter-spacing: -.02em; }
.login-form>p:not(.form-kicker) { margin: 0 0 22px; color: var(--color-text-secondary); font-size: 13px; line-height: 1.6; }
.login-form>p.error-message { color: var(--color-danger,#a83b3b); }
.field-label { display: block; margin: 0 0 7px; font-size: 13px; font-weight: 600; }
.login-form :deep(.van-field) {
  min-height: 44px;
  margin-bottom: 18px;
  padding: 9px 12px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: rgba(255,255,255,.9);
}
.login-form :deep(.van-field:focus-within) { border-color: var(--color-accent); box-shadow: 0 0 0 3px rgba(153,117,72,.12); }
.login-form :deep(.van-cell::after) { display: none; }
.password-toggle { padding: 4px 0 4px 10px; border: 0; background: none; color: var(--color-accent); font-size: 13px; cursor: pointer; }
.password-toggle:focus-visible { outline: 2px solid var(--color-accent); outline-offset: 2px; }
.actions { padding-top: 6px; }
.actions :deep(.van-button) { min-height: 46px; border-radius: 8px; }
.metal-battery { position: relative; width: 210px; aspect-ratio: 2; opacity: .76; pointer-events: none; }
.metal-battery img { display: block; width: 100%; }
.metal-battery svg { position: absolute; inset: 0; width: 100%; height: 100%; mix-blend-mode: multiply; }
.battery-cell { fill: #729d89; opacity: .06; animation: engineering-charge-one 8s ease-in-out infinite; }
.charge-two { animation-name: engineering-charge-two; }.charge-three { animation-name: engineering-charge-three; }.charge-four { animation-name: engineering-charge-four; }.charge-five { animation-name: engineering-charge-five; }
@keyframes engineering-turn { to { transform: rotate(360deg); } }
@keyframes engineering-charge-one { 0%,100% { opacity: .06; } 9%,91% { opacity: .65; } }
@keyframes engineering-charge-two { 0%,9%,91%,100% { opacity: .06; } 18%,82% { opacity: .65; } }
@keyframes engineering-charge-three { 0%,18%,82%,100% { opacity: .06; } 27%,73% { opacity: .65; } }
@keyframes engineering-charge-four { 0%,27%,73%,100% { opacity: .06; } 36%,64% { opacity: .65; } }
@keyframes engineering-charge-five { 0%,36%,64%,100% { opacity: .06; } 45%,55% { opacity: .65; } }
@keyframes scene-arrive { from { opacity: 0; transform: scale(1.06); } to { opacity: 1; transform: scale(1.02); } }
@keyframes copy-arrive { from { opacity: 0; transform: translate3d(-8px,8px,0); } to { opacity: 1; transform: translate3d(0,0,0); } }
@keyframes form-rise { from { opacity: 0; transform: translate3d(0,18px,0); } to { opacity: 1; transform: translate3d(0,0,0); } }
@media(max-width:850px) {
  .login-page { display: block; }
  .login-hero { min-height: 380px; padding: 24px 26px; border-right: 0; }
  .login-copy { margin-top: 22px; }
  .login-copy h1 { margin: 10px 0; font-size: 32px; }
  .charging-scene { object-position: 60% 57%; }
  .hero-shade { background: linear-gradient(180deg,rgba(250,248,243,.98),rgba(250,248,243,.8) 50%,rgba(250,248,243,.12)); }
  .engineering-parts { left: auto; right: 10px; top: 40px; width: 90px; height: 90px; transform: scale(.65); transform-origin: top right; opacity: .42; }
  .login-side { gap: 0; padding: 0 16px 16px; }
  .login-form { margin-top: -12px; padding: 24px; }
  .metal-battery { width: 130px; margin-top: -4px; }
}
@media(max-width:390px) {
  .login-hero { min-height: 370px; padding: 20px 22px; }
  .login-copy { margin-top: 18px; }
  .login-copy h1 { font-size: 29px; }
  .login-copy>p:last-child { font-size: 12px; }
  .login-form { padding: 22px; }
}
@media(prefers-reduced-transparency:reduce) { .login-brand>span,.login-form { background: #fffefa; backdrop-filter: none; } }
@supports not (backdrop-filter:blur(1px)) { .login-form { background: #fffefa; } }
@media(prefers-reduced-motion:reduce) { .charging-scene,.login-copy,.login-form,.engineering-parts img,.battery-cell { animation: none; }.battery-cell { opacity: .4; } }
</style>
