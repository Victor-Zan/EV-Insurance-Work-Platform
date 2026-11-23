<script setup lang="ts">
import { ref,onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { httpClient,authSession } from '@/shared/http/client'
import {unifiedLogin,consumeHandoff} from '@/shared/auth/account-login'
import { ElButton, ElCard, ElForm, ElFormItem, ElInput, ElAlert } from 'element-plus'
import showcaseArtwork from '@/assets/login-showcase.png'
import batteryArtwork from '@/assets/login-battery.png'
import gearArtwork from '@/assets/login-gear.png'
import wrenchArtwork from '@/assets/login-wrench.png'
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
  <main class="login-page">
    <div class="login-stage">
      <div class="login-illustration">
        <p class="login-kicker">电动车保险协作</p>
        <div class="login-copy">
          <span class="brand-caption">e保 / 保险协作台</span>
          <h1>让案件处理<br>清晰、有序。</h1>
          <p>衔接核损、维修与交付。</p>
        </div>
      </div>
      <div class="login-scene" aria-hidden="true">
        <img class="showcase-artwork" :src="showcaseArtwork" alt="" width="1586" height="992" fetchpriority="high">
        <img class="gear-rotor" :src="gearArtwork" alt="" width="1254" height="1254">
        <img class="gear-satellite" :src="gearArtwork" alt="" width="1254" height="1254">
        <img class="metal-wrench" :src="wrenchArtwork" alt="" width="2172" height="724">
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
      <ElCard class="login-card" :class="{ 'has-error': error }" shadow="never">
        <span class="login-kicker">客服 / 管理员</span>
        <h2>统一登录</h2>
        <p>统一账号密码登录，按后台授权进入管理端或移动端。受邀账号请先打开工作人员发来的开通链接。</p>
        <ElAlert v-if="error" :title="error" type="error" :closable="false" role="alert" />
        <ElForm label-position="top" @submit.prevent="login">
          <ElFormItem label="账号"><ElInput v-model="username" aria-label="账号" autocomplete="username" maxlength="64" /></ElFormItem>
          <ElFormItem label="密码"><ElInput v-model="password" aria-label="密码" type="password" autocomplete="current-password" show-password /></ElFormItem>
          <ElButton class="login-submit" type="primary" native-type="submit" :loading="loading">登录工作台</ElButton>
        </ElForm>
      </ElCard>
    </div>
  </main>
</template>
<style scoped>
.login-page{min-height:100dvh;display:grid;place-items:center;background:#eee9e2}
.login-stage{position:relative;isolation:isolate;container-type:inline-size;width:min(100%,max(1088px,160dvh));min-height:680px;aspect-ratio:8/5}
.login-scene{position:absolute;inset:0;z-index:-1;pointer-events:none}
.showcase-artwork{display:block;width:100%;height:100%}
.gear-rotor,.gear-satellite,.metal-wrench,.metal-battery{position:absolute;height:auto}
.gear-rotor,.gear-satellite{width:7%;filter:drop-shadow(0 3px 3px #443a2c45)}
.gear-rotor{left:43.8%;top:6.9%;animation:engineering-turn 28s linear infinite}
.gear-satellite{left:49.1%;top:11.1%;animation:engineering-turn 28s linear infinite reverse;animation-delay:-1.5s}
.metal-wrench{left:51.5%;top:14.3%;width:13.6%;transform:rotate(-30deg);transform-origin:10.4% 43%;filter:drop-shadow(0 3px 3px #443a2c36)}
.metal-battery{left:70.6%;top:79.7%;width:18%;aspect-ratio:2;filter:drop-shadow(0 3px 3px #443a2c40)}
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
.login-illustration{position:absolute;top:4.8%;left:6%;width:40%}
.login-illustration>.login-kicker{margin:0;color:var(--color-accent);font-size:13px;font-weight:600}
.login-copy{margin-top:clamp(48px,4.7cqw,76px)}
.brand-caption,.login-kicker{color:var(--color-text-secondary);font-size:12px;font-weight:500;letter-spacing:.06em}
.login-illustration h1{margin:18px 0;font-size:clamp(36px,3.5cqw,56px);font-weight:600;line-height:1.25;letter-spacing:-.03em}
.login-illustration .login-copy p{margin:0;font-size:14px}
.login-card{position:relative;margin:0 0 0 64.7%;top:25.7%;width:29.5%;border:1px solid #ffffffb3;border-radius:18px;background:rgba(255,252,247,.9);box-shadow:0 16px 28px #443a2c26,inset 0 1px 0 #fff;backdrop-filter:blur(18px) saturate(.85);animation:form-arrive 260ms var(--motion-fluid) both}
.login-card :deep(.el-card__body){padding:clamp(24px,2.4cqw,38px)}
.login-card h2{margin:12px 0 8px;font-size:clamp(26px,2.1cqw,34px);font-weight:600;letter-spacing:-.02em}
.login-card p{margin:0 0 20px;font-size:13px;line-height:1.65}
.login-card .el-form{margin-top:16px}.login-card :deep(.el-form-item){margin-bottom:20px}
.login-card :deep(.el-input__wrapper){min-height:42px;background:#fffefe}
.login-submit{width:100%;height:42px;margin-top:6px}
.login-card :deep(.el-alert){margin-bottom:16px}
@keyframes form-arrive{from{opacity:0;transform:translate3d(0,6px,0)}to{opacity:1;transform:translate3d(0,0,0)}}
@media(min-width:1089px){
@container (max-width:1350px){
.login-card{top:24%}.login-card.has-error{top:18%}
.login-card :deep(.el-card__body){padding:24px}.login-card h2{font-size:26px;margin-top:10px}
.login-card p{margin-bottom:14px}.login-card .el-form{margin-top:12px}
.login-card :deep(.el-form-item){margin-bottom:14px}
.login-card :deep(.el-input__wrapper){min-height:40px}.login-submit{height:40px}
}
}
@media(max-width:1088px){
.login-page{background:var(--color-canvas)}
.login-stage{width:100%;min-height:100dvh;aspect-ratio:auto;padding:0 0 32px}
.login-scene{position:relative;aspect-ratio:8/5}
.login-illustration{position:relative;top:auto;left:auto;width:100%;padding:24px 6%}
.login-copy{margin-top:5vw}.login-illustration h1{font-size:clamp(24px,4.5vw,40px);margin:12px 0}
.login-card{top:auto;width:min(480px,calc(100% - 40px));margin:24px auto 0}
.login-card :deep(.el-card__body){padding:28px}
}
@media(max-width:540px){
.login-illustration{padding:20px 24px 16px}
.login-copy{margin-top:16px}.brand-caption{font-size:11px}
.login-illustration h1{font-size:28px;margin:10px 0}.login-illustration .login-copy p{font-size:12px}
.login-card{margin-top:16px}.login-card :deep(.el-card__body){padding:24px}
}
@media(prefers-reduced-transparency:reduce){.login-card{background:#fffefa;backdrop-filter:none}}
@supports not (backdrop-filter:blur(1px)){.login-card{background:#fffefa}}
@media(prefers-reduced-motion:reduce){.login-card,.gear-rotor,.gear-satellite,.battery-cell{animation:none}.battery-cell{opacity:.4}}
</style>