<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import BrandHeader from './BrandHeader.vue'
import { logout } from '../services/auth'
import { cancelRequests, RequestError } from '../services/client'
import { currentSession, onSessionCleared, sameSession, sessionGeneration } from '../services/session'
import { actionLabel, canComplain, displayTime, ownerApi, ownerRevision, ownerStatus, type Notice, type OwnerCase } from '../services/owner'
const props = defineProps<{ active: boolean }>()
const tab = ref<'cases' | 'notices' | 'me'>('cases'), feedback = ref(false), query = ref(''), search = ref('')
const navigation: { key: 'cases' | 'notices' | 'me'; label: string; icon: string }[] = [{ key: 'cases', label: '案件', icon: 'case' }, { key: 'notices', label: '通知', icon: 'bell' }, { key: 'me', label: '我的', icon: 'user' }]
const cases = ref<OwnerCase[]>([]), notices = ref<Notice[]>([]), casePage = ref(1), noticePage = ref(1), caseTotal = ref(0), noticeTotal = ref(0), unread = ref(0)
const busy = ref(false), reading = ref(false), error = ref(''), scope = 'owner-home'
const rows = computed(() => feedback.value ? cases.value.filter(canComplain) : cases.value)
let timer: ReturnType<typeof setTimeout> | undefined, sequence = 0, alive = true
function pause() { sequence++; if (timer) clearTimeout(timer); timer = undefined; cancelRequests(scope); busy.value = false }
function clear() { pause(); cases.value = []; notices.value = []; unread.value = 0; caseTotal.value = 0; noticeTotal.value = 0; query.value = ''; search.value = ''; error.value = '' }
const stop = onSessionCleared(clear)
onBeforeUnmount(() => { alive = false; pause(); stop() })
function schedule(delay = 30000) { if (timer) clearTimeout(timer); if (alive && props.active && casePage.value === 1 && noticePage.value === 1) timer = setTimeout(() => { void load() }, delay) }
async function load() {
  if (busy.value || !props.active) return
  busy.value = true; error.value = ''
  const current = ++sequence, stamp = sessionGeneration()
  try {
    const [caseData, noticeData, count] = await Promise.all([ownerApi.cases(1, search.value), ownerApi.notices(1), ownerApi.unread()])
    if (!alive || current !== sequence || !props.active || !sameSession(stamp)) return
    cases.value = caseData.records; casePage.value = 1; caseTotal.value = caseData.total
    notices.value = noticeData.records; noticePage.value = 1; noticeTotal.value = noticeData.total; unread.value = count
  } catch (cause) {
    if (!alive || current !== sequence || !props.active || !sameSession(stamp)) return
    error.value = cause instanceof Error ? cause.message : '加载未完成，请重试。'
    if (cause instanceof RequestError && [401, 403, 404].indexOf(cause.status) >= 0) { cases.value = []; notices.value = []; unread.value = 0; caseTotal.value = 0; noticeTotal.value = 0 }
    if (cause instanceof RequestError && cause.retryAfter) { schedule(Math.max(30000, cause.retryAfter * 1000)); return }
  } finally { if (current === sequence) busy.value = false }
  if (current === sequence) schedule()
}
watch(() => props.active, active => { if (active) void load(); else pause() }, { immediate: true })
watch(ownerRevision, () => { if (props.active) void load() })
function switchTab(value: 'cases' | 'notices' | 'me') { tab.value = value; feedback.value = false }
function searchCases() { if (!busy.value) { search.value = query.value.trim(); void load() } }
async function more(kind: 'cases' | 'notices') {
  if (busy.value || !props.active) return
  busy.value = true; error.value = ''
  const current = ++sequence, stamp = sessionGeneration()
  let delay = 30000
  if (timer) clearTimeout(timer)
  try {
    if (kind === 'cases') {
      const result = await ownerApi.cases(casePage.value + 1, search.value)
      if (!alive || current !== sequence || !props.active || !sameSession(stamp)) return
      cases.value = [...cases.value, ...result.records.filter(row => !cases.value.some(old => old.id === row.id))]; casePage.value = result.page; caseTotal.value = result.total
    } else {
      const result = await ownerApi.notices(noticePage.value + 1)
      if (!alive || current !== sequence || !props.active || !sameSession(stamp)) return
      notices.value = [...notices.value, ...result.records.filter(row => !notices.value.some(old => old.id === row.id))]; noticePage.value = result.page; noticeTotal.value = result.total
    }
  } catch (cause) {
    if (alive && current === sequence && props.active && sameSession(stamp)) {
      error.value = cause instanceof Error ? cause.message : '加载未完成。'
      if (cause instanceof RequestError && cause.retryAfter) delay = Math.max(30000, cause.retryAfter * 1000)
      if (cause instanceof RequestError && [401, 403, 404].indexOf(cause.status) >= 0) { cases.value = []; notices.value = []; unread.value = 0; caseTotal.value = 0; noticeTotal.value = 0 }
    }
  } finally { if (current === sequence) { busy.value = false; schedule(delay) } }
}
function openCase(id: string, complaint = false, destination?: Notice['section']) {
  const row = cases.value.find(item => item.id === id)
  const section = destination ?? (complaint ? 'complaint' : row && ['WAITING_OWNER_CONFIRMATION', 'WAITING_INSURER_PAYMENT', 'WAITING_SHOP_SETTLEMENT', 'COMPLETED'].indexOf(row.status) >= 0 ? 'receipt' : 'progress')
  uni.navigateTo({ url: `/pages/owner-case/index?id=${id}&section=${section}` })
}
async function readNotice(row: Notice) {
  if (reading.value || !props.active) return
  const stamp = sessionGeneration(); reading.value = true
  try {
    const result = await ownerApi.readNotice(row.id)
    if (!alive || !props.active || !sameSession(stamp)) return
    notices.value = notices.value.map(item => item.id === result.id ? result : item)
    if (!row.read) unread.value = Math.max(0, unread.value - 1)
    openCase(result.caseId, false, result.section)
  } catch (cause) { if (alive && props.active && sameSession(stamp)) error.value = cause instanceof Error ? cause.message : '通知暂不可查看。' }
  finally { reading.value = false }
}
defineExpose({ refresh: load })
</script>
<template>
  <view v-show="active" class="owner-page">
    <BrandHeader /><view class="owner-page-title">{{ tab === 'me' ? '我的' : tab === 'notices' ? '站内通知' : feedback ? '服务反馈' : '我的案件' }}</view>
    <view v-if="error" class="owner-error">{{ error }}<text @tap="load">重新加载</text></view>
    <template v-if="tab === 'cases'">
      <view class="service-hero"><image class="service-art" src="/static/repair-showcase.jpg" mode="aspectFill" /><view class="service-copy"><text class="service-title">{{ feedback ? '服务反馈' : '我的维修服务' }}</text><text> {{ feedback ? '选择案件，查看反馈及处理进度' : '查看进度，安心收车' }}</text></view></view>
      <view class="owner-card"><view class="owner-heading"><view><text class="owner-title">{{ feedback ? '选择相关案件' : '我的案件' }}</text></view><text>全部 {{ caseTotal }} 件</text></view>
        <view class="owner-search"><input v-model="query" class="owner-search-input" placeholder="搜索案件或车辆" maxlength="120" confirm-type="search" @confirm="searchCases" /><button :disabled="busy" @tap="searchCases">搜索</button></view>
        <view v-for="row in rows" :key="row.id" class="owner-case-row" @tap="openCase(row.id, feedback)"><view><text class="owner-row-title">{{ row.businessNo || row.claimNo || '我的案件' }}</text><text class="owner-muted">{{ row.vehicle || row.plate || '车辆信息待完善' }} · {{ feedback ? '查看反馈及处理进度' : actionLabel(row) }}</text></view><text class="owner-status">{{ ownerStatus(row.status) }}</text></view>
        <text v-if="!rows.length && !busy" class="owner-empty">{{ error ? '暂未取得案件，请重试。' : feedback ? '本页暂无可反馈案件，可继续加载。' : '暂无属于您的案件。' }}</text><text v-if="busy" class="owner-empty">正在加载…</text>
        <button v-if="casePage * 20 < caseTotal" class="owner-more" :disabled="busy" @tap="more('cases')">加载更多案件</button>
      </view>
      <view class="owner-card"><view class="owner-heading"><view><text class="owner-title">最近通知</text></view><text class="owner-link" @tap="tab = 'notices'">查看全部</text></view>
        <view v-for="row in notices.slice(0, 2)" :key="row.id" class="owner-notice-row" @tap="readNotice(row)"><text :class="['owner-dot', !row.read && 'unread']"></text><view><text class="owner-row-title">{{ row.title }}</text><text class="owner-muted">{{ displayTime(row.createdAt) }}</text></view></view><text v-if="!notices.length" class="owner-empty">暂无新通知</text>
      </view>
    </template>
    <view v-else-if="tab === 'notices'" class="owner-card"><view class="owner-heading"><view><text class="owner-title">最近通知</text></view><text>未读 {{ unread }} 条</text></view>
      <view v-for="row in notices" :key="row.id" class="owner-notice-row" @tap="readNotice(row)"><text :class="['owner-dot', !row.read && 'unread']"></text><view><text class="owner-row-title">{{ row.title }}</text><text v-if="row.reason" class="owner-muted">{{ row.reason }}</text><text class="owner-muted">{{ displayTime(row.createdAt) }}</text></view></view>
      <text v-if="!notices.length" class="owner-empty">暂无通知</text><button v-if="noticePage * 20 < noticeTotal" class="owner-more" :disabled="busy" @tap="more('notices')">加载更多通知</button>
    </view>
    <view v-else class="owner-card"><text class="owner-title">{{ currentSession()?.user.displayName || '我的账号' }}</text><text class="owner-profile-note">当前为已绑定的车主身份。如需更换微信，请联系工作人员核实。</text>
      <button class="owner-more" @tap="feedback = true; tab = 'cases'">服务反馈记录</button><button class="owner-more" @tap="logout">退出登录</button>
    </view>
    <view class="owner-bottom"><view v-for="item in navigation" :key="item.key" :class="['owner-tab', tab === item.key && 'active']" @tap="switchTab(item.key)"><image :src="`/static/icons/${item.icon}${tab === item.key ? '-active' : ''}.svg`" mode="aspectFit" /><text>{{ item.label }}{{ item.key === 'notices' && unread ? ` (${Math.min(unread, 99)})` : '' }}</text></view></view>
  </view>
</template>
<style scoped>
.owner-page { min-height: 100vh; padding: 0 14px calc(92px + env(safe-area-inset-bottom)); background: #f5f2ed; color: #292b2d; }
.owner-page-title { height: 52px; margin: 0 -14px 8px; font-size: 17px; line-height: 52px; font-weight: 600; text-align: center; }
.owner-card { margin: 12px 0; padding: 20px 18px 10px; border: 1px solid #e7e1d8; border-radius: 18px; background: #fffdfa; box-shadow: 0 8px 24px rgba(65,55,38,.045), inset 0 1px 0 #fff; }.owner-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 12px; color: #706d65; font-size: 14px; }
.owner-title,.owner-row-title,.owner-muted,.owner-empty,.owner-profile-note { display: block; }.owner-title { font-size: 18px; font-weight: 600; color: #292b2d; }
.owner-case-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 76px; padding: 15px 4px; border-bottom: 1px solid #e0dcd3; }.owner-case-row>view { min-width: 0; flex: 1; }.owner-row-title { font-size: 15px; font-weight: 700; line-height: 1.4; overflow-wrap: anywhere; }.owner-muted { margin-top: 6px; color: #77746b; font-size: 13px; line-height: 1.5; }.owner-status { padding: 4px 8px; border: 1px solid #d4ddd5; border-radius: 7px; background: #edf2ec; color: #49614f; font-size: 12px; white-space: nowrap; }
.owner-empty { padding: 16px 0; color: #77746b; font-size: 13px; text-align: center; }.owner-search { display: flex; gap: 6px; margin: 12px 0 3px; }.owner-search-input { flex: 1; min-width: 0; height: 42px; padding: 0 12px; background: #f8f6f2; border: 1px solid #e0dcd3; border-radius: 7px; font-size: 13px; }.owner-search button { margin: 0; padding: 0 12px; background: #f5f3ee; font-size: 13px; line-height: 42px; color: #35383b; }
.owner-notice-row { display: flex; align-items: flex-start; gap: 12px; padding: 17px 2px; border-bottom: 1px solid #e0dcd3; }.owner-dot { width: 6px; height: 6px; margin-top: 8px; flex: 0 0 auto; border-radius: 50%; background: #e0dcd3; }.owner-dot.unread { background: #35383b; box-shadow: 0 0 0 4px #eeece7; }.owner-link { color: #35383b; }
.owner-more { margin: 14px 0 8px; padding: 0 12px; border: 1px solid #e0dcd3; border-radius: 8px; background: #fffefa; color: #35383b; font-size: 14px; line-height: 42px; }.owner-profile-note { margin: 16px 0; color: #706d65; font-size: 14px; line-height: 1.8; }.owner-error { margin: 10px 0; padding: 12px; border: 1px solid #ead0cc; border-radius: 10px; color: #a83b3b; background: #fffefa; font-size: 13px; line-height: 1.7; }.owner-error text { display: block; margin-top: 7px; color: #35383b; }
</style>
