<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { cancelRequests } from '../services/client'
import { sameSession, sessionGeneration, onSessionCleared } from '../services/session'
import { displayTime, ownerApi, type ComplaintEvent } from '../services/owner'
const props = defineProps<{ id: string; active: boolean; revision: number }>()
const rows = ref<ComplaintEvent[]>([]), page = ref(0), total = ref(0), busy = ref(false), error = ref(''), opened = ref(false)
const scope = `complaint-history-${props.id}`
let serial = 0, alive = true
function pause() { serial++; cancelRequests(scope); busy.value = false }
const stop = onSessionCleared(() => { pause(); rows.value = []; opened.value = false })
onBeforeUnmount(() => { alive = false; pause(); stop() })
watch(() => props.active, active => { if (!active) pause(); else if (opened.value) void load(true) })
watch(() => props.revision, () => { if (props.active && opened.value) void load(true) })
async function load(reset = false) {
  if (!props.active || busy.value) return
  busy.value = true; error.value = ''; opened.value = true
  const stamp = sessionGeneration(), current = ++serial
  try {
    const data = await ownerApi.complaintHistory(props.id, reset ? 1 : page.value + 1, scope)
    if (!alive || !props.active || current !== serial || !sameSession(stamp)) return
    rows.value = reset ? data.records : [...rows.value, ...data.records.filter(row => !rows.value.some(old => old.id === row.id))]
    page.value = data.page; total.value = data.total
  } catch (cause) { if (alive && current === serial && sameSession(stamp)) { rows.value = []; page.value = 0; error.value = cause instanceof Error ? cause.message : '处理记录暂不可查看。' } }
  finally { if (current === serial) busy.value = false }
}
</script>
<template>
  <view><button class="history-button" :disabled="busy || !active" @tap="load(true)">{{ busy ? '加载中…' : opened ? '刷新处理记录' : '查看处理记录' }}</button>
    <text v-if="error" class="history-note">{{ error }}</text>
    <view v-for="row in rows" :key="row.id" class="history-row"><text>{{ row.note || '处理记录已更新' }}</text><text class="history-note">{{ displayTime(row.createdAt) }}</text></view>
    <text v-if="opened && !busy && !error && !rows.length" class="history-note">暂无公开处理记录</text>
    <button v-if="page * 20 < total && !error" class="history-button" :disabled="busy || !active" @tap="load(false)">更多处理记录</button>
  </view>
</template>
<style scoped>
.history-button { margin: 12px 0; border: 1px solid #e0dcd3; border-radius: 8px; color: #35383b; background: #fffefa; font-size: 13px; line-height: 38px; }.history-row { padding: 12px 0; border-top: 1px solid #e0dcd3; font-size: 14px; line-height: 1.7; }.history-note { display: block; color: #77746b; font-size: 12px; line-height: 1.7; }
</style>
