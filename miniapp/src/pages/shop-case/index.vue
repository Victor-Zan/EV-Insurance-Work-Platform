<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { onHide, onLoad, onPullDownRefresh, onShow, onUnload } from '@dcloudio/uni-app'
import BrandHeader from '../../components/BrandHeader.vue'
import ShopImage from '../../components/ShopImage.vue'
import ShopQuotePanel from '../../components/ShopQuotePanel.vue'
import ShopRepairPanel from '../../components/ShopRepairPanel.vue'
import PrivacyConsent from '../../components/PrivacyConsent.vue'
import { currentSession, revalidateSession, returnToLogin } from '../../services/auth'
import { appVisible } from '../../services/lifecycle'
import { onSessionCleared, sameSession, sessionGeneration } from '../../services/session'
import { cancelRequests, RequestError } from '../../services/client'
import { assignmentCommand, id as resourceId, requireShop, shopApi, shopPending, shopStatus, type QuoteContext, type ShopCase, type ShopPhoto } from '../../services/shop'
import { displayTime } from '../../services/owner'
const id = ref(''), row = ref<ShopCase | null>(null), quote = ref<QuoteContext | null>(null), photos = ref<ShopPhoto[]>([])
const workflowNodes = [['CREATE', '建单'], ['DISPATCH_ARRIVAL', '派单到店'], ['SHOP_QUOTE', '网点报价'], ['QUOTE_REVIEW', '报价审核'], ['INSURER_ASSESSMENT', '保险核损'], ['REPAIR', '维修中'], ['RECEIPT', '收车'], ['FUNDS', '资金流动'], ['COMPLETE', '完成']]
const workflowIndex = computed(() => workflowNodes.findIndex(node => node[0] === row.value?.workflowNode))
const records = ref<{ label: string; createdAt: string; reason: string }[]>([]), photoPage = ref(1), photoTotal = ref(0), historyPage = ref(1), historyTotal = ref(0)
type Section = 'actions' | 'quote' | 'repair' | 'settlement' | 'data' | 'history'
const section = ref<Section>('actions'), tabs: { key: Section; label: string }[] = [{ key: 'actions', label: '任务' }, { key: 'quote', label: '报价' }, { key: 'repair', label: '维修' }, { key: 'settlement', label: '结算' }, { key: 'data', label: '资料' }, { key: 'history', label: '记录' }]
const visible = ref(false), verified = ref(false), busy = ref(false), writing = ref(false), quoteBusy = ref(false), repairBusy = ref(false), error = ref(''), actionError = ref(''), message = ref(''), reason = ref('')
const childBusy = computed(() => quoteBusy.value || repairBusy.value)
const pending = ref<ReturnType<typeof shopPending.get>>(undefined)
const active = computed(() => visible.value && appVisible.value && verified.value)
const scope = 'shop-case'
let alive = true, sequence = 0, checkSequence = 0, timer: ReturnType<typeof setTimeout> | undefined
function pause() { sequence++; checkSequence++; if (timer) clearTimeout(timer); cancelRequests(scope); busy.value = false }
function clearData() { row.value = null; quote.value = null; photos.value = []; records.value = []; photoTotal.value = 0; historyTotal.value = 0 }
const stop = onSessionCleared(() => { pause(); clearData(); reason.value = ''; pending.value = undefined; if (alive) returnToLogin() })
function failure(cause: unknown) { error.value = cause instanceof Error ? cause.message : '案件信息未取得，请重试。'; if (cause instanceof RequestError && [401, 403, 404].indexOf(cause.status) >= 0) clearData(); return cause instanceof RequestError && cause.retryAfter ? Math.max(30000, cause.retryAfter * 1000) : 30000 }
function schedule(delay = 30000) { if (timer) clearTimeout(timer); if (alive && active.value && !pending.value && !childBusy.value && photoPage.value === 1 && historyPage.value === 1) timer = setTimeout(() => { void load() }, delay) }
async function load() {
  if (!active.value || busy.value || writing.value || childBusy.value) return
  busy.value = true; error.value = ''; const current = ++sequence, stamp = sessionGeneration(); let delay = 30000
  try {
    const next = await shopApi.detail(id.value, scope)
    if (!alive || !active.value || current !== sequence || !sameSession(stamp)) return
    const accepted = next.assignment?.status === 'ACCEPTED'
    const [context, pictures, history] = await Promise.all([accepted ? shopApi.quote(id.value, scope) : Promise.resolve(null), accepted ? shopApi.photos(id.value, 1, scope) : Promise.resolve(null), shopApi.history(id.value, 1, scope)])
    if (!alive || !active.value || current !== sequence || !sameSession(stamp)) return
    if (context && context.assignmentVersion !== next.assignment?.version) throw new RequestError('STALE_ASSIGNMENT', '派单状态已变化，请刷新。', 409)
    row.value = next; quote.value = context; photos.value = pictures?.records ?? []; photoPage.value = 1; photoTotal.value = pictures?.total ?? 0; records.value = history.records; historyPage.value = 1; historyTotal.value = history.total
  } catch (cause) { if (alive && active.value && current === sequence && sameSession(stamp)) delay = failure(cause) }
  finally { if (current === sequence) { busy.value = false; schedule(delay) } }
}
async function more(kind: 'photos' | 'history') {
  if (!active.value || busy.value || writing.value) return
  busy.value = true; const current = ++sequence, stamp = sessionGeneration(); let delay = 30000
  try {
    if (kind === 'photos') { const data = await shopApi.photos(id.value, photoPage.value + 1, scope); if (!alive || !active.value || current !== sequence || !sameSession(stamp)) return; photos.value = [...photos.value, ...data.records.filter(item => !photos.value.some(old => old.id === item.id))]; photoPage.value = data.page; photoTotal.value = data.total }
    else { const data = await shopApi.history(id.value, historyPage.value + 1, scope); if (!alive || !active.value || current !== sequence || !sameSession(stamp)) return; records.value = [...records.value, ...data.records]; historyPage.value = data.page; historyTotal.value = data.total }
  } catch (cause) { if (alive && active.value && current === sequence && sameSession(stamp)) delay = failure(cause) }
  finally { if (current === sequence) { busy.value = false; schedule(delay) } }
}
async function enter() {
  verified.value = false
  if (!visible.value || !appVisible.value || !id.value) return
  if (!currentSession()) { returnToLogin(); return }
  const current = ++checkSequence, stamp = sessionGeneration()
  try { requireShop(); await revalidateSession(); requireShop(); if (!alive || !visible.value || !appVisible.value || current !== checkSequence || !sameSession(stamp)) return; verified.value = true; await load() }
  catch (cause) { if (alive && current === checkSequence && sameSession(stamp)) failure(cause) }
}
onLoad(options => { try { id.value = resourceId(options?.id); if (tabs.some(tab => tab.key === options?.section)) section.value = options!.section as Section; const saved = shopPending.get(id.value); if (saved && !saved.draft) pending.value = saved } catch (cause) { failure(cause) } })
onShow(() => { visible.value = true; void enter() })
onHide(() => { visible.value = false; verified.value = false; pause() })
watch(appVisible, value => { if (!value) { verified.value = false; pause() } else if (visible.value) void enter() })
onUnload(() => { alive = false; pause(); clearData(); stop() })
onPullDownRefresh(async () => { try { if (!verified.value) await enter(); else await load() } finally { uni.stopPullDownRefresh() } })
watch(childBusy, value => { if (!value) schedule() })
function back() { uni.navigateBack({ fail: () => uni.reLaunch({ url: '/pages/index/index' }) }) }
function begin(operation: 'accept' | 'reject' | 'arrive' | 'arrival-exceptions' | 'continue-waiting') {
  if (!row.value || !active.value || busy.value || writing.value || pending.value || childBusy.value) return
  actionError.value = ''; const stamp = sessionGeneration()
  const send = () => {
    if (!row.value || !active.value || !sameSession(stamp)) return
    try { pending.value = { title: operation === 'accept' ? '接单' : operation === 'reject' ? '拒单' : operation === 'arrive' ? '确认到店' : operation === 'arrival-exceptions' ? '登记到店异常' : '继续等待', command: assignmentCommand(row.value, operation, reason.value) }; shopPending.set(id.value, pending.value); void execute() }
    catch (cause) { actionError.value = cause instanceof Error ? cause.message : '请核对原因与当前状态。' }
  }
  if (operation === 'arrive' || operation === 'reject') uni.showModal({ title: operation === 'arrive' ? '确认车辆已到店' : '确认拒绝派单', content: operation === 'arrive' ? '请确认车辆实际到店，后续需上传到店照片。' : '请填写拒单原因，确认后此派单将退回。', success: result => { if (result.confirm) send() } })
  else send()
}
async function execute() {
  const command = pending.value; if (!command || !active.value || writing.value) return
  writing.value = true; actionError.value = ''; const stamp = sessionGeneration()
  try {
    const result = await command.command.execute(); if (!sameSession(stamp)) return
    shopPending.delete(id.value); if (!alive) return
    pending.value = undefined; reason.value = ''; message.value = `${command.title}已确认。`
    if (result === null) { clearData(); uni.reLaunch({ url: '/pages/index/index' }); return }
  } catch (cause) {
    if (!alive || !sameSession(stamp)) return
    actionError.value = cause instanceof Error ? cause.message : '结果尚未确认，请按原操作重试。'
    if (cause instanceof RequestError && cause.status >= 400 && cause.status < 500 && cause.status !== 429) { pending.value = undefined; shopPending.delete(id.value); if ([401, 403, 404].indexOf(cause.status) >= 0) clearData(); actionError.value += ' 请刷新并重新核对。' }
  } finally { if (alive && sameSession(stamp)) { writing.value = false; if (!pending.value && active.value) await load(); else schedule() } }
}
</script>
<template><view class="shop-page"><BrandHeader /><view class="shop-top"><text @tap="back">‹ 返回</text><text>案件详情</text><text @tap="enter">刷新</text></view>
  <view v-if="error" class="shop-error">{{ error }}<button class="shop-button" :disabled="busy || writing" @tap="enter">重新核实</button></view><text v-if="!verified && !error" class="shop-muted shop-empty">正在核实账号…</text>
  <view v-show="active" v-if="row"><view class="shop-card"><view class="shop-heading"><text class="shop-title">{{ row.businessNo || row.claimNo }}</text><text class="shop-status">{{ shopStatus(row.status) }}</text></view><text class="shop-muted">{{ row.vehicle }} {{ row.plate }}</text>
      <view v-if="workflowIndex >= 0" class="shop-workflow" role="list" aria-label="案件工作进度">
        <view v-for="(node, index) in workflowNodes" :key="node[0]" :class="['shop-workflow-node', index < workflowIndex ? 'done' : index === workflowIndex ? 'current' : 'locked']" role="listitem" :aria-current="index === workflowIndex ? 'step' : undefined">
          <text class="shop-node-dot">{{ index < workflowIndex ? '✓' : index + 1 }}</text><text class="shop-node-title">{{ node[1] }}</text><text class="shop-node-note">{{ index < workflowIndex ? '已完成' : index === workflowIndex ? '当前阶段' : '尚未到达' }}</text>
        </view>
      </view><text v-else class="shop-muted">进度待核对</text>
    </view>
    <view class="shop-tabs"><view v-for="tab in tabs" :key="tab.key" :class="section === tab.key && 'active'" @tap="section = tab.key">{{ tab.label }}</view></view>
    <view v-if="section === 'actions'" class="shop-card"><text class="shop-title">当前任务</text><view v-if="message" class="shop-message">{{ message }}</view><view v-if="actionError" class="shop-error">{{ actionError }}</view>
      <view v-if="pending" class="shop-pending"><text>{{ pending.title }}结果待确认，原内容已保留。</text><button class="shop-button" :disabled="writing" @tap="execute">按原操作重试</button></view>
      <template v-if="row.status === 'PENDING_ACCEPTANCE'"><text class="shop-body">请核对车辆及案件资料后处理派单。</text><textarea v-model="reason" class="shop-textarea" maxlength="500" placeholder="拒单时请填写原因" :disabled="busy || writing || !!pending" /><button class="shop-button primary" :disabled="busy || writing || !!pending" @tap="begin('accept')">接单</button><button class="shop-button danger" :disabled="busy || writing || !!pending" @tap="begin('reject')">拒单</button></template>
      <template v-else-if="row.status === 'PENDING_ARRIVAL'"><button class="shop-button primary" :disabled="busy || writing || !!pending" @tap="begin('arrive')">确认车辆到店</button><textarea v-model="reason" class="shop-textarea" maxlength="1000" placeholder="如车辆未按约到店，请填写异常原因" :disabled="busy || writing || !!pending" /><button class="shop-button" :disabled="busy || writing || !!pending" @tap="begin('arrival-exceptions')">登记到店异常</button></template>
      <template v-else-if="row.status === 'ARRIVAL_EXCEPTION'"><text class="shop-muted">请核实异常。继续等待后，车辆实际到店时再确认到店。</text><textarea v-model="reason" class="shop-textarea" maxlength="500" placeholder="补充等待说明（可选）" :disabled="busy || writing || !!pending" /><button class="shop-button primary" :disabled="busy || writing || !!pending" @tap="begin('continue-waiting')">继续等待到店</button></template>
      <template v-else-if="['ARRIVED', 'WAITING_QUOTE', 'QUOTE_REVIEWING'].indexOf(row.status) >= 0"><text class="shop-body">请上传本次到店照片并核对维修报价。</text><button class="shop-button primary" @tap="section = 'quote'">查看照片与报价</button></template><template v-else-if="['WAITING_REPAIR_AUTHORIZATION', 'REPAIRING', 'WAITING_OWNER_CONFIRMATION', 'WAITING_INSURER_PAYMENT', 'WAITING_SHOP_SETTLEMENT', 'COMPLETED'].indexOf(row.status) >= 0"><text class="shop-body">{{ shopStatus(row.status) }}</text><button class="shop-button primary" @tap="section = 'repair'">查看维修与收车</button><button class="shop-button" @tap="section = 'settlement'">查看结算结果</button></template><text v-else class="shop-body">{{ shopStatus(row.status) }}。可查看案件资料和已提交的报价记录。</text>
    </view>
    <ShopQuotePanel v-if="quote && row.assignment?.status === 'ACCEPTED'" v-show="section === 'quote'" :key="row.id + ':' + row.assignment.version" :row="row" :quote="quote" :photos="photos" :more-photos="photoPage * 20 < photoTotal" :active="active && section === 'quote'" @busy="quoteBusy = $event" @changed="load" @failed="failure" @more-photos="more('photos')" />
    <view v-if="section === 'quote' && !quote" class="shop-card"><text class="shop-muted">请先接单并确认车辆到店，再上传照片和报价。</text></view>
    <ShopRepairPanel class="shop-repair-panel" v-if="row.assignment?.status === 'ACCEPTED'" v-show="section === 'repair' || section === 'settlement'" :key="'repair:' + row.id + ':' + row.assignment.version" :row="row" :active="active && (section === 'repair' || section === 'settlement')" :mode="section === 'settlement' ? 'settlement' : 'repair'" @busy="repairBusy = $event" @changed="load" @failed="failure" />
    <view v-else-if="section === 'repair' || section === 'settlement'" class="shop-card"><text class="shop-muted">请先接单，维修及结算以本次派单授权为准。</text></view>
    <view v-if="section === 'data'" class="shop-card"><text class="shop-title">案件资料</text><view class="shop-data-row"><text>报案号</text><text>{{ row.claimNo || '未填写' }}</text></view><view class="shop-data-row"><text>车主</text><text>{{ row.owner || '未填写' }}</text></view><view class="shop-data-row"><text>联系信息</text><text>{{ row.phone || '未提供' }}</text></view><view class="shop-data-row"><text>车辆</text><text>{{ row.vehicle }} {{ row.plate }}</text></view><view class="shop-data-row"><text>保险公司</text><text>{{ row.insurance || '未填写' }}</text></view><view class="shop-data-row"><text>事故位置</text><text>{{ row.address || '未填写' }}</text></view><view class="shop-data-row"><text>事故说明</text><text>{{ row.description || '未填写' }}</text></view><view class="shop-grid"><ShopImage v-for="photo in photos" :key="photo.id + ':' + photo.version" :photo-id="photo.id" :version="photo.version" :active="active && section === 'data'" /></view><button v-if="photoPage * 20 < photoTotal" class="shop-button" :disabled="busy" @tap="more('photos')">更多维修照片</button></view>
    <view v-if="section === 'history'" class="shop-card"><text class="shop-title">案件记录</text><view v-for="(item, n) in records" :key="n" class="shop-record"><text class="shop-body">{{ item.label }}</text><text class="shop-muted">{{ displayTime(item.createdAt) }}</text><text v-if="item.reason" class="shop-muted">{{ item.reason }}</text></view><text v-if="!records.length" class="shop-muted">暂无记录</text><button v-if="historyPage * 20 < historyTotal" class="shop-button" :disabled="busy" @tap="more('history')">更多记录</button></view>
  </view><text v-if="busy" class="shop-muted shop-empty">正在更新…</text>
<PrivacyConsent :active="active" /></view></template>
