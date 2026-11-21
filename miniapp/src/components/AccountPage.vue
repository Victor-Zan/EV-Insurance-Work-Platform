<script setup lang="ts">
import BrandHeader from './BrandHeader.vue'
defineProps<{ title: string; description: string; compact?: boolean }>()
</script>
<template>
  <view class="account-page">
    <BrandHeader />
    <view :class="['account-hero', compact && 'compact']">
      <image class="account-scene" src="/static/e-scooter-charging-login.webp" mode="aspectFill" />
      <view class="account-shade"></view>
      <view class="account-copy"><text class="account-eyebrow">电动车保险协作</text><view class="account-headline"><text>让案件处理</text><text>清晰、有序。</text></view><text v-if="!compact" class="account-subtitle">维修进度随时查，车辆交付更安心。</text></view>
    </view>
    <view class="account-card"><text class="account-kicker">车主 · 维修网点</text><text class="account-title">{{ title }}</text><text class="account-description">{{ description }}</text><slot /></view>
  </view>
</template>
<style>
.account-page { min-height: 100vh; padding: 0 14px calc(28px + env(safe-area-inset-bottom)); background: #f5f3ee; }
.account-hero { position: relative; min-height: 260px; margin-top: 14px; overflow: hidden; border: 1px solid #e0dcd3; border-radius: 16px; background: #edeae4; }
.account-scene,.account-shade { position: absolute; inset: 0; width: 100%; height: 100%; }
.account-scene { opacity: .76; }
.account-shade { background: linear-gradient(180deg,rgba(250,248,243,.97),rgba(250,248,243,.78) 45%,rgba(250,248,243,.12)); }
.account-copy { position: relative; padding: 25px 22px 110px; }
.account-eyebrow,.account-headline text,.account-subtitle,.account-kicker,.account-title,.account-description { display: block; }
.account-eyebrow,.account-kicker { color: #706d65; font-size: 12px; letter-spacing: .06em; }
.account-headline { margin-top: 13px; color: #292b2d; font-size: 30px; font-weight: 600; line-height: 1.25; }
.account-subtitle { margin-top: 12px; color: #706d65; font-size: 13px; line-height: 1.6; }
.account-card { position: relative; margin: -28px 12px 0; padding: 25px 22px; border: 1px solid #fff; border-radius: 16px; background: rgba(255,254,250,.97); box-shadow: 0 10px 30px rgba(65,55,38,.08); }
.account-title { margin-top: 7px; color: #292b2d; font-size: 26px; font-weight: 600; }
.account-description { margin-top: 10px; color: #706d65; font-size: 14px; line-height: 1.7; }
.account-hero.compact { min-height: 172px; }
.compact .account-copy { padding: 20px 22px 42px; }
.compact .account-headline { font-size: 25px; }
</style>
