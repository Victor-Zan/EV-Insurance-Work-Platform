<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/shared/http/client'
import { ElButton, ElCard, ElForm, ElFormItem, ElInput, ElAlert } from 'element-plus'
import engineeringExploded from '@/assets/e-scooter-exploded-login.webp'
import batteryArtwork from '@/assets/login-battery.png'
import gearArtwork from '@/assets/login-gear.png'
import wrenchArtwork from '@/assets/login-wrench.png'
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
  <main class="login-page">
    <div class="login-decorations" aria-hidden="true">
      <div class="metal-gears">
        <img class="gear-rotor" :src="gearArtwork" alt="" width="1254" height="1254">
        <img class="gear-satellite" :src="gearArtwork" alt="" width="1254" height="1254">
        <img class="metal-wrench" :src="wrenchArtwork" alt="" width="2172" height="724">
      </div>
      <div class="metal-battery">
        <img :src="batteryArtwork" alt="" width="1774" height="887">
        <svg class="battery-charge" viewBox="0 0 1774 887">
          <rect class="battery-cell charge-one" x="348" y="270" width="146" height="382" rx="28"/>
          <rect class="battery-cell charge-two" x="540" y="270" width="146" height="382" rx="28"/>
          <rect class="battery-cell charge-three" x="732" y="270" width="146" height="382" rx="28"/>
          <rect class="battery-cell charge-four" x="924" y="270" width="146" height="382" rx="28"/>
          <rect class="battery-cell charge-five" x="1116" y="270" width="146" height="382" rx="28"/>
        </svg>
      </div>
    </div>
    <div class="login-illustration"><p class="login-kicker">电动车保险协作</p><div class="login-copy"><span class="brand-caption">e保 / 保险协作台</span><h1>让案件处理<br/>清晰、有序。</h1><p>衔接核损、维修与交付。</p></div><img class="engineering-blueprint engineering-exploded" :src="engineeringExploded" alt="" aria-hidden="true"></div><ElCard class="login-card" shadow="never">
    <span class="login-kicker">客服 / 管理员</span><h2>登录管理端</h2><p>网点与车主请使用移动端。</p>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" role="alert" />
    <ElForm label-position="top" @submit.prevent="login">
      <ElFormItem label="账号"><ElInput v-model="username" aria-label="账号" autocomplete="username" maxlength="64" /></ElFormItem>
      <ElFormItem label="密码"><ElInput v-model="password" aria-label="密码" type="password" autocomplete="current-password" show-password /></ElFormItem>
      <ElButton class="login-submit" type="primary" native-type="submit" :loading="loading">登录工作台</ElButton>
    </ElForm>
  </ElCard></main>
</template>
<style scoped>
.login-page{position:relative;isolation:isolate;display:grid;grid-template-columns:minmax(0,1.18fr) minmax(380px,.82fr);min-height:100dvh;background:linear-gradient(115deg,var(--color-canvas),#fffefa)}
.login-decorations{position:absolute;inset:0;overflow:hidden;pointer-events:none;z-index:1}
.metal-gears{position:absolute;left:calc(59% - 208px);top:28px;width:184px;height:148px;opacity:.62}
.metal-gears>.gear-rotor,.metal-gears>.gear-satellite{position:absolute;width:108px;height:108px;transform-origin:50% 50%}
.gear-rotor{left:0;top:0;animation:engineering-turn 28s linear infinite}
.gear-satellite{left:78px;top:42px;animation:engineering-turn 28s linear infinite reverse;animation-delay:-1.5s}
.metal-wrench{position:absolute;z-index:1;left:108px;top:63px;width:230px;height:auto;transform:rotate(-30deg);transform-origin:24px 33px}
.metal-battery{position:absolute;right:calc(20.5% - 130px);bottom:26px;width:260px;aspect-ratio:2;opacity:.76}
.metal-battery img{display:block;width:100%;height:auto}
.battery-charge{position:absolute;inset:0;width:100%;height:100%;mix-blend-mode:multiply}
.battery-cell{fill:#729d89;opacity:.06;animation:engineering-charge-one 8s ease-in-out infinite}
.charge-two{animation-name:engineering-charge-two}.charge-three{animation-name:engineering-charge-three}.charge-four{animation-name:engineering-charge-four}.charge-five{animation-name:engineering-charge-five}
@keyframes engineering-turn{to{transform:rotate(360deg)}}
@keyframes engineering-charge-one{0%,100%{opacity:.06}9%,91%{opacity:.65}}
@keyframes engineering-charge-two{0%,9%,91%,100%{opacity:.06}18%,82%{opacity:.65}}
@keyframes engineering-charge-three{0%,18%,82%,100%{opacity:.06}27%,73%{opacity:.65}}
@keyframes engineering-charge-four{0%,27%,73%,100%{opacity:.06}36%,64%{opacity:.65}}
@keyframes engineering-charge-five{0%,36%,64%,100%{opacity:.06}45%,55%{opacity:.65}}
.login-illustration{position:relative;display:flex;flex-direction:column;min-height:640px;overflow:hidden;padding:44px 6vw;background-image:radial-gradient(ellipse at 70% 60%,#ffffff9c,transparent 65%)}
.login-illustration::before{position:absolute;inset:0;border-right:1px solid #e0dcd380;content:'';pointer-events:none}
.login-copy{position:relative;z-index:1;max-width:570px;margin-top:60px}
.login-illustration h1{margin:18px 0;font-size:clamp(32px,3.5vw,50px);font-weight:600;line-height:1.25;letter-spacing:-.03em}
.login-illustration p{max-width:450px;font-size:14px}
.engineering-blueprint{position:absolute;right:3%;bottom:12px;width:94%;height:50%;object-fit:contain;filter:grayscale(1) contrast(.9);opacity:.64;animation:blueprint-arrive 500ms var(--motion-fluid) both;pointer-events:none}
.brand-caption,.login-kicker{position:relative;z-index:1;color:var(--color-text-secondary);font-size:12px;font-weight:500;letter-spacing:.06em}
.login-illustration>.login-kicker{color:var(--color-accent);font-size:13px;font-weight:600}
.login-card{position:relative;z-index:2;align-self:center;justify-self:center;width:min(420px,calc(100% - 48px));margin:32px 0;border:1px solid #ffffffed;border-radius:16px;background:var(--glass-context);box-shadow:inset 0 1px 0 #fff,var(--shadow-floating);backdrop-filter:blur(18px) saturate(.85);animation:form-arrive 260ms var(--motion-fluid) both}
.login-card :deep(.el-card__body){padding:32px}
.login-card h2{margin:12px 0 8px;font-size:27px;font-weight:600;letter-spacing:-.02em}
.login-card p{margin-bottom:26px;font-size:13px}
.login-card .el-form{margin-top:20px}.login-card :deep(.el-form-item){margin-bottom:22px}
.login-card :deep(.el-input__wrapper){min-height:42px}.login-submit{width:100%;height:42px;margin-top:6px}
@keyframes blueprint-arrive{from{opacity:0;transform:translate3d(0,8px,0)}to{opacity:.64;transform:translate3d(0,0,0)}}
@keyframes form-arrive{from{opacity:0;transform:translate3d(0,6px,0)}to{opacity:1;transform:translate3d(0,0,0)}}
@media(min-width:851px) and (max-height:700px){.login-copy{margin-top:36px}}
@media(min-width:851px) and (max-height:820px){.metal-battery{bottom:10px;width:220px;right:calc(20.5% - 110px)}}
@media(min-width:851px) and (max-height:760px){.login-card :deep(.el-card__body){padding:24px}.login-card p{margin-bottom:20px}.login-card .el-form{margin-top:16px}.login-card :deep(.el-form-item){margin-bottom:16px}}
@media(min-width:851px) and (max-height:700px){.metal-battery{bottom:0;width:180px;right:calc(20.5% - 90px)}}
@media(max-width:1050px){.metal-gears{left:calc(59% - 174px);width:150px;transform:scale(.8);transform-origin:top left}}
@media(max-width:850px){.metal-gears{display:none}.metal-battery{top:12px;right:20px;bottom:auto;width:150px}}
@media(max-width:850px){.login-page{grid-template-columns:1fr}.login-illustration{min-height:330px;padding:28px}.login-copy{max-width:70%;margin-top:30px}.login-illustration h1{font-size:30px}.engineering-blueprint{right:0;bottom:8px;width:55%;height:65%;opacity:.25;animation:none}.login-card{width:min(480px,calc(100% - 40px));margin:24px 0 40px}}
@media(max-width:440px){.login-illustration{min-height:300px}.login-copy{max-width:100%}.engineering-blueprint{width:65%;opacity:.13}.login-card :deep(.el-card__body){padding:24px}}
@media(prefers-reduced-transparency:reduce){.login-card{background:#fffefa;backdrop-filter:none}}
@supports not (backdrop-filter:blur(1px)){.login-card{background:#fffefa}}
@media(prefers-reduced-motion:reduce){.login-page *{animation:none}.battery-cell{opacity:.4}}
</style>
