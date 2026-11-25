<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { downloadPrivateFile, releasePrivateFile } from '../services/files'
import { onSessionCleared, sameSession, sessionGeneration } from '../services/session'
import { photoLabel, requireOwner, type Photo } from '../services/owner'
const props = defineProps<{ photo: Photo }>()
const path = ref(''), busy = ref(false), error = ref('')
let alive = true, revision = 0
function release() { revision++; if (path.value) releasePrivateFile(path.value); path.value = ''; busy.value = false; error.value = '' }
const stop = onSessionCleared(release)
onBeforeUnmount(() => { alive = false; release(); stop() })
watch(() => `${props.photo.id}:${props.photo.version}`, release)
async function load() {
  if (busy.value) return
  if (path.value) { releasePrivateFile(path.value); path.value = '' }
  const current = ++revision, stamp = sessionGeneration()
  busy.value = true; error.value = ''
  try {
    requireOwner()
    const file = await downloadPrivateFile(props.photo.id)
    if (!alive || current !== revision || !sameSession(stamp)) { releasePrivateFile(file); return }
    path.value = file
  } catch (cause) { if (alive && current === revision) error.value = cause instanceof Error ? cause.message : '照片加载失败，请重试。' }
  finally { if (alive && current === revision) busy.value = false }
}
function preview() {
  try { requireOwner(); if (path.value) uni.previewImage({ urls: [path.value], current: path.value, fail: () => { error.value = '此照片暂不能预览。' } }) }
  catch { release() }
}
</script>
<template>
  <view class="owner-photo">
    <image v-if="path" class="photo-preview" :src="path" mode="aspectFill" @tap="preview" @error="error = '照片无法显示，请重新加载。'" />
    <button v-else class="photo-placeholder" :loading="busy" :disabled="busy" @tap="load">{{ busy ? '正在加载' : '查看照片' }}</button>
    <text class="photo-caption">{{ photoLabel(photo.category) }}</text>
    <text v-if="error" class="photo-error">{{ error }}</text><text v-if="error" class="photo-retry" @tap="load">重新加载</text>
  </view>
</template>
<style scoped>
.owner-photo { min-width: 0; }.photo-preview,.photo-placeholder { width: 100%; height: 108px; margin: 0; border: 1px solid #e0dcd3; border-radius: 10px; background: #f5f3ee; }
.photo-placeholder { display: flex; align-items: center; justify-content: center; padding: 8px; color: #706d65; font-size: 13px; line-height: 1.6; }
.photo-caption,.photo-error,.photo-retry { display: block; margin-top: 6px; color: #706d65; font-size: 12px; line-height: 1.5; }.photo-error { color: #a83b3b; }.photo-retry { color: #35383b; }
</style>
