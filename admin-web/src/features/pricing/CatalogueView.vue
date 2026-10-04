<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElCard, ElInput, ElCheckbox, ElTable, ElTableColumn, ElPagination, ElSelect, ElOption, ElMessageBox } from 'element-plus'
import { httpClient, currentUser } from '@/shared/http/client'
import type { ApiResponse, Page } from '@/shared/http/create-client'
import { catalogueDefinitions, catalogueKind, cataloguePayload, canMaintain } from './catalogue'
import type { CatalogueItem } from './catalogue'
const route = useRoute(), router = useRouter()
const kind = computed(() => catalogueKind(route.params.kind))
const definition = computed(() => catalogueDefinitions[kind.value])
const writable = computed(() => canMaintain(currentUser.value))
const rows = ref<CatalogueItem[]>([]), total = ref(0), page = ref(1), busy = ref(false), error = ref(''), notice = ref('')
const form = reactive<Record<string, string>>({}), enabled = ref(true), editing = ref<number | null>(null)
const filters = reactive({ name: '', internalCode: '', brandId: '', partId: '' })
let reloadQueued = false
function clearForm() { for (const key of Object.keys(form)) delete form[key]; editing.value = null; enabled.value = true }
async function run(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try { await action() } catch (cause) { error.value = cause instanceof Error ? cause.message : '操作失败' }
  finally { busy.value = false; if (reloadQueued) { reloadQueued = false; void run(load) } }
}
async function load() {
  const requestedKind = kind.value
  const params: Record<string, string | number> = { page: page.value, size: 20 }
  if (filters.name && kind.value !== 'sources') params.name = filters.name
  if (kind.value === 'parts' && filters.internalCode) params.internalCode = filters.internalCode
  for (const field of ['brandId', 'partId'] as const) {
    if ((kind.value === 'models' && field === 'brandId' || kind.value === 'aliases' && field === 'partId') && filters[field]) {
      const id = Number(filters[field]); if (!Number.isSafeInteger(id) || id < 1) throw new Error('筛选 ID 必须为正整数')
      params[field] = id
    }
  }
  const response = await httpClient.get<ApiResponse<Page<CatalogueItem>>>('/pricing/' + requestedKind, { params })
  if (kind.value !== requestedKind) return
  rows.value = response.data.data.records; total.value = response.data.data.total
}
async function search() { page.value = 1; await run(load) }
function edit(row: CatalogueItem) {
  clearForm(); editing.value = row.id; enabled.value = row.enabled ?? true
  for (const field of definition.value.fields) form[field.key] = String(row[field.key] ?? '')
}
async function save() {
  await run(async () => {
    const payload = cataloguePayload(kind.value, form, enabled.value)
    if (editing.value === null) await httpClient.post('/pricing/' + kind.value, payload)
    else await httpClient.put('/pricing/' + kind.value + '/' + editing.value, payload)
    clearForm(); notice.value = '已保存'; await load()
  })
}
async function remove(row: CatalogueItem) {
  try { await ElMessageBox.confirm('仅能删除未被引用的资料。确认删除 ID ' + row.id + '？', '删除确认', { type: 'warning' }) }
  catch { return }
  await run(async () => { await httpClient.delete('/pricing/' + kind.value + '/' + row.id); await load() })
}
watch(kind, () => { clearForm(); page.value = 1; rows.value = []; total.value = 0; Object.assign(filters, { name: '', internalCode: '', brandId: '', partId: '' }); if (busy.value) reloadQueued = true; else void run(load) }, { immediate: true })
</script>
<template>
  <main class="catalogue"><ElButton @click="router.push('/')">管理端首页</ElButton><h1>价格基础资料 · {{ definition.title }}</h1>
    <nav><ElButton v-for="(item, key) in catalogueDefinitions" :key="key" :disabled="busy" @click="router.push('/pricing/catalogue/' + key)">{{ item.title }}</ElButton><ElButton :disabled="busy" @click="router.push('/pricing/part-models')">配件车型适配</ElButton></nav>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" role="alert" />
    <ElAlert v-if="notice" :title="notice" type="success" :closable="false" />
    <ElCard><form @submit.prevent="search">
      <label v-if="kind !== 'sources'">名称 / 别名前缀<ElInput v-model="filters.name" maxlength="120" /></label>
      <label v-if="kind === 'parts'">内部编号（精确）<ElInput v-model="filters.internalCode" maxlength="64" /></label>
      <label v-if="kind === 'models'">品牌 ID<ElInput v-model="filters.brandId" /></label>
      <label v-if="kind === 'aliases'">配件 ID<ElInput v-model="filters.partId" /></label>
      <ElButton native-type="submit" :disabled="busy">查询</ElButton>
    </form></ElCard>
    <p v-if="busy" role="status">加载或保存中…</p>
    <ElTable :data="rows" empty-text="暂无资料">
      <ElTableColumn prop="id" label="ID" width="90" />
      <ElTableColumn v-for="field in definition.fields" :key="field.key" :prop="field.key" :label="field.label" />
      <ElTableColumn v-if="definition.enabled" label="状态"><template #default="scope">{{ scope.row.enabled ? '启用' : '停用' }}</template></ElTableColumn>
      <ElTableColumn v-if="writable" label="操作"><template #default="scope"><ElButton :disabled="busy" @click="edit(scope.row as CatalogueItem)">编辑</ElButton><ElButton :disabled="busy" @click="remove(scope.row as CatalogueItem)">删除</ElButton></template></ElTableColumn>
    </ElTable>
    <ElPagination v-model:current-page="page" :page-size="20" :total="total" layout="total,prev,pager,next" :disabled="busy" @current-change="run(load)" />
    <ElCard v-if="writable"><h2>{{ editing === null ? '新增' : '编辑 ID ' + editing }}</h2>
      <form @submit.prevent="save"><label v-for="field in definition.fields" :key="field.key">{{ field.label }}
        <ElSelect v-if="field.key === 'kind'" v-model="form[field.key]"><ElOption v-for="source in ['MANUAL','CSV','EXCEL','HISTORICAL_CASE']" :key="source" :label="source" :value="source" /></ElSelect>
        <ElInput v-else v-model="form[field.key]" :maxlength="field.key === 'name' ? 120 : 64" />
      </label><ElCheckbox v-if="definition.enabled" v-model="enabled">启用</ElCheckbox>
        <ElButton native-type="submit" :disabled="busy">保存</ElButton><ElButton :disabled="busy" @click="clearForm">取消编辑</ElButton>
      </form>
    </ElCard>
    <p v-else>客服只读，可查询基础资料；维护操作由管理员执行。</p>
  </main>
</template>
<style scoped>.catalogue { padding: 28px; } nav, form { display: flex; gap: 12px; flex-wrap: wrap; align-items: end; } label { max-width: 240px; } .el-card { margin: 20px 0; } .el-alert { margin-top: 16px; }</style>
