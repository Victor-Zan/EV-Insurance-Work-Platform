<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElCard, ElTable, ElTableColumn, ElPagination } from 'element-plus'
import { httpClient } from '@/shared/http/client'
import type { ApiResponse, Page } from '@/shared/http/create-client'
import { positiveId } from './prices'
import type { Batch, ImportError } from './prices'
const route = useRoute(), router = useRouter(), detail = computed(() => typeof route.params.batchId === 'string')
const batches = ref<Batch[]>([]), batch = ref<Batch | null>(null), errors = ref<ImportError[]>([]), page = ref(1), total = ref(0), busy = ref(false), error = ref('')
let generation = 0
async function load() {
 const current = ++generation; busy.value = true; error.value = ''
 try {
  if (detail.value) {
   const id = positiveId(String(route.params.batchId))
   const response = await httpClient.get<ApiResponse<Batch>>('/pricing/imports/batches/' + id)
   const report = await httpClient.get<ApiResponse<Page<ImportError>>>('/pricing/imports/batches/' + id + '/errors', { params: { page: page.value, size: 20 } })
   if (current !== generation) return
   batch.value = response.data.data; errors.value = report.data.data.records; total.value = report.data.data.total
  } else {
   const response = await httpClient.get<ApiResponse<Page<Batch>>>('/pricing/imports/batches', { params: { page: page.value, size: 20 } })
   if (current !== generation) return
   batches.value = response.data.data.records; total.value = response.data.data.total
  }
 } catch (cause) { if (current === generation) error.value = cause instanceof Error ? cause.message : '加载失败' }
 finally { if (current === generation) busy.value = false }
}
function downloadPage() {
 const url = URL.createObjectURL(new Blob([JSON.stringify(errors.value, null, 2)], { type: 'application/json;charset=utf-8' })), anchor = document.createElement('a')
 anchor.href = url; anchor.download = 'batch-' + batch.value?.id + '-errors-page-' + page.value + '.json'; anchor.click(); URL.revokeObjectURL(url)
}
watch(() => route.params.batchId, () => { page.value = 1; total.value = 0; errors.value = []; batch.value = null; void load() })
onMounted(() => { void load() })
</script>
<template>
 <main style="padding: 28px"><ElButton @click="router.push('/pricing')">返回价格库</ElButton><ElButton @click="router.push('/pricing/import')">导入文件</ElButton><ElButton v-if="detail" @click="router.push('/pricing/batches')">批次列表</ElButton><h1>{{ detail ? '导入批次详情与错误行报告' : '导入批次' }}</h1>
  <ElAlert v-if="error" :title="error" type="error" :closable="false" role="alert" /><p v-if="busy" role="status">加载中…</p>
  <template v-if="detail">
   <ElCard v-if="batch"><p>批次 {{ batch.id }} · {{ batch.status === 'SUCCESS' ? '全成功' : '全批失败，价格零写入' }}</p><p>文件 {{ batch.fileName }} · 来源 {{ batch.source }} · 操作者 ID {{ batch.actorId }}</p><p>总数 {{ batch.totalRows }} · 成功 {{ batch.successCount }} · 失败 {{ batch.failureCount }}</p><p>创建 {{ batch.createdAt }} · 完成 {{ batch.completedAt }}</p></ElCard>
   <ElButton :disabled="busy || !errors.length" @click="downloadPage">下载当前页错误报告 JSON</ElButton>
   <ElTable :data="errors" empty-text="没有错误行"><ElTableColumn prop="rowNumber" label="行号" /><ElTableColumn prop="field" label="字段" /><ElTableColumn prop="reason" label="错误原因" /><ElTableColumn prop="originalValue" label="原始值 / 上下文" /></ElTable>
  </template>
  <ElTable v-else :data="batches" empty-text="暂无批次"><ElTableColumn prop="id" label="批次 ID" /><ElTableColumn prop="status" label="状态" /><ElTableColumn prop="fileName" label="文件" /><ElTableColumn prop="source" label="来源" /><ElTableColumn prop="totalRows" label="总行数" /><ElTableColumn prop="successCount" label="成功" /><ElTableColumn prop="failureCount" label="失败" /><ElTableColumn prop="actorId" label="操作者 ID" /><ElTableColumn prop="createdAt" label="时间" width="230" /><ElTableColumn label="查看"><template #default="scope"><ElButton @click="router.push('/pricing/batches/' + scope.row.id)">详情 / 错误行</ElButton></template></ElTableColumn></ElTable>
  <ElPagination v-model:current-page="page" :page-size="20" :total="total" :disabled="busy" layout="total,prev,pager,next" @current-change="load" />
 </main>
</template>
