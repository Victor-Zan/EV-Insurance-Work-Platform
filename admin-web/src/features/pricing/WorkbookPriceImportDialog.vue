<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElButton, ElDialog, ElInput, ElOption, ElPagination, ElSelect, ElTable, ElTableColumn } from 'element-plus'
import FileDropZone from '@/shared/upload/FileDropZone.vue'
import { httpClient } from '@/shared/http/client'
import type { ApiResponse, Page } from '@/shared/http/create-client'

interface PriceCell { original: string; amount: string | null; status: 'READY' | 'DUPLICATE' | 'INVALID' | 'SKIPPED'; note: string | null }
interface PreviewRow {
  rowNumber: number; brand: string; model: string; part: string; unit: string; region: string; note: string
  supplier: PriceCell; cp: PriceCell
}
interface ImportCounts { duplicates: number; invalid: number; skippedRows: number }
interface Preview extends ImportCounts { fileName: string; fingerprint: string; ready: number; rows: Page<PreviewRow> }
interface ImportResult extends ImportCounts { imported: number }

const emit = defineEmits<{ close: []; imported: [] }>()
const open = ref(true), busy = ref(false), error = ref('')
const file = ref<File>(), preview = ref<Preview>(), result = ref<ImportResult>()
const excludeRows = ref(''), correctionRow = ref(''), correctionAmount = ref(''), correctionNature = ref('SUPPLIER_QUOTED')
const columns = [{ key: 'supplier', label: '配件商报价' }, { key: 'cp', label: 'CP 报价' }] as const
const statuses = { READY: '可导入', DUPLICATE: '重复', INVALID: '待核对', SKIPPED: '跳过' } as const
const endpoint = '/pricing/samples/workbook'
const upload = { headers: { 'Content-Type': undefined }, timeout: 120000 }

watch([file, excludeRows, correctionRow, correctionAmount, correctionNature], () => { preview.value = undefined; error.value = '' })

function choose(event: Event) {
  const input = event.target as HTMLInputElement
  file.value = input.files?.[0]
}

function payload(selected: File) {
  const form = new FormData()
  form.append('file', selected)
  if (excludeRows.value.trim()) form.append('excludeRows', excludeRows.value.trim())
  if (correctionRow.value.trim() || correctionAmount.value.trim()) {
    form.append('correctionRow', correctionRow.value.trim())
    form.append('correctionAmount', correctionAmount.value.trim())
    form.append('correctionNature', correctionNature.value)
  }
  return form
}

async function loadPreview(page = 1) {
  if (!file.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const form = payload(file.value)
    form.append('page', String(page))
    const response = await httpClient.post<ApiResponse<Preview>>(`${endpoint}/preview`, form, upload)
    preview.value = response.data.data
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '预览失败'
  } finally { busy.value = false }
}

async function confirm() {
  if (!file.value || !preview.value?.ready || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const form = payload(file.value)
    form.append('fingerprint', preview.value.fingerprint)
    const response = await httpClient.post<ApiResponse<ImportResult>>(`${endpoint}/confirm`, form, upload)
    result.value = response.data.data
    emit('imported')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '导入失败'
  } finally { busy.value = false }
}
</script>

<template>
  <ElDialog v-model="open" title="价格批量导入" width="min(1040px, calc(100vw - 32px))" append-to-body
    :show-close="!busy" :close-on-press-escape="!busy" :close-on-click-modal="!busy" @closed="emit('close')">
    <div v-if="result" class="import-result" role="status">
      <strong>已导入 {{ result.imported }} 条报价</strong>
      <p>重复 {{ result.duplicates }} 条 · 待核对 {{ result.invalid }} 条 · 跳过 {{ result.skippedRows }} 行</p>
    </div>
    <template v-else>
      <p class="import-intro">读取「导入文档」中的配件明细。地区为空时记为全国。</p>
      <FileDropZone hint="选择 1 个 XLSX 文件，最大 10 MiB，支持 2000 条明细。" :max-bytes="10485760" :busy="busy">
        <input type="file" accept=".xlsx" aria-label="选择价格导入 Excel" :disabled="busy" @change="choose">
      </FileDropZone>
      <details class="adjustments">
        <summary>排除行与更正报价</summary>
        <div class="adjustment-fields">
          <label>排除 Excel 行号<ElInput v-model="excludeRows" placeholder="例如 62-68，可留空" maxlength="4000" :disabled="busy" /></label>
          <label>更正行号<ElInput v-model="correctionRow" placeholder="可留空" maxlength="5" inputmode="numeric" :disabled="busy" /></label>
          <label>报价类型<ElSelect v-model="correctionNature" :disabled="busy">
            <ElOption label="配件商报价" value="SUPPLIER_QUOTED" /><ElOption label="CP 报价" value="CP_QUOTED" />
          </ElSelect></label>
          <label>更正金额（元）<ElInput v-model="correctionAmount" placeholder="可留空" maxlength="19" inputmode="decimal" :disabled="busy" /></label>
        </div>
      </details>
      <section v-if="preview" class="import-preview" aria-label="价格导入预览">
        <header>
          <strong>{{ preview.fileName }}</strong>
          <p>可导入 {{ preview.ready }} 条 · 重复 {{ preview.duplicates }} 条 · 待核对 {{ preview.invalid }} 条 · 跳过 {{ preview.skippedRows }} 行</p>
        </header>
        <ElTable :data="preview.rows.records" max-height="350">
          <ElTableColumn prop="rowNumber" label="Excel 行" width="85" />
          <ElTableColumn label="配件与单位" min-width="155"><template #default="{ row }">
            {{ row.part || '未注明' }} · {{ row.unit || '无单位' }}<small v-if="row.note">{{ row.note }}</small>
          </template></ElTableColumn>
          <ElTableColumn label="车型 / 地区" min-width="150"><template #default="{ row }">
            {{ row.brand }} · {{ row.model }}<small>{{ row.region }}</small>
          </template></ElTableColumn>
          <ElTableColumn v-for="column in columns" :key="column.key" :label="column.label" min-width="160">
            <template #default="{ row }">
              <span class="quote-number">{{ row[column.key].amount ?? (row[column.key].original || '—') }}</span>
              <small v-if="row[column.key].status !== 'SKIPPED'">{{ statuses[(row as PreviewRow)[column.key].status] }}<template v-if="row[column.key].note"> · {{ row[column.key].note }}</template></small>
            </template>
          </ElTableColumn>
        </ElTable>
        <ElPagination v-if="preview.rows.total > preview.rows.size" :current-page="preview.rows.page" :total="preview.rows.total"
          :page-size="preview.rows.size" :disabled="busy" layout="prev,pager,next" @current-change="loadPreview" />
      </section>
      <p v-if="error" class="import-error" role="alert">{{ error }}</p>
    </template>
    <template #footer>
      <ElButton v-if="result" type="primary" @click="open = false">完成</ElButton>
      <template v-else>
        <ElButton :disabled="busy" @click="open = false">取消</ElButton>
        <ElButton :type="preview ? 'default' : 'primary'" :disabled="!file || busy" @click="loadPreview()">{{ preview ? '重新预览' : '上传并预览' }}</ElButton>
        <ElButton v-if="preview" type="primary" :disabled="!preview.ready || busy" @click="confirm">确认导入 {{ preview.ready }} 条</ElButton>
      </template>
    </template>
  </ElDialog>
</template>

<style scoped>
.import-intro { margin: 0 0 16px; }
.import-intro, .adjustments, .import-preview small, .import-preview p, .import-result p { color: var(--color-text-secondary); }
.adjustments { padding: 14px 0; border-bottom: 1px solid var(--color-border); }
.adjustments summary { cursor: pointer; }
.adjustment-fields { display: grid; grid-template-columns: 1.4fr repeat(3, minmax(0, 1fr)); gap: 12px; margin-top: 12px; }
.adjustment-fields label { display: grid; gap: 6px; }
.import-preview { margin-top: 20px; }
.import-preview p { margin: 6px 0 12px; }
.import-preview small { display: block; margin-top: 4px; }
.import-preview :deep(.el-pagination) { justify-content: flex-end; margin-top: 12px; }
.quote-number, .import-result strong { font-variant-numeric: tabular-nums; }
.import-result strong { font-size: 22px; }
.import-error { color: var(--color-danger); }
@media (max-width: 700px) { .adjustment-fields { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
