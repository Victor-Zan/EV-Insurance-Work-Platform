<script setup lang="ts">
import './styles.css'
import './styles/account.css'
import './styles/shop.css'
import { onLaunch, onHide, onShow } from '@dcloudio/uni-app'
import { currentSession, revalidateSession, returnToLogin, enterNativePreview } from './services/auth'
import { appVisible } from './services/lifecycle'
onLaunch(() => { enterNativePreview() })
onHide(() => { appVisible.value = false })
onShow(() => {
  appVisible.value = true
  if (currentSession()) void revalidateSession().catch(() => { if (!currentSession()) returnToLogin() })
})
</script>

<template>
  <view class="app-shell"><slot /></view>
</template>
