<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import ShopImage from './ShopImage.vue'
import { cancelRequests, RequestError } from '../services/client'
import { onSessionCleared, sameSession, sessionGeneration } from '../services/session'
import { shopApi, type QuoteContext, type ShopCase, type ShopPhoto } from '../services/shop'
import { canOpenCompletion, repairCommand, repairPending, shopRepairApi, startRepairCommand, type RepairEntry, type RepairPending, type Review, type Settlement, type ShopRepair } from '../services/shop-repair'
import { MaterialUploadQueue, createMaterialUpload } from '../services/upload'
import { pickOriginalImages } from '../services/pick-images'
import { displayTime } from '../services/owner'
const props = defineProps<{ row: ShopCase; active: boolean; mode: 'repair' | 'settlement' }>()
const emit = defineEmits<{ changed: []; busy: [boolean]; failed: [unknown] }>()
const data = ref<ShopRepair | null>(null), quote = ref<QuoteContext | null>(null), settlement = ref<Settlement | null>(null)
const photos = ref<ShopPhoto[]>([]), history = ref<RepairEntry[]>([]), reviews = ref<Review[]>([])
const photoPage = ref(1), photoTotal = ref(0), historyPage = ref(1), historyTotal = ref(0), reviewPage = ref(0), reviewTotal = ref(0)
const pending = ref<RepairPending | null>(repairPending.get(props.row.id) ?? null), note = ref(pending.value?.note ?? ''), selected = ref<string[]>(pending.value?.photoIds ?? [])
const category = ref<'PROGRESS_PHOTO' | 'COMPLETION_PHOTO'>(pending.value?.category ?? 'PROGRESS_PHOTO'), completionOpen = ref(!!pending.value && pending.value.title !== '追加进度')
const loading = ref(false), working = ref(false), error = ref(''), message = ref('')
const queue = new MaterialUploadQueue(), uploads = queue.entries, seen = new Set<string>()
const uploading = computed(() => uploads.value.some(item => item.state === 'uploading' || item.state === 'waiting'))
const locked = computed(() => !props.active || loading.value || working.value || uploading.value || !!pending.value)
const canFinish = computed(() => !!data.value && !!quote.value && canOpenCompletion(props.row, data.value, quote.value))
const editable = computed(() => canFinish.value && data.value?.status === 'REPAIRING')
const visiblePhotos = computed(() => photos.value.filter(item => item.category === category.value))
const labels: Record<string, string> = { UNSET: '待确认', UNPAID: '未结算', PARTIAL: '部分结算', SETTLED: '已结清' }
const scope = `shop-repair:${props.row.id}`
let alive = true, sequence = 0, timer: ReturnType<typeof setTimeout> | undefined
watch(() => working.value || uploading.value || !!pending.value, value => emit('busy', value), { immediate: true, flush: 'sync' })
function pause() { sequence++; if (timer) clearTimeout(timer); timer = undefined; cancelRequests(scope); loading.value = false }
function clear() { pause(); data.value = null; quote.value = null; settlement.value = null; photos.value = []; history.value = []; reviews.value = []; note.value = ''; selected.value = []; pending.value = null; queue.dispose() }
const stop = onSessionCleared(clear)
onBeforeUnmount(() => { alive = false; clear(); stop(); emit('busy', false) })
function fail(cause: unknown) {
  error.value = cause instanceof Error ? cause.message : '维修记录暂未确认，请重试。'
  if (cause instanceof RequestError && [401, 403, 404].indexOf(cause.status) >= 0) { data.value = null; quote.value = null; settlement.value = null; photos.value = []; history.value = []; reviews.value = []; emit('failed', cause) }
}
function schedule() {
  if (timer) clearTimeout(timer)
  if (alive && props.active && !working.value && !pending.value && !uploading.value && photoPage.value === 1 && historyPage.value === 1 && reviewPage.value < 2) timer = setTimeout(() => { void load() }, 30000)
}
async function load() {
  if (!alive || !props.active || working.value || loading.value) return
  loading.value = true; error.value = ''; const current = ++sequence, stamp = sessionGeneration()
  try {
    if (props.mode === 'settlement') {
      const value = await shopRepairApi.settlement(props.row, scope)
      if (alive && props.active && current === sequence && sameSession(stamp)) settlement.value = value
    } else {
      const [value, authorization, pictures, entries] = await Promise.all([shopRepairApi.get(props.row, scope), shopApi.quote(props.row.id, scope), shopApi.photos(props.row.id, 1, scope), shopRepairApi.history(props.row, 1, scope)])
      if (!alive || !props.active || current !== sequence || !sameSession(stamp)) return
      if (value.assignmentVersion !== authorization.assignmentVersion || value.status !== authorization.status) throw new RequestError('STATE_CONFLICT', '案件状态正在变化，请刷新核对。', 409)
      data.value = value; quote.value = authorization; photos.value = pictures.records; photoPage.value = 1; photoTotal.value = pictures.total; history.value = entries.records; historyPage.value = 1; historyTotal.value = entries.total
      if (value.status !== 'REPAIRING' && !pending.value) completionOpen.value = false
    }
  } catch (cause) { if (alive && props.active && current === sequence && sameSession(stamp)) fail(cause) }
  finally { if (current === sequence) { loading.value = false; schedule() } }
}
watch(() => `${props.active}:${props.mode}`, () => { pause(); if (props.active) void load() }, { immediate: true })
watch(uploads, async entries => {
  const fresh = entries.filter(item => item.state === 'uploaded' && !seen.has(item.command.idempotencyKey))
  if (!fresh.length || !alive) return
  fresh.forEach(item => { seen.add(item.command.idempotencyKey); if (item.result?.status === 'uploaded' && item.command.category === category.value && selected.value.length < 20) selected.value = [...new Set([...selected.value, item.result.remoteId])] })
  await nextTick(); if (props.active) { await load(); emit('changed') }
})
function changeCategory(value: 'PROGRESS_PHOTO' | 'COMPLETION_PHOTO') { if (!locked.value) { category.value = value; selected.value = [] } }
function toggle(key: string) { if (locked.value) return; if (selected.value.indexOf(key) >= 0) selected.value = selected.value.filter(item => item !== key); else if (selected.value.length < 20) selected.value = [...selected.value, key]; else error.value = '每次最多选择20张照片。' }
async function choose() {
  if (locked.value || !editable.value) return
  const stamp = sessionGeneration(), version = data.value!.assignmentVersion, kind = category.value; error.value = ''
  try {
    const files = await pickOriginalImages(9)
    if (!alive || !props.active || !sameSession(stamp) || data.value?.assignmentVersion !== version || !editable.value) return
    queue.enqueue(files.map(file => createMaterialUpload(props.row.id, kind, version, file)))
  } catch (cause) { if (alive && sameSession(stamp) && !(cause instanceof RequestError && cause.code === 'PICK_CANCELLED')) fail(cause) }
}
async function openCompletion() {
  if (locked.value || !canFinish.value || !data.value || !quote.value) return
  if (data.value.status === 'WAITING_REPAIR_AUTHORIZATION') {
    try { pending.value = { title: '开启完工', command: startRepairCommand(props.row, data.value, quote.value), note: note.value, photoIds: [], category: 'COMPLETION_PHOTO' }; repairPending.set(props.row.id, pending.value); await execute() } catch (cause) { fail(cause) }
  } else { completionOpen.value = true; category.value = 'COMPLETION_PHOTO'; selected.value = [] }
}
async function submit(operation: 'progress' | 'completion') {
  if (locked.value || !data.value || !quote.value) return
  try {
    if ((operation === 'completion' && category.value !== 'COMPLETION_PHOTO') || (operation === 'progress' && category.value !== 'PROGRESS_PHOTO')) throw new RequestError('INVALID_REPAIR_PHOTO', '请使用对应用途的照片。')
    const command = repairCommand(props.row, data.value, quote.value, operation, note.value, selected.value, photos.value)
    const stamp = sessionGeneration(), send = () => {
      if (!alive || locked.value || !sameSession(stamp)) return
      pending.value = { title: operation === 'completion' ? '提交完工' : '追加进度', command, note: note.value, photoIds: [...selected.value], category: category.value }; repairPending.set(props.row.id, pending.value); void execute()
    }
    if (operation === 'completion') uni.showModal({ title: '确认维修完成', content: '提交后所选完工照片版本将冻结，并通知车主确认收车。', success: result => { if (result.confirm) send() } }); else send()
  } catch (cause) { fail(cause) }
}
async function execute() {
  const item = pending.value; if (!item || !props.active || working.value) return
  working.value = true; error.value = ''; const stamp = sessionGeneration(); let actionError = ''
  try {
    await item.command.execute(); if (!sameSession(stamp)) return
    repairPending.delete(props.row.id); if (!alive) return
    pending.value = null; note.value = ''; selected.value = []; message.value = item.title === '提交完工' ? '完工已提交，照片版本已冻结，等待车主确认收车。' : item.title === '追加进度' ? '维修进度已追加。' : '请上传并选择完工照片。'
    if (item.title === '开启完工') { completionOpen.value = true; category.value = 'COMPLETION_PHOTO' }
  } catch (cause) {
    if (!alive || !sameSession(stamp)) return
    fail(cause)
    if (cause instanceof RequestError && cause.status >= 400 && cause.status < 500 && cause.status !== 429) { pending.value = null; repairPending.delete(props.row.id); error.value += ' 请刷新核对后重新提交。' } else error.value += ' 原内容已保留，请按原操作重试。'
    actionError = error.value
  } finally { if (alive && sameSession(stamp)) { working.value = false; await nextTick(); if (!pending.value && props.active) { await load(); if (actionError) error.value = actionError; emit('changed') } } }
}
async function more(kind: 'photos' | 'history' | 'reviews') {
  if (locked.value) return
  loading.value = true; const stamp = sessionGeneration(), current = sequence
  try {
    if (kind === 'photos') { const value = await shopApi.photos(props.row.id, photoPage.value + 1, scope); if (alive && props.active && current === sequence && sameSession(stamp)) { photos.value = [...photos.value, ...value.records.filter(item => !photos.value.some(old => old.id === item.id))]; photoPage.value = value.page; photoTotal.value = value.total } }
    else if (kind === 'history') { const value = await shopRepairApi.history(props.row, historyPage.value + 1, scope); if (alive && props.active && current === sequence && sameSession(stamp)) { history.value = [...history.value, ...value.records]; historyPage.value = value.page; historyTotal.value = value.total } }
    else { const value = await shopRepairApi.reviews(props.row, reviewPage.value + 1, scope); if (alive && props.active && current === sequence && sameSession(stamp)) { reviews.value = [...reviews.value, ...value.records]; reviewPage.value = value.page; reviewTotal.value = value.total } }
  } catch (cause) { if (alive && props.active && current === sequence && sameSession(stamp)) fail(cause) }
  finally { if (current === sequence) { loading.value = false; schedule() } }
}
</script>
<template><view>
  <view v-if="error" class="shop-error">{{ error }}</view><view v-if="message" class="shop-message">{{ message }}</view><button class="shop-button" :disabled="loading || working || !active" @tap="load">刷新{{ mode === 'settlement' ? '结算' : '维修' }}记录</button>
  <view v-if="pending" class="shop-pending"><text>{{ pending.title }}结果待确认，原内容与操作已保留。</text><button class="shop-button" :disabled="working || !active" @tap="execute">按原操作重试</button></view>
  <view v-if="mode === 'settlement' && settlement" class="shop-card"><text class="shop-index">SETTLEMENT / 结算</text><text class="shop-title">结算结果</text><text class="shop-status">{{ labels[settlement.status] }}</text><view class="shop-data-row"><text>已确认应付</text><text>{{ settlement.target === null ? '待确认' : settlement.target + ' 元' }}</text></view><view class="shop-data-row"><text>累计结算</text><text>{{ settlement.net }} 元</text></view><text v-if="settlement.target === null" class="shop-muted">应付金额尚未确认，请等待客服办理。</text></view>
  <template v-if="mode === 'repair' && data">
    <view class="shop-card"><text class="shop-index">REPAIR / 维修</text><text class="shop-title">{{ completionOpen && editable ? '上传完工照' : data.completion ? '维修与收车' : '维修进度' }}</text><text v-if="!canFinish && ['WAITING_REPAIR_AUTHORIZATION', 'REPAIRING'].indexOf(data.status) >= 0" class="shop-muted">等待客服完成维修授权。</text><button v-if="canFinish && !completionOpen" class="shop-button primary" :disabled="locked" @tap="openCompletion">维修完成</button>
      <template v-if="editable"><textarea v-if="!completionOpen" v-model="note" class="shop-textarea" maxlength="2000" placeholder="填写维修进度（1至2000字）" :disabled="locked" /><text class="shop-muted">{{ completionOpen ? '请选择至少一张本次派单完工照，最多20张。提交后照片版本冻结。' : '可选择本次派单进度照，最多20张；照片可不选。' }}</text>
        <view class="shop-grid"><view v-for="photo in visiblePhotos" :key="photo.id + ':' + photo.version" class="shop-line"><ShopImage :photo-id="photo.id" :version="photo.version" :active="active" :caption="category === 'COMPLETION_PHOTO' ? '完工照' : '进度照'" /><button class="shop-button" :disabled="locked" @tap="toggle(photo.id)">{{ selected.indexOf(photo.id) >= 0 ? '已选择 ✓' : '选择此图' }}</button></view></view>
        <button v-if="photoPage * 20 < photoTotal" class="shop-button" :disabled="locked" @tap="more('photos')">更多照片</button><button class="shop-button" :disabled="locked" @tap="choose">选择并上传{{ completionOpen ? '完工' : '进度' }}原图</button>
        <view v-for="item in uploads" :key="item.command.idempotencyKey" class="shop-record"><text class="shop-muted">{{ item.command.file.name }} · {{ item.state === 'uploaded' ? '已上传' : item.state === 'failed' ? '未确认上传' : '上传中' }}</text><text v-if="item.result?.status === 'failed'" class="shop-error">{{ item.result.message }}</text><button v-if="item.state === 'failed' && item.result?.status === 'failed' && item.result.retryable" class="shop-button" :disabled="!active || working || !!pending || (item.retryAt || 0) > Date.now()" @tap="queue.retry(item.command.idempotencyKey)">原文件重试</button></view>
        <text class="shop-muted">已选择 {{ selected.length }}/20 张</text><button class="shop-button primary" :disabled="locked" @tap="submit(completionOpen ? 'completion' : 'progress')">{{ completionOpen ? '确认维修完成' : '追加进度' }}</button><button v-if="completionOpen" class="shop-button" :disabled="locked" @tap="completionOpen = false; changeCategory('PROGRESS_PHOTO')">返回维修进度</button>
      </template><text v-else-if="!data.completion && !canFinish" class="shop-muted">{{ data.status === 'CANCELLED' || data.status === 'CLOSED' ? '案件已结束，维修记录只读。' : '当前阶段暂不能填写维修记录。' }}</text>
    </view>
    <view v-if="data.completion" class="shop-card"><text class="shop-title">已提交完工照片</text><text class="shop-muted">{{ displayTime(data.completion.createdAt) }} · 版本已冻结</text><view class="shop-grid"><view v-for="photo in data.completion.photos" :key="photo.id + ':' + photo.version"><ShopImage :photo-id="photo.id" :version="photo.version" :active="active" :caption="'完工照 · 版本 ' + photo.version" /></view></view><text v-if="!data.receipt || data.receipt.withdrawn" class="shop-muted">等待车主确认收车。</text></view>
    <view v-if="data.receipt" class="shop-card"><text class="shop-title">收车确认</text><text class="shop-body">{{ data.receipt.withdrawn ? '收车确认已撤回，等待重新确认' : '已确认收车' }}</text><text class="shop-muted">{{ data.receipt.confirmedBy === 'OWNER' ? '车主确认' : '客服代录' }} · {{ displayTime(data.receipt.createdAt) }}</text></view>
    <view class="shop-card"><text class="shop-title">车主评价</text><template v-if="data.review"><text class="shop-body">{{ data.review.original.text || '未填写文字' }}</text><text class="shop-muted">原评分 {{ data.review.original.score === null ? '未评分' : data.review.original.score + ' 星' }}</text><text v-if="data.review.current.id !== data.review.original.id" class="shop-body">当前公开版本：{{ data.review.current.text || '未填写文字' }} · {{ data.review.current.score === null ? '未评分' : data.review.current.score + ' 星' }}</text><text class="shop-muted">{{ data.review.eligible ? '计入当前评分' : '历史评价，不计当前评分' }}</text><view v-for="review in reviews" :key="review.id" class="shop-record"><text class="shop-body">{{ review.text || '未填写文字' }} · {{ review.score === null ? '未评分' : review.score + ' 星' }}</text><text class="shop-muted">{{ displayTime(review.createdAt) }} · {{ review.source === 'OWNER' ? '车主原评价' : '公开纠正版本' }}</text></view><button v-if="!reviewPage || reviewPage * 20 < reviewTotal" class="shop-button" :disabled="locked" @tap="more('reviews')">{{ reviewPage ? '更多公开版本' : '查看公开评价版本' }}</button></template><text v-else class="shop-muted">暂无评价。评价和评分可跳过。</text></view>
    <view class="shop-card"><text class="shop-title">历史维修记录</text><view v-for="entry in history" :key="entry.id" class="shop-record"><text class="shop-body">{{ entry.note }}</text><text class="shop-muted">{{ displayTime(entry.createdAt) }}</text><view class="shop-grid"><ShopImage v-for="photo in entry.photos" :key="photo.id + ':' + photo.version" :photo-id="photo.id" :version="photo.version" :active="active" :caption="'进度照 · 版本 ' + photo.version" /></view></view><text v-if="!history.length" class="shop-muted">暂无维修进度</text><button v-if="historyPage * 20 < historyTotal" class="shop-button" :disabled="locked" @tap="more('history')">更多维修记录</button></view>
  </template><text v-if="loading" class="shop-muted">正在更新…</text>
</view></template>
