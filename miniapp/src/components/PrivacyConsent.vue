<script setup lang="ts">
import { nextTick, onBeforeUnmount, watch } from 'vue'
import { agreePrivacy, cancelPrivacy, privacyButtonId, privacyExposed, privacyHost, privacyPrompt, viewPrivacy } from '../services/privacy'
const props = defineProps<{ active: boolean }>(), host = Symbol('privacy-host')
watch(() => props.active, active => privacyHost(host, active), { immediate: true })
watch(privacyPrompt, async value => { if (value && props.active) { await nextTick(); privacyExposed() } })
onBeforeUnmount(() => privacyHost(host, false))
</script>
<template><view v-if="active && privacyPrompt" class="privacy-mask" @touchmove.stop.prevent><view class="privacy-sheet" aria-role="dialog" aria-label="图片隐私授权"><text class="privacy-title">图片使用说明</text><text class="privacy-copy">选择维修图片前，请阅读并同意隐私指引。仅在你主动选择并提交后上传图片。</text><text class="privacy-policy" @tap="viewPrivacy">{{ privacyPrompt.name }}</text><text v-if="privacyPrompt.error" class="privacy-error">{{ privacyPrompt.error }}</text><button :id="privacyButtonId" class="privacy-agree" open-type="agreePrivacyAuthorization" :disabled="privacyPrompt.submitting || !privacyPrompt.canAgree" @agreeprivacyauthorization="agreePrivacy">同意并继续</button><button class="privacy-cancel" :disabled="privacyPrompt.submitting" @tap="cancelPrivacy('PRIVACY_DECLINED')">暂不使用图片</button></view></view></template>
<style scoped>
.privacy-mask{position:fixed;z-index:1000;inset:0;display:flex;align-items:flex-end;background:rgba(35,37,39,.38);padding:16px 16px calc(16px + env(safe-area-inset-bottom));box-sizing:border-box}.privacy-sheet{width:100%;box-sizing:border-box;background:#fffefa;border:1px solid #e0dcd3;border-radius:18px;padding:24px}.privacy-title{display:block;font-size:22px;font-weight:700;color:#292b2d;margin-bottom:14px}.privacy-copy{display:block;color:#706d65;font-size:14px;line-height:1.8}.privacy-policy{display:block;margin:16px 0;color:#35383b;font-size:15px;text-decoration:underline;line-height:1.7}.privacy-error{display:block;color:#a13030;font-size:13px;line-height:1.7;margin-bottom:10px}.privacy-agree,.privacy-cancel{margin-top:12px;border-radius:9px;font-size:15px;line-height:46px}.privacy-agree{background:#35383b;color:#fffefa}.privacy-cancel{background:#f5f3ee;color:#706d65}
</style>
