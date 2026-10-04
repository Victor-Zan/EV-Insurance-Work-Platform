<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElAlert, ElButton, ElCheckbox, ElTable, ElTableColumn, ElPagination } from 'element-plus'
import { httpClient } from '@/shared/http/client'
import type { ApiResponse, Page } from '@/shared/http/create-client'
import { mayConfirm, priceTypes } from './prices'
import type { Preview, PreviewRow, Batch, PriceType } from './prices'
const router = useRouter(), file = ref<File | null>(null), preview = ref<Preview | null>(null), rows = ref<PreviewRow[]>([])
const busy = ref(false), error = ref(''), checked = ref(false), page = ref(1), selectedName = ref('')
function select(event: Event) { file.value = (event.target as HTMLInputElement).files?.[0] ?? null; selectedName.value = file.value?.name ?? ''; preview.value = null; rows.value = []; checked.value = false }
async function run(action: () => Promise<void>) { if (busy.value) return; busy.value = true; error.value = ''
 try { await action() } catch (cause) { error.value = cause instanceof Error ? cause.message : '操作失败' } finally { busy.value = false } }
async function load() {
 if (!preview.value) return
 const response = await httpClient.get<ApiResponse<Page<PreviewRow>>>('/pricing/imports/previews/' + preview.value.id, { params: { page: page.value, size: 20 } })
 rows.value = response.data.data.records
}
async function upload() { await run(async () => {
 if (!file.value) throw new Error('请选择 XLSX 或 UTF-8 CSV')
 if (file.value.size > 10 * 1024 * 1024) throw new Error('文件不得超过 10 MiB')
 const body = new FormData(); body.append('file', file.value)
 const response = await httpClient.post<ApiResponse<Preview>>('/pricing/imports/previews', body, { headers: { 'Content-Type': undefined }, timeout: 120000 })
 preview.value = response.data.data; checked.value = false; page.value = 1; await load()
}) }
async function confirm() { if (!mayConfirm(preview.value, checked.value, busy.value)) return
 await run(async () => {
  if (!preview.value) return
  const response = await httpClient.post<ApiResponse<Batch>>('/pricing/imports/previews/' + preview.value.id + '/confirm', { confirmed: true }, { timeout: 120000 })
  await router.push('/pricing/batches/' + response.data.data.id)
 })
}
async function template() { await run(async () => {
 const response = await httpClient.get<Blob>('/pricing/imports/template', { responseType: 'blob' })
 const url = URL.createObjectURL(response.data), anchor = document.createElement('a'); anchor.href = url; anchor.download = 'price-import-template.csv'; anchor.click(); URL.revokeObjectURL(url)
}) }
</script>
<template>
 <main class="import-page"><ElButton @click="router.push('/pricing')">返回价格库</ElButton><ElButton @click="router.push('/pricing/batches')">导入批次</ElButton><h1>Excel / CSV 导入</h1>
  <ElAlert v-if="error" :title="error" type="error" :closable="false" role="alert" /><p v-if="busy" role="status">解析、校验或提交中…</p>
  <ElButton :disabled="busy" @click="template">下载字段模板</ElButton>
  <p>UTF-8 CSV 或单工作表 XLSX，第一行必须按模板顺序。字段：brandCode、modelCode、internalCode、partName、alias、priceType、scope、regionCode、shopCode、amount、sourceCode、effectiveFrom、effectiveTo。</p>
  <p>品牌、车型、配件、别名、适配关系、来源及组织必须已存在；名称和别名可空。价格与日期使用文本，日期 yyyy-MM-dd，金额大于零且最多两位小数；全国范围不填写区域/网点，区域与网点分别填写已有编码。XLSX 不接受公式。最多 10 MiB / 20,000 行 / 单元格 256 字符。</p>
  <p>预览不写正式价格。确认时重新校验；任意错误整批价格零写入，失败批次和错误仍保留。可追加更晚的新版本，自动截止无期限旧版本；不能覆盖有限历史区间。</p>
  <input aria-label="价格导入文件" type="file" accept=".xlsx,.csv" :disabled="busy" @change="select"><span>{{ selectedName }}</span><ElButton :disabled="busy || !file" @click="upload">解析并预览</ElButton>
  <template v-if="preview">
   <h2>校验预览</h2><p>总行数 {{ preview.totalRows }} · 有效 {{ preview.validRows }} · 错误 {{ preview.errorRows }} · 文件内重复 {{ preview.duplicateRows }} · 预览有效至 {{ preview.expiresAt }}</p>
   <ElAlert v-if="preview.errorRows" title="存在错误，确认后仅记录失败批次，不写入任何价格。" type="warning" :closable="false" />
   <ElTable :data="rows" empty-text="暂无预览行"><ElTableColumn prop="rowNumber" label="行号" width="70" />
    <ElTableColumn label="内部编号"><template #default="scope">{{ scope.row.values.internalCode }}</template></ElTableColumn>
    <ElTableColumn label="车型"><template #default="scope">{{ scope.row.values.brandCode }} / {{ scope.row.values.modelCode }}</template></ElTableColumn>
    <ElTableColumn label="价格类型"><template #default="scope">{{ priceTypes[scope.row.values.priceType as PriceType] ?? scope.row.values.priceType }}</template></ElTableColumn>
    <ElTableColumn label="范围"><template #default="scope">{{ scope.row.values.scope }} {{ scope.row.values.regionCode || scope.row.values.shopCode }}</template></ElTableColumn>
    <ElTableColumn label="金额 CNY"><template #default="scope">{{ scope.row.values.amount }}</template></ElTableColumn>
    <ElTableColumn label="有效期"><template #default="scope">{{ scope.row.values.effectiveFrom }} 至 {{ scope.row.values.effectiveTo || '持续有效' }}</template></ElTableColumn>
    <ElTableColumn prop="action" label="计划操作" /><ElTableColumn label="错误"><template #default="scope"><p v-for="(item, index) in (scope.row as PreviewRow).errors" :key="index">{{ item.field }}：{{ item.reason }}（{{ item.originalValue }}）</p></template></ElTableColumn>
   </ElTable><ElPagination v-model:current-page="page" :page-size="20" :total="preview.totalRows" :disabled="busy" layout="total,prev,pager,next" @current-change="run(load)" />
   <ElCheckbox v-model="checked" :disabled="busy">我已检查预览，确认提交此批次</ElCheckbox><ElButton type="primary" :disabled="!mayConfirm(preview, checked, busy)" @click="confirm">{{ preview.errorRows ? '记录失败批次（价格零写入）' : '确认整批导入' }}</ElButton>
  </template>
 </main>
</template>
<style scoped>.import-page { padding: 28px; } .el-alert { margin: 16px 0; } input { margin: 12px; }</style>
