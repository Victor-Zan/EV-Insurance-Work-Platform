<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import BrandHeader from '../../components/BrandHeader.vue'
import { currentSession, loginWithWeChat, loginWithDemo, enterNativePreview } from '../../services/auth'
import { isLocalDemo, type DemoRole } from '@local-preview'
import { serviceConfigured } from '../../services/client'
import { useAccountFeedback } from '../../services/account-feedback'
const { error, remaining, busy, failed } = useAccountFeedback()
const configured = serviceConfigured()
onShow(() => { if (!currentSession()) enterNativePreview(); if (currentSession()) uni.reLaunch({ url: '/pages/index/index' }) })
async function login() {
  if (busy.value || remaining.value) return
  error.value = ''
  try { await loginWithWeChat(); uni.reLaunch({ url: '/pages/index/index' }) }
  catch (cause) { failed(cause) }
}
async function localLogin(role: DemoRole) {
  if (busy.value) return
  error.value = ''
  try { await loginWithDemo(role); uni.reLaunch({ url: '/pages/index/index' }) }
  catch (cause) { failed(cause) }
}
</script>
<template>
  <view class="login-page"><BrandHeader />
    <view class="login-main">
      <view class="login-scene"><text class="login-scene-title">维修有序，交付安心。</text><image src="/static/repair-showcase.jpg" mode="aspectFit" /></view>
      <view class="login-card"><text class="login-title">欢迎使用 e保</text><text class="login-subtitle">维修进度随时查，车辆交付更安心。</text>
        <template v-if="isLocalDemo"><button class="wechat-login" :disabled="busy" @tap="localLogin('OWNER')">车主演示登录</button><button class="wechat-login demo-shop" :disabled="busy" @tap="localLogin('REPAIR_SHOP')">网点演示登录</button><text class="login-preview">无需微信或密码 · 退出后可登录另一演示账号</text></template>
        <button v-else class="wechat-login" :loading="busy" :disabled="busy || remaining > 0" @tap="login">{{ remaining ? `请稍候 ${remaining} 秒` : busy ? '正在登录…' : '微信登录' }}</button>
        <text v-if="error" class="login-error">{{ error }}</text>
      </view>
      <text v-if="isLocalDemo" class="login-preview">本机演示 · 全部为示例数据，刷新页面后重置</text><text v-else-if="!configured" class="login-preview">本机预览 · 登录服务尚未连接</text>
    </view>
  </view>
</template>
<style scoped>
.login-page { min-height: 100vh; padding: 0 14px calc(24px + env(safe-area-inset-bottom)); background: #f5f2ed; color: #292b2d; }
.login-main { width: 100%; max-width: 460px; margin: 20px auto 0; }
.login-scene { position: relative; height: 270px; overflow: hidden; border: 1px solid #ebe3d9; border-radius: 22px; background: #eee7df; }
.login-scene image { position: absolute; right: 0; bottom: 0; width: 100%; height: 240px; }
.login-scene-title { position: relative; z-index: 1; display: block; padding: 24px 22px; font-size: 24px; line-height: 1.4; font-weight: 600; letter-spacing: -.5px; }
.login-card { position: relative; margin: -18px 10px 0; padding: 28px 22px 24px; border: 1px solid #fff; border-radius: 20px; background: #fffdfa; box-shadow: 0 14px 32px rgba(65,55,38,.09), inset 0 1px 0 #fff; text-align: left; }
.login-title,.login-subtitle,.login-error,.login-preview { display: block; }
.login-title { font-size: 24px; font-weight: 600; line-height: 1.5; }.login-subtitle { margin-top: 8px; color: #706d65; font-size: 14px; line-height: 1.7; }
.wechat-login { margin-top: 24px; padding: 0 16px; border-radius: 12px; background: #35383b; color: #fffefa; font-size: 16px; line-height: 48px; box-shadow: 0 4px 10px rgba(53,56,59,.12); }
.wechat-login[disabled] { opacity: .6; background: #686a6b; color: #fffefa; }
.demo-shop { margin-top: 12px; border: 1px solid #d6d1c6; background: #fffefa; color: #35383b; box-shadow: none; }
.login-error { margin-top: 14px; color: #a83b3b; font-size: 13px; line-height: 1.7; }.login-preview { margin: 18px 0 0; color: #706d65; font-size: 11px; line-height: 1.7; text-align: center; }
</style>
