<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElTable, ElTableColumn, ElPagination } from 'element-plus'
import { currentUser, httpClient } from '@/shared/http/client'
import type { ApiResponse, Page } from '@/shared/http/create-client'
import { canMaintain } from './catalogue'
import { positiveId, priceTypes, scopeLabel } from './prices'
import type { Price } from './prices'
const route = useRoute(), router = useRouter(), writable = computed(() => canMaintain(currentUser.value))
const rows = ref<Price[]>([]), total = ref(0), page = ref(1), error = ref(''), busy = ref(false)
async function load() {
 if (busy.value) return; busy.value = true; error.value = ''
 try { const response = await httpClient.get<ApiResponse<Page<Price>>>('/pricing/records/' + positiveId(String(route.params.recordId)) + '/versions', { params: { page: page.value, size: 20 } }); rows.value = response.data.data.records; total.value = response.data.data.total }
 catch (cause) { error.value = cause instanceof Error ? cause.message : '加载失败' } finally { busy.value = false }
}
onMounted(() => { void load() })
</script>
<template>
 <main style="padding: 28px"><ElButton @click="router.push('/pricing')">返回价格库</ElButton><h1>价格详情与历史版本</h1>
  <ElButton v-if="writable" @click="router.push('/pricing/records/' + route.params.recordId + '/new')">创建新版本</ElButton>
  <p v-if="rows[0]">{{ rows[0].internalCode }} · {{ rows[0].partName }} · {{ rows[0].brandName }} / {{ rows[0].modelName }} · {{ priceTypes[rows[0].priceType] }} · {{ scopeLabel(rows[0]) }}</p>
  <ElAlert v-if="error" :title="error" type="error" :closable="false" role="alert" /><p v-if="busy" role="status">加载中…</p>
  <ElTable :data="rows" empty-text="暂无版本"><ElTableColumn prop="id" label="版本 ID" /><ElTableColumn prop="versionNo" label="版本号" /><ElTableColumn prop="amount" label="金额 CNY" />
   <ElTableColumn prop="effectiveFrom" label="开始日" /><ElTableColumn label="截止日"><template #default="scope">{{ scope.row.effectiveTo ?? '持续有效' }}</template></ElTableColumn>
   <ElTableColumn prop="sourceCode" label="来源编码（快照）" /><ElTableColumn prop="sourceName" label="来源名称（快照）" /><ElTableColumn prop="sourceKind" label="来源类型" />
   <ElTableColumn prop="previousVersionId" label="前版本 ID" /><ElTableColumn prop="closedByVersionId" label="截止关联版本 ID" /><ElTableColumn label="批次 ID"><template #default="scope"><ElButton v-if="writable && scope.row.batchId" link @click="router.push('/pricing/batches/' + scope.row.batchId)">{{ scope.row.batchId }}</ElButton><span v-else>{{ scope.row.batchId ?? '手工维护' }}</span></template></ElTableColumn>
   <ElTableColumn prop="createdBy" label="创建人 ID" /><ElTableColumn prop="createdAt" label="创建时间" width="230" />
  </ElTable><ElPagination v-model:current-page="page" :page-size="20" :total="total" :disabled="busy" layout="total,prev,pager,next" @current-change="load" />
 </main>
</template>
