<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import BrandHeader from './BrandHeader.vue'
import { logout } from '../services/auth'
import { currentSession, onSessionCleared, sameSession, sessionGeneration } from '../services/session'
import { cancelRequests, RequestError } from '../services/client'
import { shopApi, shopRevision, shopStatus, type ShopCase, type ShopNotice, type ShopTask } from '../services/shop'
import { displayTime } from '../services/owner'
const props = defineProps<{ active: boolean }>()
const tab = ref<'tasks' | 'cases' | 'notices' | 'me'>('tasks')
const navigation: { key: 'tasks' | 'cases' | 'notices' | 'me'; icon: string; label: string }[] = [{ key: 'tasks', icon: 'home', label: '任务' }, { key: 'cases', icon: 'case', label: '案件' }, { key: 'notices', icon: 'bell', label: '通知' }, { key: 'me', icon: 'user', label: '我的' }]
const cases = ref<ShopCase[]>([]), tasks = ref<ShopTask[]>([]), notices = ref<ShopNotice[]>([]), unread = ref(0), query = ref(''), search = ref(''), busy = ref(false), reading = ref(false), error = ref('')
type Kind = 'cases' | 'tasks' | 'notices'
const pages = ref<Record<Kind, number>>({ cases: 1, tasks: 1, notices: 1 }), totals = ref<Record<Kind, number>>({ cases: 0, tasks: 0, notices: 0 })
const scope = 'shop-home'
let alive = true, sequence = 0, timer: ReturnType<typeof setTimeout> | undefined
function pause() { sequence++; if (timer) clearTimeout(timer); cancelRequests(scope); busy.value = false }
function clear() { pause(); cases.value = []; tasks.value = []; notices.value = []; unread.value = 0; totals.value = { cases: 0, tasks: 0, notices: 0 }; query.value = ''; search.value = '' }
const stop = onSessionCleared(clear)
onBeforeUnmount(() => { alive = false; clear(); stop() })
function schedule(delay = 30000) { if (timer) clearTimeout(timer); if (alive && props.active && Object.keys(pages.value).every(key => pages.value[key as Kind] === 1)) timer = setTimeout(() => { void load() }, delay) }
function failure(cause: unknown) { error.value = cause instanceof Error ? cause.message : '加载未完成。'; if (cause instanceof RequestError && [401, 403, 404].indexOf(cause.status) >= 0) clear(); return cause instanceof RequestError && cause.retryAfter ? Math.max(30000, cause.retryAfter * 1000) : 30000 }
async function load() {
  if (!props.active || busy.value) return
  busy.value = true; error.value = ''; const current = ++sequence, stamp = sessionGeneration(); let delay = 30000
  try {
    const [a, b, c, count] = await Promise.all([shopApi.cases(1, search.value), shopApi.tasks(), shopApi.notices(), shopApi.unread()])
    if (!alive || !props.active || current !== sequence || !sameSession(stamp)) return
    cases.value = a.records; tasks.value = b.records; notices.value = c.records; unread.value = count
    pages.value = { cases: 1, tasks: 1, notices: 1 }; totals.value = { cases: a.total, tasks: b.total, notices: c.total }
  } catch (cause) { if (alive && props.active && current === sequence && sameSession(stamp)) delay = failure(cause) }
  finally { if (current === sequence) { busy.value = false; schedule(delay) } }
}
async function more(kind: Kind) {
  if (!props.active || busy.value) return
  busy.value = true; error.value = ''; if (timer) clearTimeout(timer)
  const current = ++sequence, stamp = sessionGeneration(); let delay = 30000
  try {
    if (kind === 'cases') { const data = await shopApi.cases(pages.value.cases + 1, search.value); if (current !== sequence || !props.active || !sameSession(stamp)) return; cases.value = [...cases.value, ...data.records.filter(row => !cases.value.some(old => old.id === row.id))]; pages.value.cases = data.page; totals.value.cases = data.total }
    else if (kind === 'tasks') { const data = await shopApi.tasks(pages.value.tasks + 1); if (current !== sequence || !props.active || !sameSession(stamp)) return; tasks.value = [...tasks.value, ...data.records.filter(row => !tasks.value.some(old => old.key === row.key))]; pages.value.tasks = data.page; totals.value.tasks = data.total }
    else { const data = await shopApi.notices(pages.value.notices + 1); if (current !== sequence || !props.active || !sameSession(stamp)) return; notices.value = [...notices.value, ...data.records.filter(row => !notices.value.some(old => old.id === row.id))]; pages.value.notices = data.page; totals.value.notices = data.total }
  } catch (cause) { if (current === sequence && props.active && sameSession(stamp)) delay = failure(cause) }
  finally { if (current === sequence) { busy.value = false; schedule(delay) } }
}
function open(caseId: string) { uni.navigateTo({ url: `/pages/shop-case/index?id=${caseId}` }) }
async function read(row: ShopNotice) {
  if (reading.value || !props.active) return
  reading.value = true; const stamp = sessionGeneration()
  try { const data = await shopApi.read(row.id); if (!alive || !props.active || !sameSession(stamp)) return; notices.value = notices.value.map(item => item.id === data.id ? data : item); if (!row.read) unread.value = Math.max(0, unread.value - 1); open(data.caseId) }
  catch (cause) { if (alive && props.active && sameSession(stamp)) error.value = cause instanceof Error ? cause.message : '通知暂不能查看。' }
  finally { reading.value = false }
}
function searchCases() { if (!busy.value) { search.value = query.value.trim(); void load() } }
watch(() => props.active, value => { if (value) void load(); else pause() }, { immediate: true })
watch(shopRevision, () => { if (props.active) void load() })
defineExpose({ refresh: load })
</script>
<template><view v-show="active" class="shop-page with-nav"><BrandHeader /><view class="shop-page-title">{{ tab === 'tasks' ? '本店任务' : tab === 'cases' ? '本店案件' : tab === 'notices' ? '站内通知' : '我的' }}</view>
  <view v-if="error" class="shop-error">{{ error }}<text @tap="load">重新加载</text></view>
  <template v-if="tab === 'tasks' || tab === 'cases'"><view class="shop-hero"><image src="/static/repair-shop-service-hero.webp" mode="aspectFill" /><view class="shop-hero-copy"><text class="shop-hero-title">今日工作台</text><text>待办与案件，一目了然</text></view></view>
    <view v-if="tab === 'tasks'" class="shop-card"><view class="shop-heading"><view><text class="shop-index">01 / ACTION</text><text class="shop-title">待执行动作</text></view><text class="shop-small">{{ totals.tasks }} 项</text></view><view v-for="item in tasks" :key="item.key" class="shop-row" @tap="open(item.caseId)"><view><text class="shop-row-title">{{ item.title }}</text><text class="shop-muted">{{ item.deadline ? '截止 ' + displayTime(item.deadline) : '不限期' }}</text></view><text v-if="item.overdue" class="shop-status">已逾期</text></view><text v-if="!tasks.length && !busy" class="shop-muted">当前没有待执行动作</text><button v-if="pages.tasks * 20 < totals.tasks" class="shop-button" :disabled="busy" @tap="more('tasks')">更多待办</button></view>
    <view class="shop-card"><view class="shop-heading"><view><text class="shop-index">02 / CASES</text><text class="shop-title">本店当前案件</text></view><text class="shop-small">全部 {{ totals.cases }} 件</text></view><view class="shop-search"><input v-model="query" class="shop-input" placeholder="搜索案件或车辆" maxlength="120" confirm-type="search" @confirm="searchCases" /><button :disabled="busy" @tap="searchCases">搜索</button></view><view v-for="item in cases" :key="item.id" class="shop-row" @tap="open(item.id)"><view><text class="shop-row-title">{{ item.businessNo || item.claimNo }}</text><text class="shop-muted">{{ item.vehicle || item.plate || '查看案件记录' }}</text></view><text class="shop-status">{{ shopStatus(item.status) }}</text></view><text v-if="!cases.length && !busy" class="shop-muted">暂无属于本店的案件</text><button v-if="pages.cases * 20 < totals.cases" class="shop-button" :disabled="busy" @tap="more('cases')">更多案件</button></view>
  </template>
  <view v-if="tab === 'tasks' || tab === 'notices'" class="shop-card"><view class="shop-heading"><view><text class="shop-index">03 / NOTICE</text><text class="shop-title">最近通知</text></view><text class="shop-small" @tap="tab = 'notices'">{{ tab === 'tasks' ? '查看全部' : '未读 ' + unread + ' 条' }}</text></view><view v-for="item in tab === 'tasks' ? notices.slice(0, 3) : notices" :key="item.id" class="shop-row" @tap="read(item)"><view><text class="shop-row-title">{{ item.read ? '' : '● ' }}{{ item.title }}</text><text v-if="item.reason" class="shop-muted">{{ item.reason }}</text><text class="shop-muted">{{ displayTime(item.createdAt) }}</text></view></view><text v-if="!notices.length && !busy" class="shop-muted">暂无通知</text><button v-if="tab === 'notices' && pages.notices * 20 < totals.notices" class="shop-button" :disabled="busy" @tap="more('notices')">更多通知</button></view>
  <view v-if="tab === 'me'" class="shop-card"><text class="shop-index">01 / PROFILE</text><text class="shop-title">{{ currentSession()?.user.displayName || '我的账号' }}</text><text class="shop-body">账号 ID：{{ currentSession()?.user.id }}</text><text class="shop-muted">所属网点 ID：{{ currentSession()?.user.shopId }}</text><text class="shop-muted">身份及门店由后台核实设置。如需变更，请联系工作人员。</text><button class="shop-button" @tap="logout">退出登录</button></view>
  <text v-if="busy" class="shop-muted shop-empty">正在更新…</text><view class="shop-bottom"><view v-for="item in navigation" :key="item.key" :class="['shop-nav', tab === item.key && 'active']" @tap="tab = item.key"><image :src="`/static/icons/${item.icon}${tab === item.key ? '-active' : ''}.svg`" mode="aspectFit" /><text>{{ item.label }}{{ item.key === 'notices' && unread ? ' (' + Math.min(unread, 99) + ')' : '' }}</text></view></view>
</view></template>
