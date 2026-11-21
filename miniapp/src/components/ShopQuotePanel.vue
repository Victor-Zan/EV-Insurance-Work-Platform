<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import ShopImage from './ShopImage.vue'
import { cancelRequests, RequestError } from '../services/client'
import { onSessionCleared, sameSession, sessionGeneration } from '../services/session'
import { shopPending, quoteEditable, shopApi, type QuoteContext, type QuoteLine, type RawHistory, type ShopCase, type ShopCommand, type ShopPhoto } from '../services/shop'
import { adoptCandidate, historicalMock, ocrApi, ocrError, ocrPending, ocrRetryCommand, ocrUploadCommand, preparedQuoteCommand, sourceLabel, type OcrJob, type OcrProgram } from '../services/shop-ocr'
import { MaterialUploadQueue, createMaterialUpload, type UploadFile } from '../services/upload'
import { pickOriginalImages } from '../services/pick-images'
import { displayAmount, quoteTotal } from '../services/quote-money'
import { displayTime } from '../services/owner'
const props = defineProps<{ row: ShopCase; quote: QuoteContext; active: boolean; photos: ShopPhoto[]; morePhotos: boolean }>()
const emit = defineEmits<{ changed: []; busy: [boolean]; failed: [unknown]; morePhotos: [] }>()
const blank = (): QuoteLine => ({ description: '', quantity: '', unitPrice: '' })
const saved = shopPending.get(props.row.id)
const pending = ref(saved?.draft ? saved : null), draft = ref<QuoteLine[]>(pending.value?.draft ? JSON.parse(JSON.stringify(pending.value.draft)) : [blank()])
const working = ref(false), message = ref(''), error = ref(''), ocrMessage = ref(''), provider = ref(''), jobs = ref<OcrJob[]>([]), programs = ref<Record<string, OcrProgram>>({}), loadedVersions: Record<string, number> = {}
const ignored = ref<Record<string, number[]>>({}), targets = ref<Record<string, number>>({}), rawText = ref<Record<string, string>>({}), jobPage = ref(1), jobTotal = ref(0), history = ref<RawHistory[]>([]), historyPage = ref(0), historyTotal = ref(0)
const category = ref<'SHOP_QUOTE' | 'ASSESSMENT'>('SHOP_QUOTE'), categories = ['报价清单', '网点定损清单'], selected = ref<UploadFile | null>(null)
const uploadKey = `${props.row.id}/upload`, selectedUpload = ref<ShopCommand<OcrJob> | null>(ocrPending.get(uploadKey) ?? null)
const queue = new MaterialUploadQueue(), uploads = queue.entries, uploaded = new Set<string>()
const editable = computed(() => quoteEditable(props.row, props.quote)), locked = computed(() => !props.active || !editable.value || working.value || !!pending.value)
const blocked = computed(() => working.value || !!pending.value || uploads.value.some(item => item.state === 'uploading' || item.state === 'waiting'))
const total = computed(() => { try { return quoteTotal(draft.value) } catch { return '待核对' } })
const scope = `shop-quote:${props.row.id}`
let alive = true, sequence = 0, polls = 0, timer: ReturnType<typeof setTimeout> | undefined
watch(blocked, value => emit('busy', value), { immediate: true, flush: 'sync' })
function pause() { sequence++; if (timer) clearTimeout(timer); timer = undefined; cancelRequests(scope) }
function clear() { pause(); draft.value = [blank()]; jobs.value = []; programs.value = {}; rawText.value = {}; selected.value = null; selectedUpload.value = null; pending.value = null; queue.dispose() }
const stop = onSessionCleared(clear)
onBeforeUnmount(() => { alive = false; clear(); stop(); emit('busy', false) })
function feedback(cause: unknown) {
  error.value = cause instanceof Error ? cause.message : '操作未完成，请重试。'
  if (cause instanceof RequestError && [401, 403, 404].indexOf(cause.status) >= 0) emit('failed', cause)
}
function schedule() {
  if (timer) clearTimeout(timer)
  if (!alive || !props.active || !editable.value || working.value || polls >= 120 || !jobs.value.some(job => ['QUEUED', 'RUNNING'].indexOf(job.state) >= 0)) return
  timer = setTimeout(() => { void poll() }, 2000)
}
async function readProgram(job: OcrJob, auto = false) {
  const stamp = sessionGeneration(), current = sequence
  try {
    const data = await ocrApi.program(props.row.id, job.id, scope)
    if (!alive || !props.active || current !== sequence || !sameSession(stamp)) return
    programs.value = { ...programs.value, [job.id]: data }; loadedVersions[job.id] = job.reviewVersion
    if (auto && editable.value && !pending.value && provider.value && !historicalMock(job, provider.value) && draft.value.every(line => !line.description && !line.quantity && !line.unitPrice) && data.items.length) {
      let lines: QuoteLine[] = []
      data.items.forEach((item, index) => { lines = adoptCandidate(lines, item, job, index, provider.value) })
      draft.value = lines; ocrMessage.value = '识别完成，请对照原图核对下方清单。'
    }
  } catch (cause) { if (alive && props.active && current === sequence && sameSession(stamp)) feedback(cause) }
}
async function loadJobs(reset = true) {
  if (!props.active || !editable.value || !props.quote.arrivalPhotoUploaded) return
  const current = sequence, stamp = sessionGeneration()
  try {
    const [currentProvider, data] = await Promise.all([ocrApi.configuration(props.row.id, scope), ocrApi.list(props.row.id, props.quote.assignmentVersion, reset ? 1 : jobPage.value + 1, scope)])
    if (!alive || !props.active || current !== sequence || !sameSession(stamp)) return
    provider.value = currentProvider; jobs.value = reset ? data.records : [...jobs.value, ...data.records.filter(job => !jobs.value.some(old => old.id === job.id))]; jobPage.value = data.page; jobTotal.value = data.total
    for (const job of jobs.value) if (job.state === 'SUCCEEDED' && programs.value[job.id] && loadedVersions[job.id] !== job.reviewVersion) { delete programs.value[job.id] }
    const first = jobs.value.find(job => job.state === 'SUCCEEDED')
    if (first && draft.value.every(line => !line.description && !line.quantity && !line.unitPrice)) await readProgram(first, true)
    schedule()
  } catch (cause) { if (alive && props.active && current === sequence && sameSession(stamp)) { ocrMessage.value = cause instanceof Error ? cause.message : '识别任务暂不可读，可继续手填。'; if (cause instanceof RequestError && [403, 404].indexOf(cause.status) >= 0) emit('failed', cause) } }
}
async function poll() {
  if (!props.active || !editable.value || working.value) { schedule(); return }
  const current = sequence, stamp = sessionGeneration(); polls++
  try {
    for (const job of [...jobs.value]) {
      if (['QUEUED', 'RUNNING'].indexOf(job.state) < 0) continue
      const next = await ocrApi.get(props.row.id, job.id, scope)
      if (!alive || !props.active || current !== sequence || !sameSession(stamp)) return
      jobs.value = jobs.value.map(item => item.id === next.id ? next : item)
      if (next.state === 'SUCCEEDED') await readProgram(next, true)
    }
    if (polls >= 120) ocrMessage.value = '等待较久，已暂停查询；可继续手填或点击刷新任务。'
    schedule()
  } catch (cause) { if (alive && props.active && current === sequence && sameSession(stamp)) { ocrMessage.value = cause instanceof Error ? cause.message : '任务查询未完成，已暂停；请手动刷新。'; if (cause instanceof RequestError && [403, 404].indexOf(cause.status) >= 0) emit('failed', cause) } }
}
watch(() => props.active, value => { if (value) { polls = 0; void loadJobs() } else pause() }, { immediate: true })
watch(() => props.quote.arrivalPhotoUploaded, value => { if (value && props.active) void loadJobs() })
watch(editable, value => { if (!value) pause(); else if (props.active) void loadJobs() })
watch(uploads, async entries => {
  let changed = false
  entries.forEach(entry => { if (entry.state === 'uploaded' && !uploaded.has(entry.command.idempotencyKey)) { uploaded.add(entry.command.idempotencyKey); changed = true } })
  if (changed && alive) { await nextTick(); if (props.active) emit('changed') }
})
async function chooseArrival() {
  if (locked.value) return
  const version = props.quote.assignmentVersion, stamp = sessionGeneration(); error.value = ''
  try {
    const files = await pickOriginalImages(9)
    if (!alive || !props.active || !sameSession(stamp) || version !== props.quote.assignmentVersion) return
    queue.enqueue(files.map(file => createMaterialUpload(props.row.id, 'ARRIVAL_PHOTO', version, file)))
  } catch (cause) { if (alive && props.active && sameSession(stamp) && !(cause instanceof RequestError && cause.code === 'PICK_CANCELLED')) feedback(cause) }
}
async function chooseOcr() {
  if (locked.value || selectedUpload.value || jobTotal.value >= 20) return
  const version = props.quote.assignmentVersion, stamp = sessionGeneration()
  try { const files = await pickOriginalImages(1); if (alive && props.active && sameSession(stamp) && version === props.quote.assignmentVersion) selected.value = files[0] ?? null }
  catch (cause) { if (alive && props.active && sameSession(stamp) && !(cause instanceof RequestError && cause.code === 'PICK_CANCELLED')) feedback(cause) }
}
async function uploadOcr() {
  if (locked.value || (!selected.value && !selectedUpload.value)) return
  const stamp = sessionGeneration(); working.value = true; error.value = ''
  try {
    if (!selectedUpload.value) { selectedUpload.value = ocrUploadCommand(props.row, selected.value!, Math.min(20, jobTotal.value + 1), category.value); ocrPending.set(uploadKey, selectedUpload.value) }
    const job = await selectedUpload.value.execute()
    if (!sameSession(stamp)) return
    ocrPending.delete(uploadKey)
    if (!alive) return
    selectedUpload.value = null; selected.value = null; polls = 0
    if (props.active) { await loadJobs(); if (job.state === 'SUCCEEDED') await readProgram(job) }
  } catch (cause) {
    if (!alive || !sameSession(stamp)) return
    feedback(cause)
    if (cause instanceof RequestError && cause.status >= 400 && cause.status < 500 && cause.status !== 429) { selectedUpload.value = null; ocrPending.delete(uploadKey); emit('changed') }
  } finally { if (alive && sameSession(stamp)) { working.value = false; schedule() } }
}
function confirm(content: string, action: () => void) {
  const stamp = sessionGeneration()
  uni.showModal({ title: '请核对', content, success: result => { if (result.confirm && alive && !locked.value && sameSession(stamp)) action() } })
}
function use(job: OcrJob, index: number, replace = false) {
  if (locked.value) return
  const item = programs.value[job.id]?.items[index]; if (!item) return
  const target = replace ? targets.value[`${job.id}:${index}`] : undefined
  const action = () => { try { draft.value = adoptCandidate(draft.value, item, job, index, provider.value, target); ocrMessage.value = '已填入报价表，请核对数量、单价和重复项目。' } catch (cause) { feedback(cause) } }
  if (replace) { if (target === undefined) { error.value = '请先选择要替换的报价行。'; return } confirm('将替换选定行的人工填写内容，确认继续？', action) }
  else if (draft.value.some(line => line.description.trim() === item.description.trim())) confirm('已有同名项目，可能重复计费。仍要新增独立一行吗？', action)
  else action()
}
function ignore(job: OcrJob, index: number) {
  if (locked.value) return
  const action = () => { ignored.value = { ...ignored.value, [job.id]: [...(ignored.value[job.id] ?? []), index] }; draft.value = draft.value.filter(line => !(line.ocr?.jobId === job.id && line.ocr.itemIndex === index)); if (!draft.value.length) draft.value = [blank()] }
  if (draft.value.some(line => line.ocr?.jobId === job.id && line.ocr.itemIndex === index)) confirm('此候选已进入报价表，将一并移除该行的人工填写内容，确认继续？', action); else action()
}
function targetChanged(jobId: string, index: number, event: { detail: { value: string | number } }) { targets.value[`${jobId}:${index}`] = Number(event.detail.value) }
function categoryChanged(event: { detail: { value: string | number } }) { category.value = Number(event.detail.value) === 1 ? 'ASSESSMENT' : 'SHOP_QUOTE' }
async function retry(job: OcrJob) {
  if (locked.value) return
  const stamp = sessionGeneration(), key = `${props.row.id}/${job.id}/retry`; working.value = true; error.value = ''
  try {
    let command = ocrPending.get(key); if (!command) { command = ocrRetryCommand(props.row.id, job); ocrPending.set(key, command) }
    const result = await command.execute(); if (!sameSession(stamp)) return
    ocrPending.delete(key); if (!alive) return
    jobs.value = jobs.value.map(item => item.id === job.id ? result : item); delete programs.value[job.id]; delete loadedVersions[job.id]; delete rawText.value[job.id]
    draft.value = draft.value.map(line => { if (line.ocr?.jobId !== job.id) return line; const manual = { ...line }; delete manual.ocr; return manual })
    ocrMessage.value = '已明确重新识别；已填内容保留并转为手填，旧引用已移除。'; polls = 0
  } catch (cause) { if (alive && sameSession(stamp)) { feedback(cause); if (cause instanceof RequestError && cause.status >= 400 && cause.status < 500 && cause.status !== 429) { ocrPending.delete(key); await loadJobs() } } }
  finally { if (alive && sameSession(stamp)) { working.value = false; schedule() } }
}
async function readRaw(job: OcrJob) { const stamp = sessionGeneration(); try { const value = await ocrApi.raw(props.row.id, job.id, scope); if (alive && props.active && sameSession(stamp)) rawText.value = { ...rawText.value, [job.id]: value } } catch (cause) { if (alive && sameSession(stamp)) feedback(cause) } }
async function submit() {
  if (locked.value) return
  working.value = true; error.value = ''; message.value = ''; const stamp = sessionGeneration()
  try {
    if (draft.value.some(line => line.ocr)) provider.value = await ocrApi.configuration(props.row.id, scope)
    if (!alive || !props.active || !sameSession(stamp)) return
    const command = preparedQuoteCommand(props.row, props.quote, draft.value, jobs.value, provider.value)
    pending.value = { title: '提交报价', command, draft: JSON.parse(JSON.stringify(draft.value)) }; shopPending.set(props.row.id, pending.value)
  } catch (cause) { if (alive && sameSession(stamp)) feedback(cause) }
  finally { if (alive && sameSession(stamp)) working.value = false }
  if (pending.value && alive && props.active) await executePending()
}
async function executePending() {
  const command = pending.value; if (!command || !props.active || working.value) return
  working.value = true; error.value = ''; const stamp = sessionGeneration()
  try {
    await command.command.execute(); if (!sameSession(stamp)) return
    shopPending.delete(props.row.id); if (!alive) return
    pending.value = null; message.value = '报价新版本已提交，原报价与识别依据保留。'; emit('changed'); await loadJobs()
  } catch (cause) {
    if (!alive || !sameSession(stamp)) return
    feedback(cause)
    if (cause instanceof RequestError && cause.status >= 400 && cause.status < 500 && cause.status !== 429) { pending.value = null; shopPending.delete(props.row.id); error.value += ' 请刷新并重新核对。'; emit('changed'); await loadJobs() }
    else error.value += ' 请按原操作重试，勿重复创建报价。'
  } finally { if (alive && sameSession(stamp)) { working.value = false; await nextTick(); if (props.active && !pending.value) emit('changed'); schedule() } }
}
async function moreHistory() {
  if (!props.active || working.value) return
  working.value = true; const stamp = sessionGeneration()
  try { const data = await shopApi.quoteHistory(props.row.id, historyPage.value + 1, scope); if (alive && props.active && sameSession(stamp)) { history.value = [...history.value, ...data.records.filter(item => !history.value.some(old => old.id === item.id))]; historyPage.value = data.page; historyTotal.value = data.total } }
  catch (cause) { if (alive && sameSession(stamp)) feedback(cause) }
  finally { if (alive && sameSession(stamp)) working.value = false }
}
</script>
<template><view>
  <view v-if="error" class="shop-error">{{ error }}</view><view v-if="message" class="shop-message">{{ message }}</view>
  <view v-if="pending" class="shop-pending"><text>报价结果待确认，原内容已保留。</text><button class="shop-button" :disabled="working || !active" @tap="executePending">按原操作重试</button></view>
  <view class="shop-card"><text class="shop-index">02 / ARRIVAL</text><text class="shop-title">到店照片</text><text class="shop-muted">{{ quote.arrivalPhotoUploaded ? '已核实本次派单到店照片，可以填写报价。' : '请先上传本次派单到店照片，再填写报价。' }}</text>
    <view class="shop-grid"><ShopImage v-for="photo in photos.filter(item => item.category === 'ARRIVAL_PHOTO')" :key="photo.id + ':' + photo.version" :photo-id="photo.id" :version="photo.version" :active="active" caption="到店照片" /></view>
    <button v-if="morePhotos" class="shop-button" :disabled="working || !active" @tap="emit('morePhotos')">更多照片</button><button v-if="editable" class="shop-button" :disabled="locked" @tap="chooseArrival">选择并上传到店原图</button>
    <view v-for="entry in uploads" :key="entry.command.idempotencyKey" class="shop-record"><text class="shop-muted">{{ entry.command.file.name }} · {{ entry.state === 'uploaded' ? '已上传' : entry.state === 'failed' ? '未确认上传' : '上传中' }}</text><text v-if="entry.result?.status === 'failed'" class="shop-muted">{{ entry.result.message }}</text><button v-if="entry.state === 'failed' && entry.result?.status === 'failed' && entry.result.retryable" class="shop-button" :disabled="!active || (entry.retryAt || 0) > Date.now()" @tap="queue.retry(entry.command.idempotencyKey)">原文件重试</button></view>
  </view>
  <template v-if="quote.arrivalPhotoUploaded && editable">
    <view class="shop-card"><text class="shop-index">03 / RECOGNITION</text><text class="shop-title">清单识别（可选）</text><text class="shop-muted">支持JPG/PNG原图，每张不超过10MiB，最多20张。也可直接在下方手填报价。</text>
      <picker :range="categories" :disabled="locked || !!selectedUpload" @change="categoryChanged"><view class="shop-input">{{ category === 'SHOP_QUOTE' ? categories[0] : categories[1] }} ▾</view></picker>
      <button class="shop-button" :disabled="locked || !!selectedUpload || jobTotal >= 20" @tap="chooseOcr">选择清单原图</button><text v-if="selected" class="shop-muted">已选择 {{ selected.name }}</text><button v-if="selected || selectedUpload" class="shop-button primary" :disabled="locked" @tap="uploadOcr">{{ selectedUpload ? '按原文件重试上传识别' : '上传并识别' }}</button>
      <text v-if="ocrMessage" class="shop-muted">{{ ocrMessage }}</text><button class="shop-button" :disabled="working || !active" @tap="polls = 0; loadJobs()">刷新识别任务</button>
      <view v-for="job in jobs" :key="job.id" class="shop-record"><text class="shop-row-title">第 {{ job.pageNo }} 张 · {{ job.state === 'QUEUED' ? '排队中' : job.state === 'RUNNING' ? '识别中' : job.state === 'SUCCEEDED' ? '候选待核对' : '识别失败' }}</text><text class="shop-muted">{{ sourceLabel(job, provider) }} · 已重识别 {{ job.rerunsUsed }}/3 次</text><text v-if="job.errorCode" class="shop-muted">{{ ocrError(job.errorCode) }}</text>
        <ShopImage :case-id="row.id" :job-id="job.id" :version="job.reviewVersion" :active="active" caption="原始清单，点击放大核对" />
        <button v-if="job.state === 'SUCCEEDED'" class="shop-button" :disabled="working || !active" @tap="readProgram(job)">查看识别候选</button><button class="shop-button" :disabled="locked || (job.rerunsUsed >= 3 && !ocrPending.has(row.id + '/' + job.id + '/retry')) || job.state === 'QUEUED' || job.state === 'RUNNING'" @tap="confirm('将使用原图重新识别，可能消耗识别额度。已填内容保留并转为手填，确认继续？', () => retry(job))">{{ ocrPending.has(row.id + '/' + job.id + '/retry') ? '重试原重识别操作' : '明确重新识别' }}</button>
        <template v-if="programs[job.id]"><text v-if="programs[job.id]!.total" class="shop-muted">单据原始合计：{{ programs[job.id]!.total }} 元（不计入报价合计）</text><text v-for="(warning, n) in programs[job.id]!.warnings" :key="n" class="shop-muted">{{ warning }}</text>
          <view v-for="(item, n) in programs[job.id]!.items" :key="n" v-show="(ignored[job.id] || []).indexOf(n) < 0" class="shop-line"><text class="shop-row-title">{{ item.description || '待核对项目' }}</text><text class="shop-muted">数量 {{ item.quantity || '待核对' }} {{ item.unit }} · 单价 {{ item.unitPrice || '待核对' }} 元 · 原行金额 {{ item.lineAmount || '未识别' }}</text><text v-if="item.evidence" class="shop-muted">原文 / 位置 / 置信度：{{ item.evidence }}</text><button class="shop-button" :disabled="locked || historicalMock(job, provider)" @tap="use(job, n)">添加到报价表</button><picker :range="draft.map((line, i) => '第' + (i + 1) + '行 ' + (line.description || '空白'))" :disabled="locked" @change="targetChanged(job.id, n, $event)"><view class="shop-input">{{ targets[job.id + ':' + n] === undefined ? '选择替换行' : '第' + (targets[job.id + ':' + n]! + 1) + '行' }}</view></picker><text class="shop-link" @tap="use(job, n, true)">核对并替换</text><text class="shop-link" @tap="ignore(job, n)">忽略 / 移除候选</text></view>
          <button v-if="ignored[job.id]?.length" class="shop-button" :disabled="locked" @tap="ignored[job.id] = []">重新显示忽略候选</button><text v-for="(line, n) in programs[job.id]!.lines" :key="n" class="shop-muted">{{ line }}</text>
        </template><button class="shop-button" :disabled="!active || working" @tap="readRaw(job)">读取原始文字返回</button><text v-if="rawText[job.id]" class="shop-muted">{{ rawText[job.id] }}</text>
      </view><button v-if="jobPage * 20 < jobTotal" class="shop-button" :disabled="working || !active" @tap="loadJobs(false)">更多识别任务</button>
    </view>
    <view class="shop-card"><text class="shop-index">04 / QUOTATION</text><text class="shop-title">维修报价清单</text><text class="shop-muted">单价为元，最多两位小数；数量为正整数。提交前请逐行核对。</text>
      <view v-for="(line, n) in draft" :key="n" class="shop-line"><text class="shop-small">第 {{ n + 1 }} 项</text><input v-model="line.description" class="shop-input" maxlength="255" placeholder="项目或配件名称" :disabled="locked" /><view class="shop-line-inputs"><input v-model="line.quantity" class="shop-input" type="number" maxlength="10" placeholder="数量" :disabled="locked" @input="line.quantityDefaulted = false" /><input v-model="line.unitPrice" class="shop-input" type="digit" maxlength="39" placeholder="单价（元）" :disabled="locked" /></view><text v-if="line.quantityDefaulted" class="shop-muted">默认 1 · 待核对</text><text class="shop-line-total">小计 {{ displayAmount(line.quantity, line.unitPrice) }} 元</text><text v-if="line.ocr" class="shop-muted">源自第 {{ (jobs.find(job => job.id === line.ocr?.jobId)?.pageNo) || '?' }} 张第 {{ line.ocr.itemIndex + 1 }} 行，提交时保存人工核对快照。</text><text v-if="line.ocr" class="shop-link" @tap="!locked && (line.ocr = undefined)">转为手填</text><text class="shop-link" @tap="!locked && (draft.length > 1 ? draft.splice(n, 1) : draft = [blank()])">删除此行</text></view>
      <button class="shop-button" :disabled="locked || draft.length >= 1000" @tap="draft.push(blank())">增加项目</button><text class="shop-total">合计 {{ total }} 元</text><button class="shop-button primary" :disabled="locked" @tap="submit">提交报价新版本</button>
    </view>
  </template>
  <view v-if="quote.rawQuote" class="shop-card"><text class="shop-index">05 / SUBMITTED</text><text class="shop-title">已提交原始报价 v{{ quote.rawQuote.version }}</text><view v-for="(line, n) in quote.rawQuote.lines" :key="n" class="shop-record"><text class="shop-body">{{ line.description }}</text><text class="shop-muted">{{ line.quantity }} × {{ line.unitPrice }} 元 = {{ line.amount }} 元</text></view><text class="shop-total">合计 {{ quote.rawQuote.total }} 元</text></view>
  <view class="shop-card"><text class="shop-title">维修授权条件</text><view class="shop-data-row"><text>最终核损金额已录入</text><text>{{ quote.gate.amount ? '已满足' : '未满足' }}</text></view><view class="shop-data-row"><text>保险公司已确认</text><text>{{ quote.gate.insurer ? '已满足' : '未满足' }}</text></view><view class="shop-data-row"><text>客服已确认</text><text>{{ quote.gate.service ? '已满足' : '未满足' }}</text></view><text class="shop-muted">{{ quote.authorized && quote.gate.allowed ? '维修授权条件已满足。' : '请等待客服完成确认。' }}</text></view>
  <view class="shop-card"><text class="shop-title">原始报价版本</text><view v-for="item in history" :key="item.id" class="shop-record"><text class="shop-body">原始报价 v{{ item.version }} · {{ item.total }} 元</text><text class="shop-muted">{{ displayTime(item.createdAt) }}</text></view><button v-if="!historyPage || historyPage * 20 < historyTotal" class="shop-button" :disabled="working || !active" @tap="moreHistory">{{ historyPage ? '更多报价版本' : '查看报价版本' }}</button></view>
</view></template>
