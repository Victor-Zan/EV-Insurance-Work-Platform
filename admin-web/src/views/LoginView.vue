<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/shared/http/client'
import { ElButton, ElCard, ElForm, ElFormItem, ElInput, ElAlert } from 'element-plus'
import engineeringExploded from '@/assets/e-scooter-exploded-login.webp'
const router = useRouter()
const username = ref(''), password = ref(''), error = ref(''), loading = ref(false)
const motionPaused = ref(false)
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
  <main class="login-page" :class="{'motion-paused':motionPaused}">
    <div class="login-decorations" aria-hidden="true">
      <svg class="schematic schematic-gears" viewBox="0 0 160 150" fill="none">
        <path class="construction-line" d="M12 54h136M58 10v126M22 118h116"/>
        <circle class="construction-line" cx="58" cy="54" r="48" stroke-dasharray="2 5"/>
        <g class="gear-rotor">
          <path d="m50 15 16 0 2 10 7 3 9-6 11 11-6 9 3 7 10 2v16l-10 2-3 7 6 9-11 11-9-6-7 3-2 10H50l-2-10-7-3-9 6-11-11 6-9-3-7-10-2V51l10-2 3-7-6-9 11-11 9 6 7-3z" transform="translate(6 0) scale(.9)"/>
          <circle cx="58" cy="54" r="21"/><circle cx="58" cy="54" r="10"/>
          <path d="M58 33v11m0 20v11M37 54h11m20 0h11"/>
        </g>
        <g class="gear-satellite">
          <path d="m108 88 10-2 3 6 6 2 6-3 7 8-4 5 1 6 6 4-2 10-7 1-4 4-1 7-10 2-3-6-6-2-6 3-7-8 4-5-1-6-6-4 2-10 7-1 4-4z"/>
          <circle cx="114" cy="111" r="12"/><circle cx="114" cy="111" r="5"/>
        </g>
        <path class="construction-line" d="m127 136 19-10m-1-4 3 6M11 126h38m-38-3v6m38-6v6"/>
      </svg>
      <svg class="schematic schematic-wrench" viewBox="0 0 184 100" fill="none">
        <path class="construction-line" d="M14 78h155M119 5v60m-33-37h65"/>
        <circle class="construction-line" cx="119" cy="28" r="24" stroke-dasharray="3 5"/>
        <path d="m119 21 6 3.5v7L119 35l-6-3.5v-7z"/>
        <g class="wrench-arm">
          <path d="M103 20a19 19 0 0 0 4 26L59 74a9 9 0 1 0 10 14l49-42a19 19 0 0 0 19-25l-11 12-11-3-3-10 11-12a19 19 0 0 0-20 12Z"/>
          <circle cx="64" cy="81" r="3"/><path class="construction-line" d="m74 73 29-25"/>
        </g>
        <path class="construction-line" d="M151 53v29m-4-29h8m-8 29h8M31 22h32m-16-6v12"/>
      </svg>
      <svg class="schematic schematic-battery" viewBox="0 0 190 74" fill="none">
        <path class="construction-line" d="M8 37h24m120 0h30M42 8h100m-100-3v6m100-6v6M42 66h100"/>
        <rect x="38" y="19" width="108" height="36" rx="4"/><path d="M146 30h7v14h-7"/>
        <path d="M24 29v16m-5-8h10M165 37h10"/>
        <rect class="battery-cell" x="46" y="27" width="17" height="20" rx="1"/>
        <rect class="battery-cell charge-two" x="71" y="27" width="17" height="20" rx="1"/>
        <rect class="battery-cell charge-three" x="96" y="27" width="17" height="20" rx="1"/>
        <rect class="battery-cell charge-four" x="121" y="27" width="17" height="20" rx="1"/>
        <circle cx="16" cy="37" r="2"/><circle cx="180" cy="37" r="2"/>
      </svg>
    </div>
    <button class="decoration-toggle" type="button" :aria-label="motionPaused?'播放装饰动效':'暂停装饰动效'" :title="motionPaused?'播放装饰动效':'暂停装饰动效'" :aria-pressed="motionPaused" @click="motionPaused=!motionPaused">
      <svg viewBox="0 0 16 16" fill="none" aria-hidden="true"><path v-if="motionPaused" d="m5 3 7 5-7 5Z" fill="currentColor"/><path v-else d="M5 3v10m6-10v10" stroke="currentColor" stroke-width="2"/></svg>
    </button>
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
.schematic{position:absolute;color:#77786f;stroke:currentColor;stroke-width:1.3;stroke-linecap:round;stroke-linejoin:round;opacity:.38}
.construction-line{stroke-width:.7;opacity:.5}
.schematic-gears{left:calc(59% - 164px);top:36px;width:132px;height:124px}
.gear-rotor{transform-origin:58px 54px;animation:engineering-turn 32s linear infinite}
.gear-satellite{transform-origin:114px 111px;animation:engineering-turn 23s linear infinite reverse}
.schematic-wrench{right:calc(20.5% - 78px);top:24px;width:156px;height:85px}
.wrench-arm{transform-origin:119px 28px;animation:engineering-tighten 5.5s ease-in-out infinite}
.schematic-battery{right:calc(20.5% - 95px);bottom:30px;width:190px;height:74px}
.battery-cell{fill:currentColor;stroke:none;opacity:.45}
.charge-two{animation:engineering-charge-two 8s linear infinite}.charge-three{animation:engineering-charge-three 8s linear infinite}.charge-four{animation:engineering-charge-four 8s linear infinite}
.decoration-toggle{position:absolute;z-index:3;right:20px;top:16px;display:grid;place-items:center;width:28px;height:28px;padding:6px;border:1px solid var(--color-border);border-radius:50%;color:var(--color-text-secondary);background:transparent;cursor:pointer}
.decoration-toggle:hover{background:var(--color-accent-soft)}.decoration-toggle:focus-visible{outline:2px solid var(--color-accent);outline-offset:3px}.decoration-toggle svg{width:14px;height:14px}
.motion-paused .login-decorations *{animation-play-state:paused}
@keyframes engineering-turn{to{transform:rotate(360deg)}}
@keyframes engineering-tighten{0%,18%,100%{transform:rotate(-9deg)}48%,68%{transform:rotate(13deg)}}
@keyframes engineering-charge-two{0%,15%,100%{opacity:.06}25%,88%{opacity:.45}}
@keyframes engineering-charge-three{0%,38%,100%{opacity:.06}48%,88%{opacity:.45}}
@keyframes engineering-charge-four{0%,61%,100%{opacity:.06}71%,88%{opacity:.45}}
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
@media(min-width:851px) and (max-height:760px){.schematic-wrench{top:10px;height:56px}.schematic-battery{bottom:12px;height:52px}}
@media(max-width:850px){.schematic-gears,.schematic-wrench{display:none}.schematic-battery{top:18px;right:60px;bottom:auto;width:130px;height:50px}}
@media(max-width:850px){.login-page{grid-template-columns:1fr}.login-illustration{min-height:330px;padding:28px}.login-copy{max-width:70%;margin-top:30px}.login-illustration h1{font-size:30px}.engineering-blueprint{right:0;bottom:8px;width:55%;height:65%;opacity:.25;animation:none}.login-card{width:min(480px,calc(100% - 40px));margin:24px 0 40px}}
@media(max-width:440px){.login-illustration{min-height:300px}.login-copy{max-width:100%}.engineering-blueprint{width:65%;opacity:.13}.login-card :deep(.el-card__body){padding:24px}}
@media(prefers-reduced-transparency:reduce){.login-card{background:#fffefa;backdrop-filter:none}}
@supports not (backdrop-filter:blur(1px)){.login-card{background:#fffefa}}
@media(prefers-reduced-motion:reduce){.login-page *{animation:none}.decoration-toggle{display:none}}
</style>
