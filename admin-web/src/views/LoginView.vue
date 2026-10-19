<script setup lang="ts">
import { ref,onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { httpClient,authSession } from '@/shared/http/client'
import {unifiedLogin,consumeHandoff} from '@/shared/auth/account-login'
import { ElButton, ElCard, ElForm, ElFormItem, ElInput, ElAlert } from 'element-plus'
import engineeringExploded from '@/assets/e-scooter-exploded-login.webp'
import { vMagnetic } from '@/shared/ui/magnetic'
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
  <main class="login-page"><div class="login-illustration"><p class="login-kicker">INSURANCE CASE OPERATIONS</p><div class="login-copy"><span class="brand-caption">e保 / 保险协作台 · 设计占位</span><h1>让案件处理<br/>清晰、有序、可追溯。</h1><p>从建单、报价核损到维修交付，在一个克制的工作空间里衔接每一步。</p></div><img class="engineering-blueprint engineering-exploded" :src="engineeringExploded" alt="" aria-hidden="true"><span class="dimension dimension-a" aria-hidden="true">3200</span><span class="dimension dimension-b" aria-hidden="true">1420</span></div><ElCard class="login-card" shadow="never">
    <span class="login-kicker">ADMIN & CUSTOMER SERVICE</span><h2>统一登录</h2><p>统一账号密码登录，按后台授权进入管理端或移动端。受邀账号请先打开工作人员发来的开通链接。</p>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" role="alert" />
    <ElForm label-position="top" @submit.prevent="login">
      <ElFormItem label="账号"><ElInput v-model="username" autocomplete="username" maxlength="64" /></ElFormItem>
      <ElFormItem label="密码"><ElInput v-model="password" type="password" autocomplete="current-password" show-password /></ElFormItem>
      <ElButton v-magnetic class="login-submit" type="primary" native-type="submit" :loading="loading">登录工作台</ElButton>
    </ElForm>
  </ElCard></main>
</template>
<style scoped>
.login-page{display:grid;grid-template-columns:minmax(0,1.18fr) minmax(400px,.82fr);min-height:100dvh;overflow:hidden;background:linear-gradient(120deg,#f7f8f8,#fff 72%)}
.login-illustration{position:relative;display:flex;flex-direction:column;justify-content:flex-start;overflow:hidden;padding:50px 7vw;background-color:var(--color-canvas);background-image:linear-gradient(rgba(82,110,110,.055) 1px,transparent 1px),linear-gradient(90deg,rgba(82,110,110,.055) 1px,transparent 1px),linear-gradient(135deg,rgba(255,255,255,.78),transparent 54%);background-size:24px 24px,24px 24px,100% 100%}
.login-illustration::before{position:absolute;inset:0;border-right:1px solid rgba(255,255,255,.92);box-shadow:inset -1px 0 0 rgba(184,190,192,.3);content:'';pointer-events:none}.login-copy{position:relative;z-index:2;max-width:570px;margin-top:90px;padding:24px 28px;border:1px solid rgba(255,255,255,.9);border-radius:16px;background:rgba(255,255,255,.52);box-shadow:inset 0 1px 0 rgba(255,255,255,.96),0 18px 46px rgba(82,110,110,.07);backdrop-filter:blur(12px)}
.login-illustration h1{margin:18px 0;font-size:clamp(32px,3.5vw,54px);line-height:1.22;letter-spacing:-.03em}.login-illustration p{max-width:450px}.engineering-blueprint{position:absolute;right:-3%;bottom:-1%;width:min(1080px,116%);height:auto;filter:grayscale(1) saturate(.18) contrast(.94);opacity:.56;transform:translate3d(0,0,0);animation:blueprint-arrive 720ms cubic-bezier(.16,1,.3,1) both;pointer-events:none}.engineering-exploded{mask-image:linear-gradient(180deg,transparent 0,#000 12%,#000 100%)}.dimension{position:absolute;z-index:1;color:rgba(82,110,110,.38);font:10px ui-monospace,SFMono-Regular,Consolas,monospace;letter-spacing:.08em}.dimension-a{right:9%;bottom:32%}.dimension-b{right:44%;bottom:7%;writing-mode:vertical-rl}
.brand-caption,.login-kicker{position:relative;z-index:2;color:var(--color-accent);font-size:11px;font-weight:700;letter-spacing:.1em}.login-card{position:relative;align-self:center;justify-self:center;width:min(420px,calc(100% - 48px));border:1px solid rgba(255,255,255,.92);border-radius:18px;background:rgba(255,255,255,.82);box-shadow:inset 0 1px 0 #fff,0 28px 70px rgba(82,110,110,.11);backdrop-filter:blur(18px) saturate(.82);animation:form-arrive 520ms 80ms cubic-bezier(.16,1,.3,1) both}.login-card::before{position:absolute;inset:0;border-radius:inherit;background:linear-gradient(120deg,rgba(255,255,255,.38),transparent 34%);content:'';pointer-events:none}.login-card :deep(.el-card__body){position:relative}.login-card h2{margin:14px 0 6px;font-size:28px}.login-card p{margin-bottom:24px}.login-card .el-form{margin-top:0}.login-submit{width:100%;height:44px;margin-top:8px}
@keyframes blueprint-arrive{from{opacity:0;transform:translate3d(24px,12px,0)}to{opacity:.52;transform:translate3d(0,0,0)}}@keyframes form-arrive{from{opacity:0;transform:translate3d(18px,0,0)}to{opacity:1;transform:translate3d(0,0,0)}}
@media(max-width:850px){.login-page{grid-template-columns:1fr;overflow:auto}.login-illustration{min-height:300px;padding:28px}.login-copy{max-width:520px;margin-top:38px;padding:18px;background:rgba(255,255,255,.62)}.login-illustration h1{font-size:26px}.engineering-blueprint{right:-12%;bottom:-10px;width:520px;opacity:.3}.login-card{margin:30px 0}}
@media(prefers-reduced-transparency:reduce){.login-copy,.login-card{background:#fff;backdrop-filter:none}}
@media(prefers-reduced-motion:reduce){.login-page *{animation:none}.engineering-blueprint{opacity:.42}}
</style>
