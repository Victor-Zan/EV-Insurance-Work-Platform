<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ElButton, ElDrawer, ElPagination, ElSkeleton } from 'element-plus'
import { httpClient } from '@/shared/http/client'
import type { ApiResponse, Page } from '@/shared/http/create-client'
import { priceNatures, sampleFields, sampleSources, sampleStates } from './samples'
import type { PriceSample, ReferenceGroup } from './samples'

interface RecordDetails { record: PriceSample; relatedQuotes: PriceSample[] }
interface ReviewVersion { revision: number; status: keyof typeof sampleStates; amount?: string; reason: string; created_at: string; created_by: number }
const props = defineProps<{ group: ReferenceGroup; filters: Record<string, string | number | undefined> }>()
const emit = defineEmits<{ close: [] }>()
const open = ref(true), rows = ref<PriceSample[]>([]), page = ref(1), total = ref(0)
const loading = ref(false), error = ref(''), selectedId = ref('')
const details = ref<RecordDetails>(), detailLoading = ref(false), detailError = ref(''), downloadError = ref('')
const history = ref<ReviewVersion[]>([]), historyPage = ref(1), historyTotal = ref(0), historyLoading = ref(false), historyError = ref('')
const api = '/pricing/samples'
const evidenceLabels: Record<string, string> = {
  ...Object.fromEntries(sampleFields), historicalCaseId: '来源档案编号', lineNumber: '来源行号', vehicleBrand: '原始车辆品牌',
  sheetName: '来源工作表',
  priceNature: '原始价格性质', caseId: '来源工单编号', quoteId: '来源报价编号', quantity: '数量', lineTotal: '原始行金额',
}
let rowsRequest = 0, detailsRequest = 0, historyRequest = 0

function textValue(value: unknown): string {
  if (value === null || value === undefined || value === '') return '未提供'
  return typeof value === 'object' ? JSON.stringify(value) : String(value)
}

function nature(value: PriceSample['priceNature']) {
  return value ? priceNatures[value] : '未注明'
}

async function loadRows() {
  const request = ++rowsRequest
  const group = props.group
  loading.value = true
  error.value = ''
  try {
    const { data: { data } } = await httpClient.get<ApiResponse<Page<PriceSample>>>(api, { params: {
      ...props.filters, partId: group.part_id, modelId: group.model_id, unit: group.unit, partBrand: group.part_brand,
      region: group.region, priceNature: group.price_nature, exactGroup: true, reference: true, page: page.value, size: 20,
    } })
    if (request !== rowsRequest) return
    rows.value = data.records
    total.value = data.total
    const first = data.records[0]
    if (first) void select(first.id)
    else { selectedId.value = ''; details.value = undefined }
  } catch (cause) {
    if (request === rowsRequest) error.value = cause instanceof Error ? cause.message : '记录加载失败'
  } finally {
    if (request === rowsRequest) loading.value = false
  }
}

async function loadHistory() {
  const id = selectedId.value
  const request = ++historyRequest
  historyLoading.value = true
  historyError.value = ''
  try {
    const { data: { data } } = await httpClient.get<ApiResponse<Page<ReviewVersion>>>(`${api}/${id}/history`, { params: { page: historyPage.value, size: 10 } })
    if (request !== historyRequest) return
    history.value = data.records
    historyTotal.value = data.total
  } catch (cause) {
    if (request === historyRequest) historyError.value = cause instanceof Error ? cause.message : '核对历史加载失败'
  } finally {
    if (request === historyRequest) historyLoading.value = false
  }
}

async function select(id: string) {
  const request = ++detailsRequest
  selectedId.value = id
  details.value = undefined
  detailLoading.value = true
  detailError.value = ''
  downloadError.value = ''
  history.value = []
  historyPage.value = 1
  historyTotal.value = 0
  void loadHistory()
  try {
    const { data } = await httpClient.get<ApiResponse<RecordDetails>>(`${api}/${id}`)
    if (request === detailsRequest) details.value = data.data
  } catch (cause) {
    if (request === detailsRequest) detailError.value = cause instanceof Error ? cause.message : '记录详情加载失败'
  } finally {
    if (request === detailsRequest) detailLoading.value = false
  }
}

async function download(record: PriceSample) {
  downloadError.value = ''
  try {
    const { data } = await httpClient.get<Blob>(`${api}/batches/${record.batchId}/source`, { responseType: 'blob' })
    const url = URL.createObjectURL(data), link = document.createElement('a')
    link.href = url
    link.download = record.fileName || '价格来源'
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (cause) {
    downloadError.value = cause instanceof Error ? cause.message : '下载失败'
  }
}

onMounted(loadRows)
onBeforeUnmount(() => { rowsRequest++; detailsRequest++; historyRequest++ })
</script>

<template>
  <ElDrawer v-model="open" title="价格记录" size="min(760px, 100vw)" append-to-body @closed="emit('close')">
    <header class="group-heading"><h2>{{ group.part_name }}</h2><p>{{ group.vehicle_brand }} · {{ group.model_name }} · {{ priceNatures[group.price_nature] }}</p></header>
    <ElSkeleton v-if="loading" :rows="6" animated />
    <p v-else-if="error" class="error" role="alert">{{ error }} <ElButton text @click="loadRows">重试</ElButton></p>
    <p v-else-if="!rows.length" class="muted">当前筛选下暂无已确认记录。</p>
    <template v-else>
      <section v-if="total > 1" class="record-picker" aria-label="选择价格记录">
        <h3>参考记录 <small>{{ total }} 条</small></h3>
        <button v-for="row in rows" :key="row.id" type="button" :aria-pressed="selectedId === row.id" @click="select(row.id)">
          <span>{{ sampleSources[row.sourceKind] }} · {{ row.importedDate }}</span><strong>{{ row.amount }} 元 / {{ row.unit }}</strong>
        </button>
        <ElPagination v-if="total > 20" v-model:current-page="page" :total="total" :page-size="20" layout="prev,pager,next" @current-change="loadRows" />
      </section>
      <ElSkeleton v-if="detailLoading" :rows="7" animated />
      <p v-else-if="detailError" class="error" role="alert">{{ detailError }} <ElButton text @click="select(selectedId)">重试</ElButton></p>
      <template v-else-if="details">
        <section class="record-section">
          <h3>{{ details.relatedQuotes.length > 1 ? '同一来源行的报价' : '参考报价' }}</h3>
          <div class="quotes" :class="{ paired: details.relatedQuotes.length > 1 }">
            <div v-for="quote in details.relatedQuotes.length ? details.relatedQuotes : [details.record]" :key="quote.id" class="quote">
              <span>{{ nature(quote.priceNature) }}</span>
              <strong>{{ quote.amount }} <small>元 / {{ quote.unit }}</small></strong>
              <p>原始金额 {{ textValue(quote.original.amount) }} 元</p>
              <p>{{ quote.reason }}</p>
            </div>
          </div>
        </section>
        <section class="record-section">
          <h3>配件与来源</h3>
          <dl>
            <dt>标准配件</dt><dd>{{ textValue(details.record.partName) }}</dd>
            <dt>车辆品牌</dt><dd>{{ textValue(details.record.vehicleBrand) }}</dd>
            <dt>适配车型</dt><dd>{{ textValue(details.record.modelName) }}</dd>
            <dt>配件品牌</dt><dd>{{ textValue(details.record.partBrand) }}</dd>
            <dt>计量单位</dt><dd>{{ textValue(details.record.unit) }}</dd>
            <dt>地区</dt><dd>{{ textValue(details.record.region) }}</dd>
            <dt>来源</dt><dd>{{ sampleSources[details.record.sourceKind] }}</dd>
            <dt>导入日期</dt><dd>{{ details.record.importedDate }}</dd>
            <dt>核对状态</dt><dd>{{ sampleStates[details.record.status] }} · 第 {{ details.record.version }} 版</dd>
            <dt v-if="details.record.fileName">来源文件</dt><dd v-if="details.record.fileName">{{ details.record.fileName }}</dd>
          </dl>
          <div class="source-actions">
            <ElButton v-if="details.record.hasFile" text @click="download(details.record)">下载原始文件</ElButton>
            <RouterLink v-if="details.record.original.historicalCaseId" :to="{ path: '/historical-cases', query: { archiveId: details.record.original.historicalCaseId } }">查看来源档案</RouterLink>
            <RouterLink v-if="details.record.original.caseId" :to="`/work-orders/${details.record.original.caseId}`">查看来源工单</RouterLink>
          </div>
          <p v-if="downloadError" class="error" role="alert">{{ downloadError }}</p>
        </section>
        <section class="record-section">
          <h3>原始记录</h3>
          <dl><template v-for="(value,key) in details.record.original" :key="key"><dt>{{ evidenceLabels[key] || key }}</dt><dd>{{ key === 'priceNature' ? (priceNatures[value as keyof typeof priceNatures] || textValue(value)) : textValue(value) }}</dd></template></dl>
        </section>
        <section class="record-section">
          <h3>核对与更正历史</h3>
          <ElSkeleton v-if="historyLoading" :rows="2" animated />
          <p v-else-if="historyError" class="error" role="alert">{{ historyError }} <ElButton text @click="loadHistory">重试</ElButton></p>
          <p v-else-if="!history.length" class="muted">暂无核对历史。</p>
          <template v-else>
            <article v-for="item in history" :key="item.revision" class="history-row">
              <strong>第 {{ item.revision }} 版 · {{ sampleStates[item.status] }}<template v-if="item.amount"> · {{ item.amount }} 元</template></strong>
              <p>{{ item.reason }}</p><small>{{ item.created_at }} · 操作者 {{ item.created_by }}</small>
            </article>
            <ElPagination v-if="historyTotal > 10" v-model:current-page="historyPage" :total="historyTotal" :page-size="10" layout="prev,next" @current-change="loadHistory" />
          </template>
        </section>
      </template>
    </template>
  </ElDrawer>
</template>

<style scoped>
.group-heading { padding-bottom: 16px; }
.group-heading h2 { margin: 0; font-size: 24px; font-weight: 600; }
.group-heading p, .muted, small { color: var(--color-text-secondary); }
.record-section, .record-picker { padding: 18px 0; border-top: 1px solid var(--color-border); }
h3 { margin: 0 0 14px; font-size: 15px; }
.record-picker button { display: flex; justify-content: space-between; gap: 12px; width: 100%; margin: 4px 0; padding: 10px; border: 1px solid transparent; border-radius: 6px; background: none; cursor: pointer; text-align: left; }
.record-picker button[aria-pressed="true"] { background: var(--color-surface-muted); border-color: var(--color-border); }
.record-picker strong, .quote strong { font-variant-numeric: tabular-nums; }
.quotes { display: grid; gap: 12px; }
.quotes.paired { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.quote { padding: 14px; background: var(--color-canvas); border: 1px solid var(--color-border); border-radius: 8px; }
.quote strong { display: block; font-size: 22px; margin-top: 6px; }
.quote span, .quote p, .quote small, .history-row small { font-size: 12px; }
.quote p, .history-row p { margin: 6px 0; }
dl { display: grid; grid-template-columns: minmax(110px, 150px) minmax(0, 1fr); gap: 8px 14px; margin: 0; font-size: 13px; }
dt { color: var(--color-text-secondary); }
dd { margin: 0; overflow-wrap: anywhere; }
.source-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-top: 12px; }
.history-row { padding: 10px 0; border-bottom: 1px solid var(--color-border); font-size: 13px; }
.error { color: var(--color-danger); }
@media (max-width: 540px) { .quotes.paired { grid-template-columns: 1fr; } }
</style>
