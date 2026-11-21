<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { downloadPrivateFile, downloadQuoteOcrImage, releasePrivateFile } from '../services/files'
import { requireShop } from '../services/shop'
import { onSessionCleared, sameSession, sessionGeneration } from '../services/session'
const props = defineProps<{ active: boolean; photoId?: string; caseId?: string; jobId?: string; version: number; caption?: string }>()
const path = ref(''), busy = ref(false), error = ref('')
let alive = true, sequence = 0
function release() { sequence++; if (path.value) releasePrivateFile(path.value); path.value = ''; busy.value = false }
const stop = onSessionCleared(release)
watch(() => `${props.photoId}:${props.jobId}:${props.version}:${props.active}`, release)
onBeforeUnmount(() => { alive = false; release(); stop() })
async function load() {
  if (!props.active || busy.value) return
  release(); const current = ++sequence, stamp = sessionGeneration(); busy.value = true; error.value = ''
  try {
    requireShop()
    const file = props.photoId ? await downloadPrivateFile(props.photoId) : await downloadQuoteOcrImage(props.caseId!, props.jobId!)
    if (!alive || !props.active || current !== sequence || !sameSession(stamp)) { releasePrivateFile(file); return }
    path.value = file
  } catch (cause) { if (alive && current === sequence && sameSession(stamp)) error.value = cause instanceof Error ? cause.message : '图片加载未完成。' }
  finally { if (current === sequence) busy.value = false }
}
function preview() { if (props.active && path.value) { requireShop(); uni.previewImage({ urls: [path.value], current: path.value, fail: () => { error.value = '图片暂不能预览，请重试。' } }) } }
</script>
<template><view><image v-if="path" :src="path" class="shop-image" mode="aspectFit" @tap="preview" @error="error = '图片暂不能显示，请重新加载。'" /><button v-else class="shop-image shop-image-placeholder" :disabled="busy || !active" :loading="busy" @tap="load">{{ jobId ? '查看原件' : '查看照片' }}</button><text v-if="caption" class="shop-muted">{{ caption }}</text><text v-if="error" class="shop-error">{{ error }}<text @tap="load">重新加载</text></text></view></template>
