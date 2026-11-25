<script setup lang="ts">
import { ref } from 'vue'
import { onResize } from '@dcloudio/uni-app'
import { isLocalDemo, isNativePreview, type DemoRole } from '@local-preview'
import { enterNativePreview } from '../services/auth'

function previewAs(role: DemoRole) {
  enterNativePreview(role)
  uni.reLaunch({ url: '/pages/index/index' })
}

const inset = ref(0)
const toolbarStyle = ref<Record<string, string>>({})
// #ifdef MP-WEIXIN
function measureHeader() {
  const { statusBarHeight, windowWidth } = uni.getWindowInfo()
  const menu = uni.getMenuButtonBoundingClientRect()
  const statusHeight = Number.isFinite(statusBarHeight) ? Math.max(0, statusBarHeight) : 44
  const hasMenu = Number.isFinite(menu.top) && Number.isFinite(menu.height) && menu.height > 0
  const toolbarHeight = hasMenu ? Math.max(56, menu.height + 16) : 56
  // Centre the 44px brand beside the capsule while keeping it below the status text.
  // The bar background may extend upwards into the unused bottom of the status area.
  const toolbarCenter = hasMenu ? Math.max(statusHeight + 22, menu.top + menu.height / 2) : statusHeight + toolbarHeight / 2
  const toolbarTop = Math.max(0, toolbarCenter - toolbarHeight / 2)
  const rightInset = Number.isFinite(windowWidth) && Number.isFinite(menu.left) && menu.left > 0
    ? Math.max(16, windowWidth - menu.left + 12) : 106
  inset.value = toolbarTop + toolbarHeight
  toolbarStyle.value = { top: `${toolbarTop}px`, height: `${toolbarHeight}px`, paddingRight: `${rightInset}px` }
}
measureHeader()
onResize(measureHeader)
// #endif
</script>

<template>
  <view class="brand-header-space" :style="inset ? { height: `${inset}px` } : {}">
    <!-- #ifdef MP-WEIXIN -->
    <view class="wechat-system-cover" :style="inset ? { height: `${inset}px` } : {}"></view>
    <!-- #endif -->
    <view class="topbar" :style="toolbarStyle">
      <view class="brand">
        <view class="brand-mark"><image src="/static/icons/shield.svg" mode="aspectFit" /></view>
        <view class="brand-copy"><text class="brand-name">e保</text><text class="brand-subtitle">保险维修服务</text></view>
      </view>
    </view>
  </view>
  <view v-if="isLocalDemo" class="local-demo-notice">
    <text>{{ isNativePreview ? '开发预览 · 免登录 · 示例数据 · 不连接真实后台' : '本机演示 · 示例账号与数据 · 不连接真实后台' }}</text>
    <view v-if="isNativePreview" class="preview-roles"><button @tap="previewAs('OWNER')">车主视角</button><button @tap="previewAs('REPAIR_SHOP')">网点视角</button></view>
  </view>
</template>

<style scoped>
.brand-header-space { margin: 0 -14px; }
.local-demo-notice { margin: 0 -14px; padding: 6px 14px; border-bottom: 1px solid #e0dcd3; background: #efe9d9; color: #746440; text-align: center; font-size: 12px; }
.preview-roles { display: flex; justify-content: center; gap: 8px; margin-top: 5px; }.preview-roles button { margin: 0; padding: 0 12px; background: #fffefa; color: #514c42; font-size: 12px; line-height: 28px; }.preview-roles button::after { border-color: #d6d1c6; }
.topbar { position: relative; display: flex; align-items: center; min-height: 76px; padding: 12px 16px; overflow: hidden; border-bottom: 1px solid #e7e1d8; background: linear-gradient(120deg,#fffdfa,#eee9e2); }
.brand { position: relative; z-index: 1; display: flex; align-items: center; gap: 11px; }
.brand-copy { display: flex; min-height: 44px; flex-direction: column; justify-content: center; }
.brand-mark { display: flex; width: 44px; height: 44px; align-items: center; justify-content: center; border: 1px solid #fff; border-radius: 12px; background: #fff; box-shadow: 0 5px 14px rgba(65,55,38,.06); }
.brand-mark image { width: 26px; height: 26px; }
.brand-name,.brand-subtitle { display: block; }
.brand-name { color: #292b2d; font-size: 20px; font-weight: 700; line-height: 1.15; }
.brand-subtitle { margin-top: 3px; color: #706d65; font-size: 12px; line-height: 1.25; }
/* #ifdef MP-WEIXIN */
.brand-header-space { height: 100px; }
.wechat-system-cover { position: fixed; top: 0; right: 0; left: 0; z-index: 40; height: 100px; pointer-events: none; background: #f5f2ed; }
.topbar { position: fixed; top: 44px; right: 0; left: 0; z-index: 41; height: 56px; min-height: 0; align-items: center; padding: 0 106px 0 16px; }
/* #endif */
</style>
