<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElAlert, ElButton, ElCard, ElInput, ElTable, ElTableColumn, ElPagination, ElMessageBox } from 'element-plus'
import { currentUser, httpClient } from '@/shared/http/client'
import type { ApiResponse, Page } from '@/shared/http/create-client'
import { canMaintain } from './catalogue'
interface Relation { partId: number; modelId: number; internalCode: string; partName: string; modelName: string; brandId: number }
const router = useRouter(), writable = computed(() => canMaintain(currentUser.value))
const rows = ref<Relation[]>([]), total = ref(0), page = ref(1), busy = ref(false), error = ref('')
const partId = ref(''), modelId = ref(''), newPart = ref(''), newModel = ref('')
function id(raw: string): number { const v = Number(raw); if (!Number.isSafeInteger(v) || v < 1) throw new Error('ID 必须为正整数'); return v }
async function run(action: () => Promise<void>) { if (busy.value) return; busy.value = true; error.value = ''
  try { await action() } catch (cause) { error.value = cause instanceof Error ? cause.message : '操作失败' } finally { busy.value = false } }
async function load() {
  const response = await httpClient.get<ApiResponse<Page<Relation>>>('/pricing/part-models', { params: { page: page.value, size: 20, partId: partId.value ? id(partId.value) : undefined, modelId: modelId.value ? id(modelId.value) : undefined } })
  rows.value = response.data.data.records; total.value = response.data.data.total
}
async function search() { page.value = 1; await run(load) }
async function add() { await run(async () => { await httpClient.post('/pricing/part-models', { partId: id(newPart.value), modelId: id(newModel.value) }); newPart.value = ''; newModel.value = ''; await load() }) }
async function remove(row: Relation) {
  try { await ElMessageBox.confirm('有价格记录引用的适配关系无法删除。确认删除？', '删除确认') } catch { return }
  await run(async () => { await httpClient.delete('/pricing/part-models/' + row.partId + '/' + row.modelId); await load() })
}
onMounted(() => { void run(load) })
</script>
<template>
  <main style="padding: 28px"><ElButton @click="router.push('/pricing/catalogue/parts')">标准配件</ElButton><h1>配件与车型适配关系</h1>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" role="alert" /><p v-if="busy" role="status">处理中…</p>
    <ElCard><form @submit.prevent="search"><label>配件 ID<ElInput v-model="partId" /></label><label>车型 ID<ElInput v-model="modelId" /></label><ElButton native-type="submit" :disabled="busy">查询</ElButton></form></ElCard>
    <ElTable :data="rows" empty-text="暂无适配关系"><ElTableColumn prop="partId" label="配件 ID" /><ElTableColumn prop="internalCode" label="内部编号" /><ElTableColumn prop="partName" label="标准配件" /><ElTableColumn prop="modelId" label="车型 ID" /><ElTableColumn prop="modelName" label="车型" /><ElTableColumn prop="brandId" label="品牌 ID" />
      <ElTableColumn v-if="writable" label="操作"><template #default="scope"><ElButton :disabled="busy" @click="remove(scope.row as Relation)">删除</ElButton></template></ElTableColumn>
    </ElTable><ElPagination v-model:current-page="page" :page-size="20" :total="total" layout="total,prev,pager,next" :disabled="busy" @current-change="run(load)" />
    <ElCard v-if="writable"><h2>添加适配关系</h2><form @submit.prevent="add"><label>配件 ID<ElInput v-model="newPart" /></label><label>车型 ID<ElInput v-model="newModel" /></label><ElButton native-type="submit" :disabled="busy">添加</ElButton></form></ElCard>
  </main>
</template>
<style scoped>form { display: flex; flex-wrap: wrap; gap: 12px; align-items: end; } .el-card { margin: 20px 0; }</style>
