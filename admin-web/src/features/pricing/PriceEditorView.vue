<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElCard, ElInput, ElSelect, ElOption, ElMessageBox } from 'element-plus'
import { httpClient } from '@/shared/http/client'
import type { ApiResponse, Page } from '@/shared/http/create-client'
import { priceTypes, scopeLabel, chinaToday, positiveId, versionPayload } from './prices'
import type { Price, PriceType, Scope } from './prices'
import type { CatalogueItem } from './catalogue'
const route = useRoute(), router = useRouter(), version = computed(() => typeof route.params.recordId === 'string')
const latest = ref<Price | null>(null), sources = ref<CatalogueItem[]>([]), busy = ref(false), error = ref('')
const form = reactive({ partId: '', modelId: '', priceType: 'REPAIR_SHOP_RAW_REFERENCE' as PriceType, scope: 'NATIONAL' as Scope, regionId: '', shopId: '', amount: '', sourceId: '', effectiveFrom: chinaToday(), effectiveTo: '' })
async function init() {
 busy.value = true
 try {
  const response = await httpClient.get<ApiResponse<Page<CatalogueItem>>>('/pricing/sources', { params: { page: 1, size: 100 } })
  sources.value = response.data.data.records.filter(item => item.enabled)
  form.sourceId = String(sources.value.find(item => item.kind === 'MANUAL')?.id ?? '')
  if (version.value) {
   const history = await httpClient.get<ApiResponse<Page<Price>>>('/pricing/records/' + positiveId(String(route.params.recordId)) + '/versions', { params: { page: 1, size: 1 } })
   latest.value = history.data.data.records[0] ?? null
   if (!latest.value) throw new Error('没有可追加的价格版本')
  }
 } catch (cause) { error.value = cause instanceof Error ? cause.message : '加载失败' } finally { busy.value = false }
}
async function save() {
 if (busy.value) return
 error.value = ''
 try {
  const payload = versionPayload(form)
  const create = { ...payload, partId: version.value ? 0 : positiveId(form.partId), modelId: version.value ? 0 : positiveId(form.modelId), priceType: form.priceType, scope: form.scope, regionId: form.scope === 'REGION' ? positiveId(form.regionId) : null, shopId: form.scope === 'SHOP' ? positiveId(form.shopId) : null }
  if (version.value && latest.value && payload.effectiveFrom <= latest.value.effectiveFrom) throw new Error('新开始日必须晚于当前版本开始日')
  try { await ElMessageBox.confirm(version.value && !latest.value?.effectiveTo ? '确认创建新版本？系统会在同一事务中将无期限旧版本截止至新开始日前一天，并记录审计。' : '确认保存此参考价格版本？', '保存确认') } catch { return }
  busy.value = true
  const response = await httpClient.post<ApiResponse<Price>>(version.value ? '/pricing/records/' + route.params.recordId + '/versions' : '/pricing/prices', version.value ? payload : create)
  await router.push('/pricing/records/' + response.data.data.recordId + '/history')
 } catch (cause) { error.value = cause instanceof Error ? cause.message : '保存失败' } finally { busy.value = false }
}
onMounted(() => { void init() })
</script>
<template>
 <main class="editor"><ElButton @click="router.push('/pricing')">返回价格库</ElButton><h1>{{ version ? '创建新价格版本' : '新增参考价格' }}</h1>
  <ElAlert v-if="error" :title="error" type="error" :closable="false" role="alert" /><p v-if="busy" role="status">处理中…</p>
  <ElCard v-if="latest"><p>{{ latest.internalCode }} · {{ latest.partName }} · {{ latest.brandName }} / {{ latest.modelName }}</p><p>{{ priceTypes[latest.priceType] }} · {{ scopeLabel(latest) }}</p><p>当前版本 {{ latest.versionNo }}：{{ latest.amount }} CNY，{{ latest.effectiveFrom }} 至 {{ latest.effectiveTo ?? '持续有效' }}</p></ElCard>
  <p>金额必须大于零；中国自然日首尾包含。有限旧区间不得覆盖；无期限旧区间由新版本自动截止。金额、来源和创建信息保留。</p>
  <ElCard><form @submit.prevent="save">
   <template v-if="!version"><label>标准配件 ID<ElInput v-model="form.partId" /></label><label>车型 ID<ElInput v-model="form.modelId" /></label>
    <label>价格类型<ElSelect v-model="form.priceType"><ElOption v-for="(label, key) in priceTypes" :key="key" :value="key" :label="label" /></ElSelect></label>
    <label>适用范围<ElSelect v-model="form.scope"><ElOption value="NATIONAL" label="全国通用" /><ElOption value="REGION" label="区域" /><ElOption value="SHOP" label="指定网点" /></ElSelect></label>
    <label v-if="form.scope === 'REGION'">区域 ID<ElInput v-model="form.regionId" /></label><label v-if="form.scope === 'SHOP'">网点 ID<ElInput v-model="form.shopId" /></label>
   </template>
   <label>金额 CNY<ElInput v-model="form.amount" inputmode="decimal" maxlength="32" /></label>
   <label>数据来源<ElSelect v-model="form.sourceId"><ElOption v-for="item in sources" :key="item.id" :value="String(item.id)" :label="item.name + ' (' + item.code + ')'" /></ElSelect></label>
   <label>生效开始日<ElInput v-model="form.effectiveFrom" type="date" /></label><label>截止日（可空）<ElInput v-model="form.effectiveTo" type="date" /></label>
   <ElButton native-type="submit" type="primary" :disabled="busy || version && !latest">保存新版本</ElButton>
  </form></ElCard>
  <p>配件、车型需先配置适配关系，可在价格基础资料页面查看 ID。来源选择列出前 100 条启用资料。</p>
 </main>
</template>
<style scoped>.editor { padding: 28px; } form { display: flex; flex-wrap: wrap; gap: 16px; align-items: end; } label { width: 240px; } .el-select { width: 100%; } .el-card { margin-top: 20px; }</style>
