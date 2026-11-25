<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { onHide, onLoad, onPullDownRefresh, onShow, onUnload } from '@dcloudio/uni-app'
import BrandHeader from '../../components/BrandHeader.vue'
import OwnerPhoto from '../../components/OwnerPhoto.vue'
import ComplaintHistory from '../../components/ComplaintHistory.vue'
import { revalidateSession, returnToLogin } from '../../services/auth'
import { cancelRequests, RequestError } from '../../services/client'
import { currentSession, onSessionCleared, sameSession, sessionGeneration } from '../../services/session'
import { appVisible } from '../../services/lifecycle'
import { canComplain, canReceive, canReview, complaintCommand, complaintStatus, displayTime, ownerApi, ownerStatus,
  pendingOwnerActions, receiptCommand, requireOwner, resourceId, reviewCommand, workflow, workflowIndex,
  type Complaint, type OwnerCase, type PendingOwnerAction, type Photo, type Progress, type Repair } from '../../services/owner'

const id = ref(''), row = ref<OwnerCase | null>(null), repair = ref<Repair | null>(null)
const progress = ref<Progress[]>([]), photos = ref<Photo[]>([]), history = ref<{ label: string; createdAt: string }[]>([]), complaints = ref<Complaint[]>([])
type ListKind = 'progress' | 'photos' | 'history' | 'complaints'
const kinds: ListKind[] = ['progress', 'photos', 'history', 'complaints']
const pages = ref<Record<ListKind, number>>({ progress: 1, photos: 1, history: 1, complaints: 1 })
const totals = ref<Record<ListKind, number>>({ progress: 0, photos: 0, history: 0, complaints: 0 })
const section = ref<'task' | 'data' | 'history'>('task'), module = ref<'progress' | 'photos' | 'receipt' | 'complaint'>('progress')
const tabs: { key: 'task' | 'data' | 'history'; label: string }[] = [{ key: 'task', label: '当前任务' }, { key: 'data', label: '案件资料' }, { key: 'history', label: '案件记录' }]
const modules: { key: 'progress' | 'photos' | 'receipt' | 'complaint'; label: string }[] = [{ key: 'progress', label: '维修进度' }, { key: 'photos', label: '维修照片' }, { key: 'receipt', label: '收车与评价' }, { key: 'complaint', label: '服务反馈' }]
const visible = ref(false), verified = ref(false), busy = ref(false), writing = ref(false), error = ref(''), actionError = ref(''), message = ref(''), revision = ref(0)
const text = ref(''), score = ref<number | null>(null), description = ref(''), selectedPhotos = ref<string[]>([]), pending = ref<PendingOwnerAction | null>(null)
const active = computed(() => visible.value && appVisible.value && verified.value)
const scope = 'owner-case'
const currentNode = computed(() => row.value ? workflowIndex(row.value) : -1)
const changedReview = computed(() => repair.value?.review && repair.value.review.current.id !== repair.value.review.original.id)
let alive = true, serial = 0, checkSerial = 0, timer: ReturnType<typeof setTimeout> | undefined
function pause() { serial++; checkSerial++; if (timer) clearTimeout(timer); timer = undefined; cancelRequests(scope); busy.value = false }
function clearData() { row.value = null; repair.value = null; progress.value = []; photos.value = []; history.value = []; complaints.value = [] }
function clear() { pause(); verified.value = false; clearData(); pending.value = null; text.value = ''; score.value = null; description.value = ''; selectedPhotos.value = []; actionError.value = ''; message.value = '' }
const stop = onSessionCleared(() => { clear(); if (alive) returnToLogin() })
function schedule(delay = 30000) {
  if (timer) clearTimeout(timer)
  // Reading additional pages must not be reset by a background first-page refresh.
  if (active.value && !pending.value && !writing.value && kinds.every(kind => pages.value[kind] === 1)) timer = setTimeout(() => { void load() }, delay)
}
function failure(cause: unknown) {
  error.value = cause instanceof Error ? cause.message : '加载未完成，请稍后重试。'
  if (cause instanceof RequestError && [401, 403, 404].indexOf(cause.status) >= 0) clearData()
  return cause instanceof RequestError && cause.retryAfter ? Math.max(30000, cause.retryAfter * 1000) : 30000
}
async function load() {
  if (!active.value || busy.value || writing.value) return
  busy.value = true; error.value = ''
  const stamp = sessionGeneration(), current = ++serial
  let delay = 30000
  try {
    const data = await Promise.all([ownerApi.detail(id.value, scope), ownerApi.repair(id.value, scope), ownerApi.progress(id.value, 1, scope),
      ownerApi.photos(id.value, 1, scope), ownerApi.history(id.value, 1, scope), ownerApi.complaints(id.value, 1, scope)])
    if (!alive || !active.value || current !== serial || !sameSession(stamp)) return
    row.value = data[0]; repair.value = data[1]; progress.value = data[2].records; photos.value = data[3].records; history.value = data[4].records; complaints.value = data[5].records
    const lists = [data[2], data[3], data[4], data[5]]
    kinds.forEach((kind, index) => { pages.value[kind] = 1; totals.value[kind] = lists[index]!.total })
    revision.value++
  } catch (cause) { if (alive && active.value && current === serial && sameSession(stamp)) delay = failure(cause) }
  finally { if (current === serial) { busy.value = false; schedule(delay) } }
}
async function more(kind: ListKind) {
  if (!active.value || busy.value || writing.value) return
  busy.value = true; error.value = ''; if (timer) clearTimeout(timer)
  const stamp = sessionGeneration(), current = ++serial
  let delay = 30000
  try {
    const data = await ownerApi[kind](id.value, pages.value[kind] + 1, scope)
    if (!alive || !active.value || current !== serial || !sameSession(stamp)) return
    if (kind === 'progress') progress.value = [...progress.value, ...(data.records as Progress[]).filter(item => !progress.value.some(old => old.id === item.id))]
    if (kind === 'photos') photos.value = [...photos.value, ...(data.records as Photo[]).filter(item => !photos.value.some(old => old.id === item.id))]
    if (kind === 'history') history.value = [...history.value, ...(data.records as { label: string; createdAt: string }[])]
    if (kind === 'complaints') complaints.value = [...complaints.value, ...(data.records as Complaint[]).filter(item => !complaints.value.some(old => old.id === item.id))]
    pages.value[kind] = data.page; totals.value[kind] = data.total
  } catch (cause) { if (alive && active.value && current === serial && sameSession(stamp)) delay = failure(cause) }
  finally { if (current === serial) { busy.value = false; schedule(delay) } }
}
async function enter() {
  verified.value = false
  if (!visible.value || !appVisible.value) return
  if (!currentSession()) { returnToLogin(); return }
  const stamp = sessionGeneration(), current = ++checkSerial
  try {
    requireOwner(); await revalidateSession(); requireOwner()
    if (!alive || current !== checkSerial || !visible.value || !appVisible.value || !sameSession(stamp)) return
    verified.value = true; await load()
  } catch (cause) { if (alive && current === checkSerial && sameSession(stamp)) { failure(cause); clearData() } }
}
onLoad(options => {
  try {
    id.value = resourceId(options?.id)
    if (options?.section === 'complaint' || options?.section === 'receipt' || options?.section === 'photos') module.value = options.section
    pending.value = pendingOwnerActions.get(id.value) ?? null
    if (pending.value?.kind === 'review') { text.value = pending.value.text; score.value = pending.value.score; module.value = 'receipt' }
    if (pending.value?.kind === 'receipt') module.value = 'receipt'
    if (pending.value?.kind === 'complaint') { description.value = pending.value.text; selectedPhotos.value = [...pending.value.photoIds]; module.value = 'complaint' }
  } catch (cause) { failure(cause) }
})
onShow(() => { visible.value = true; if (id.value) void enter() })
onHide(() => { visible.value = false; verified.value = false; pause() })
watch(appVisible, value => { if (!value) { verified.value = false; pause() } else if (visible.value) void enter() })
onUnload(() => { alive = false; clear(); stop() })
onPullDownRefresh(async () => { try { if (!verified.value) await enter(); else await load() } finally { uni.stopPullDownRefresh() } })
function back() { uni.navigateBack({ fail: () => uni.reLaunch({ url: '/pages/index/index' }) }) }
function selectPhoto(photo: Photo) {
  if (pending.value || writing.value) return
  const index = selectedPhotos.value.indexOf(photo.id)
  if (index >= 0) selectedPhotos.value.splice(index, 1)
  else if (selectedPhotos.value.length < 20) selectedPhotos.value.push(photo.id)
  else actionError.value = '最多选择20张已有维修照片。'
}
function confirmReceipt() {
  if (!repair.value || !active.value || busy.value || writing.value || pending.value) return
  const stamp = sessionGeneration()
  uni.showModal({ title: '确认已收车', content: '请核对完工照片，确认已收到维修车辆后再继续。评价可以稍后填写或跳过。', confirmText: '确认收车',
    success: result => { if (result.confirm && active.value && sameSession(stamp)) start('receipt') } })
}
function start(kind: PendingOwnerAction['kind']) {
  if (!row.value || !repair.value || !active.value || busy.value || writing.value || pending.value) return
  actionError.value = ''; message.value = ''
  try {
    const command = kind === 'receipt' ? receiptCommand(repair.value) : kind === 'review' ? reviewCommand(repair.value, text.value, score.value) : complaintCommand(row.value, description.value, selectedPhotos.value, photos.value)
    pending.value = { kind, command, text: kind === 'review' ? text.value : description.value, score: score.value, photoIds: [...selectedPhotos.value] }
    pendingOwnerActions.set(id.value, pending.value); void execute()
  } catch (cause) { actionError.value = cause instanceof Error ? cause.message : '请核对填写内容。' }
}
async function execute() {
  const command = pending.value
  if (!command || !active.value || writing.value || busy.value || !row.value || !repair.value) return
  writing.value = true; actionError.value = ''; if (timer) clearTimeout(timer)
  const stamp = sessionGeneration()
  let refresh = false
  try {
    const result = await command.command.execute()
    if (!sameSession(stamp)) return
    pendingOwnerActions.delete(id.value)
    if (!alive) return
    pending.value = null
    if (command.kind === 'complaint') { description.value = ''; selectedPhotos.value = [] }
    if (command.kind === 'review') { text.value = ''; score.value = null }
    if ('version' in result) repair.value = result
    message.value = command.kind === 'receipt' ? '已确认收车，评价可选。' : command.kind === 'review' ? '评价已提交，原评价保留且不能再次提交。' : '服务反馈已提交。'
    refresh = true
  } catch (cause) {
    if (!alive || !sameSession(stamp)) return
    actionError.value = cause instanceof Error ? cause.message : '操作结果尚未确认，请按原操作重试。'
    if (cause instanceof RequestError && cause.status >= 400 && cause.status < 500 && cause.status !== 429) {
      pendingOwnerActions.delete(id.value); pending.value = null; refresh = true
      if (cause.status === 403 || cause.status === 404) clearData()
      if (cause.status === 409) actionError.value += ' 请重新核对最新页面后再提交。'
    } else actionError.value += ' 请保留此操作并按原内容重试，勿重复创建。'
  } finally {
    if (alive && sameSession(stamp)) { writing.value = false; if (refresh && active.value) await load(); else schedule() }
  }
}
</script>
<template>
  <view class="case-page"><BrandHeader /><view class="case-top"><text @tap="back">‹ 返回</text><text>案件详情</text><text class="refresh-link" @tap="enter">刷新</text></view>
    <view v-if="error" class="case-error">{{ error }}<button :disabled="busy || writing" @tap="enter">重新加载</button></view>
    <text v-if="!verified && !error" class="case-muted loading">正在核实账号…</text>
    <template v-if="active && row && repair">
      <view class="case-card"><view class="case-heading"><text class="case-title case-number">{{ row.businessNo || row.claimNo || '我的案件' }}</text><text class="case-status">{{ ownerStatus(row.status) }}</text></view><text class="case-muted">{{ row.vehicle || '车辆信息待完善' }} {{ row.plate }}</text>
        <view class="workflow"><view v-for="(node, index) in workflow" :key="node[0]" :class="['workflow-node', index <= currentNode && 'done', index === currentNode && 'current']"><text class="node-number">{{ index + 1 }}</text><text>{{ node[1] }}</text></view></view>
      </view>
      <view class="case-tabs"><view v-for="item in tabs" :key="item.key" :class="['case-tab', section === item.key && 'active']" @tap="section = item.key">{{ item.label }}</view></view>
      <template v-if="section === 'task'">
        <view class="module-tabs"><text v-for="item in modules" :key="item.key" :class="['module-tab', module === item.key && 'selected']" @tap="module = item.key">{{ item.label }}</text></view>
        <view v-if="message" class="case-message">{{ message }}</view><view v-if="actionError" class="case-error">{{ actionError }}</view>
        <view v-if="pending" class="case-pending"><text>有一项{{ pending.kind === 'receipt' ? '收车确认' : pending.kind === 'review' ? '评价' : '反馈' }}结果待确认，原内容已保留。</text><button :disabled="writing || busy" @tap="execute">{{ writing ? '正在确认…' : '按原操作重试' }}</button></view>
        <view v-if="module === 'progress'" class="case-card"><text class="case-title">维修进度</text>
          <view v-for="item in progress" :key="item.id" class="case-record"><text class="case-body">{{ item.note || '维修进度已更新' }}</text><text class="case-muted">{{ displayTime(item.createdAt) }}</text><view class="photo-grid"><OwnerPhoto v-for="photo in item.photos" :key="photo.id + ':' + photo.version" :photo="photo" /></view></view><text v-if="!progress.length" class="case-muted">暂无维修进度</text><button v-if="pages.progress * 20 < totals.progress" class="case-button" :disabled="busy || writing" @tap="more('progress')">更多维修进度</button>
        </view>
        <view v-if="module === 'photos'" class="case-card"><text class="case-title">维修照片</text><text class="case-muted">到店、维修进度与完工照片</text><view class="photo-grid"><OwnerPhoto v-for="photo in photos" :key="photo.id + ':' + photo.version" :photo="photo" /></view><text v-if="!photos.length" class="case-muted">暂无可查看照片</text><button v-if="pages.photos * 20 < totals.photos" class="case-button" :disabled="busy || writing" @tap="more('photos')">更多照片</button></view>
        <view v-if="module === 'receipt'" class="case-card"><text class="case-title">完工与收车</text>
          <template v-if="repair.completion"><text class="case-muted">完工提交：{{ displayTime(repair.completion.createdAt) }}</text><view class="photo-grid"><OwnerPhoto v-for="photo in repair.completion.photos" :key="photo.id + ':' + photo.version" :photo="photo" /></view></template><text v-else class="case-muted">等待网点提交完工记录与照片</text>
          <button v-if="canReceive(repair)" class="case-button primary" :disabled="busy || writing || !!pending" @tap="confirmReceipt">确认已收车</button>
          <text v-if="repair.receipt" class="case-body">{{ repair.receipt.withdrawn ? '本次收车确认已撤回，请核实后重新确认。' : '已确认收车 · ' + displayTime(repair.receipt.createdAt) }}</text>
          <view v-if="repair.review" class="case-record"><text class="case-title">我的原评价</text><text class="case-body">{{ repair.review.original.score === null ? '未评分' : repair.review.original.score + ' 星' }}</text><text class="case-body">{{ repair.review.original.text || '未填写文字' }}</text><text class="case-muted">{{ displayTime(repair.review.original.createdAt) }} · 原评价只读</text><text v-if="!repair.review.eligible" class="case-muted">原评价已保留，当前不计入本次收车评分。</text><template v-if="changedReview"><text class="case-title">当前公开评价</text><text class="case-body">{{ repair.review.current.score === null ? '未评分' : repair.review.current.score + ' 星' }} · {{ repair.review.current.text || '未填写文字' }}</text><text class="case-muted">{{ displayTime(repair.review.current.createdAt) }}</text></template></view>
          <view v-if="canReview(repair) || pending?.kind === 'review'" class="case-record"><text class="case-title">服务评价（可选）</text><text class="case-muted">可只写文字或只评分，也可跳过；提交后不能再次填写。</text><view class="stars"><button v-for="n in 5" :key="n" :class="['star', score !== null && n <= score && 'filled']" :disabled="writing || !!pending" @tap="score = score === n ? null : n">★</button><text>{{ score === null ? '未评分' : score + '星' }}</text></view><textarea v-model="text" class="case-textarea" maxlength="2000" :disabled="writing || !!pending" placeholder="填写本次维修服务体验（选填）" /><button class="case-button primary" :disabled="busy || writing || !!pending" @tap="start('review')">提交评价</button><button class="case-button" @tap="back">暂不评价，返回案件</button></view>
        </view>
        <view v-if="module === 'complaint'" class="case-card"><text class="case-title">服务反馈</text>
          <template v-if="canComplain(row)"><textarea v-model="description" class="case-textarea" maxlength="2000" :disabled="writing || !!pending" placeholder="请说明需要反馈的问题（必填）" /><text class="case-muted">可选择已有维修照片作为证据，最多20张。</text><view class="photo-grid"><view v-for="photo in photos" :key="photo.id"><OwnerPhoto :photo="photo" /><button :class="['case-select', selectedPhotos.indexOf(photo.id) >= 0 && 'chosen']" :disabled="writing || !!pending" @tap="selectPhoto(photo)">{{ selectedPhotos.indexOf(photo.id) >= 0 ? '✓ 已选择' : '选择此照片' }}</button></view></view><button v-if="pages.photos * 20 < totals.photos" class="case-button" :disabled="busy || writing" @tap="more('photos')">更多可选照片</button><text class="case-muted">已选择 {{ selectedPhotos.length }} 张</text><button class="case-button primary" :disabled="busy || writing || !!pending" @tap="start('complaint')">提交反馈</button></template><text v-else class="case-muted">当前案件阶段不能新增反馈，可查看已有处理记录。</text>
          <view v-for="item in complaints" :key="item.id" class="case-record"><view class="case-heading"><text class="case-title">{{ complaintStatus(item.status) }}</text><text class="case-muted">{{ displayTime(item.createdAt) }}</text></view><text class="case-body">{{ item.description }}</text><view class="photo-grid"><OwnerPhoto v-for="photo in item.photos" :key="photo.id + ':' + photo.version" :photo="photo" /></view><ComplaintHistory :id="item.id" :active="active" :revision="revision" /></view><text v-if="!complaints.length" class="case-muted">暂无反馈记录</text><button v-if="pages.complaints * 20 < totals.complaints" class="case-button" :disabled="busy || writing" @tap="more('complaints')">更多反馈</button>
        </view>
      </template>
      <view v-if="section === 'data'" class="case-card"><text class="case-title">案件资料</text><view class="data-row"><text>报案号</text><text>{{ row.claimNo || '未填写' }}</text></view><view class="data-row"><text>车辆</text><text>{{ row.vehicle || '未填写' }}</text></view><view class="data-row"><text>车牌</text><text>{{ row.plate || '未填写' }}</text></view><view class="data-row"><text>保险公司</text><text>{{ row.insuranceCompany || '未填写' }}</text></view><view class="data-row"><text>事故地点</text><text>{{ row.accidentAddress || '未填写' }}</text></view><view class="data-row"><text>事故说明</text><text>{{ row.accidentDescription || '未填写' }}</text></view></view>
      <view v-if="section === 'history'" class="case-card"><text class="case-title">案件记录</text><view v-for="(item, index) in history" :key="index" class="case-record"><text class="case-body">{{ item.label }}</text><text class="case-muted">{{ displayTime(item.createdAt) }}</text></view><text v-if="!history.length" class="case-muted">暂无公开记录</text><button v-if="pages.history * 20 < totals.history" class="case-button" :disabled="busy || writing" @tap="more('history')">更多案件记录</button></view>
      <text v-if="busy" class="case-muted loading">正在更新…</text>
    </template>
  </view>
</template>
<style scoped>
.case-page { min-height: 100vh; padding: 0 14px calc(24px + env(safe-area-inset-bottom)); background: #f5f2ed; color: #292b2d; }.case-top { display: flex; align-items: center; justify-content: space-between; height: 46px; margin: 0 -14px 12px; padding: 0 16px; border-bottom: 1px solid #e0dcd3; font-size: 16px; font-weight: 700; }.case-top>text:first-child,.refresh-link { font-size: 14px; font-weight: 400; }
.case-card { margin: 12px 0; padding: 20px 18px; border: 1px solid #e7e1d8; border-radius: 18px; background: #fffdfa; box-shadow: 0 8px 24px rgba(65,55,38,.045), inset 0 1px 0 #fff; }.case-title,.case-muted,.case-body { display: block; }.case-title { margin: 0 0 10px; font-size: 18px; font-weight: 600; line-height: 1.5; }.case-heading { display: flex; justify-content: space-between; align-items: center; gap: 8px; }.case-number { min-width: 0; overflow-wrap: anywhere; font-size: 17px; }.case-status { padding: 4px 8px; border: 1px solid #d4ddd5; border-radius: 7px; background: #edf2ec; color: #49614f; font-size: 12px; white-space: nowrap; }.case-muted { color: #77746b; font-size: 12px; line-height: 1.8; }.case-body { margin: 8px 0; font-size: 14px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }.case-record { margin-top: 16px; padding-top: 15px; border-top: 1px solid #e0dcd3; }.photo-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 12px; margin: 12px 0; }.workflow { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: 13px 5px; margin-top: 20px; }.workflow-node { display: flex; flex-direction: column; align-items: center; gap: 7px; color: #89867e; font-size: 11px; }.node-number { width: 23px; height: 23px; border: 1px solid #e0dcd3; border-radius: 50%; line-height: 23px; text-align: center; }.workflow-node.done { color: #35383b; }.workflow-node.done .node-number { background: #eeece7; }.workflow-node.current .node-number { background: #35383b; color: #fffefa; border-color: #35383b; }
.case-tabs { display: flex; border: 1px solid #e0dcd3; border-radius: 9px; background: #fffefa; overflow: hidden; }.case-tab { flex: 1; padding: 12px 4px; text-align: center; color: #77746b; font-size: 14px; }.case-tab.active { background: #35383b; color: #fffefa; }.module-tabs { display: flex; flex-wrap: wrap; gap: 8px; margin: 14px 0; }.module-tab { padding: 7px 9px; border: 1px solid #e0dcd3; border-radius: 7px; color: #706d65; font-size: 12px; }.module-tab.selected { background: #fffefa; color: #292b2d; border-color: #a39b8b; }.case-button,.case-pending button { margin: 14px 0 0; padding: 0 10px; border: 1px solid #e0dcd3; border-radius: 8px; background: #fffefa; color: #35383b; font-size: 14px; line-height: 42px; }.case-button.primary { background: #35383b; color: #fffefa; border-color: #35383b; }.case-textarea { box-sizing: border-box; width: 100%; height: 130px; margin: 14px 0; padding: 11px; border: 1px solid #e0dcd3; border-radius: 8px; background: #fffefa; font-size: 14px; line-height: 1.7; }.case-select { margin: 6px 0 0; padding: 0 6px; border: 1px solid #e0dcd3; border-radius: 7px; color: #706d65; background: #fffefa; line-height: 34px; font-size: 12px; }.case-select.chosen { color: #fffefa; background: #35383b; }.stars { display: flex; align-items: center; gap: 3px; margin: 12px 0; }.star { width: 34px; margin: 0; padding: 0; background: transparent; color: #d0ccc2; font-size: 26px; line-height: 42px; }.star:after { border: 0; }.star.filled { color: #a5823d; }.stars>text { margin-left: 5px; font-size: 12px; color: #77746b; }.data-row { display: flex; justify-content: space-between; gap: 18px; padding: 14px 0; border-bottom: 1px solid #e0dcd3; font-size: 14px; line-height: 1.7; }.data-row>text:first-child { flex-shrink: 0; color: #77746b; }.data-row>text:last-child { text-align: right; overflow-wrap: anywhere; }.case-error,.case-message,.case-pending { margin: 12px 0; padding: 12px; border: 1px solid #ead0cc; border-radius: 9px; color: #9c3e3e; background: #fffefa; font-size: 13px; line-height: 1.8; }.case-message { border-color: #d8dfd3; color: #4b6541; }.case-pending { border-color: #ddcfaa; color: #756337; }.case-error button { margin-top: 8px; font-size: 13px; background: #fff; }.loading { padding: 18px; text-align: center; }
</style>
