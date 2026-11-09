<script setup lang="ts">
import { onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElButton, ElInput, ElSelect, ElOption, ElTable, ElTableColumn, ElPagination, ElSkeleton } from 'element-plus'
import { httpClient } from '@/shared/http/client'
import type { ApiResponse, Page } from '@/shared/http/create-client'
import PricingLookup from './PricingLookup.vue'
import PriceRecordsDrawer from './PriceRecordsDrawer.vue'
import { importDateRange, importPeriods, sampleSources, priceNatures } from './samples'
import type { ImportPeriod, ReferenceGroup } from './samples'

type QueryParams = Record<string, string | number | undefined>
const route = useRoute()
const defaults = { query: '', modelId: null as number | null, sourceKind: '', region: '', priceNature: '' }
const filters = reactive({ ...defaults })
const brandId = ref<number | null>(null)
const importPeriod = ref<ImportPeriod>('ALL')
const page = ref(1), total = ref(0), groups = ref<ReferenceGroup[]>([])
const loading = ref(false), error = ref('')
const applied = ref<QueryParams>({})
const drawer = ref<{ group: ReferenceGroup; filters: QueryParams }>()
let generation = 0

async function load() {
  const request = ++generation
  loading.value = true
  error.value = ''
  try {
    const { data: { data } } = await httpClient.get<ApiResponse<Page<ReferenceGroup>>>('/pricing/samples/groups', {
      params: { ...applied.value, page: page.value, size: 20 },
    })
    if (request !== generation) return
    groups.value = data.records
    total.value = data.total
  } catch (cause) {
    if (request === generation) error.value = cause instanceof Error ? cause.message : '参考价格加载失败'
  } finally {
    if (request === generation) loading.value = false
  }
}

function search() {
  page.value = 1
  applied.value = { ...filters, query: filters.query.trim(), region: filters.region.trim(), modelId: filters.modelId ?? undefined,
    brandId: brandId.value ?? undefined, ...importDateRange(importPeriod.value) }
  void load()
}

function reset() {
  Object.assign(filters, defaults)
  importPeriod.value = 'ALL'
  brandId.value = null
  search()
}

function viewRecords(group: ReferenceGroup) {
  drawer.value = { group, filters: { ...applied.value } }
}

watch(() => route.query.partName, value => {
  filters.query = typeof value === 'string' ? value : ''
  search()
}, { immediate: true })
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <section class="reference-workspace">
    <header class="page-heading"><h1>参考查询</h1><p>按车型和配件查询参考价格及原始依据。</p></header>
    <form class="filter-grid" @submit.prevent="search">
      <label>车辆品牌<PricingLookup v-model="brandId" kind="brands" @selected="filters.modelId = null" /></label>
      <label>适配车型<PricingLookup v-model="filters.modelId" kind="models" :brand-id="brandId" /></label>
      <label>配件名称<ElInput v-model="filters.query" clearable maxlength="120" placeholder="标准名称或原始名称" /></label>
      <label>来源<ElSelect v-model="filters.sourceKind" clearable placeholder="全部来源"><ElOption v-for="(label,key) in sampleSources" :key="key" :value="key" :label="label" /></ElSelect></label>
      <label>地区<ElInput v-model="filters.region" clearable maxlength="120" placeholder="输入地区名称" /></label>
      <label>导入日期<ElSelect v-model="importPeriod" @change="search"><ElOption v-for="(label,key) in importPeriods" :key="key" :value="key" :label="label" /></ElSelect></label>
      <label>价格性质<ElSelect v-model="filters.priceNature" clearable placeholder="全部价格性质"><ElOption v-for="(label,key) in priceNatures" :key="key" :value="key" :label="label" /></ElSelect></label>
      <div class="filter-actions"><ElButton type="primary" native-type="submit" :disabled="loading">查询</ElButton><ElButton @click="reset">重置</ElButton></div>
    </form>
    <section class="result-section" :aria-busy="loading">
      <header class="section-heading"><h2>参考价格</h2><span>{{ total }} 组</span></header>
      <ElSkeleton v-if="loading" :rows="7" animated />
      <p v-else-if="error" class="error" role="alert">{{ error }} <ElButton text @click="load">重试</ElButton></p>
      <template v-else>
        <ElTable :data="groups" empty-text="当前条件下暂无参考价格，可调整筛选后重试。">
          <ElTableColumn label="配件 / 适配车型" min-width="210"><template #default="{ row }"><strong>{{ row.part_name }}</strong><small>{{ row.vehicle_brand }} · {{ row.model_name }}</small></template></ElTableColumn>
          <ElTableColumn label="比较条件" min-width="210"><template #default="{ row }">{{ priceNatures[row.price_nature as keyof typeof priceNatures] }}<small>{{ row.part_brand || '配件品牌未注明' }} · {{ row.region || '地区未注明' }}</small></template></ElTableColumn>
          <ElTableColumn label="价格范围" min-width="180"><template #default="{ row }"><strong class="money">{{ row.minimum === row.maximum ? row.minimum : `${row.minimum} — ${row.maximum}` }}</strong><small>元 / {{ row.unit }}</small></template></ElTableColumn>
          <ElTableColumn prop="records" label="参考记录" width="100" />
          <ElTableColumn prop="latest" label="最近导入日期" width="130" />
          <ElTableColumn label="依据" width="104"><template #default="{ row }"><ElButton text @click="viewRecords(row as ReferenceGroup)">查看记录</ElButton></template></ElTableColumn>
        </ElTable>
        <ElPagination v-model:current-page="page" :total="total" :page-size="20" layout="total,prev,pager,next" @current-change="load" />
      </template>
    </section>
    <PriceRecordsDrawer v-if="drawer" :group="drawer.group" :filters="drawer.filters" @close="drawer = undefined" />
  </section>
</template>

<style scoped>
.reference-workspace { max-width: 1400px; margin: 0 auto; padding: 28px 32px 48px; }
.page-heading { margin-bottom: 28px; }
.page-heading h1 { margin: 0; font-size: 28px; line-height: 1.3; font-weight: 600; }
.page-heading p, .section-heading span { color: var(--color-text-secondary); font-size: 13px; }
.filter-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 18px 20px; padding-bottom: 24px; }
.filter-grid label { display: grid; gap: 8px; min-width: 0; font-size: 12px; }
.el-select { width: 100%; }
.filter-actions { display: flex; align-items: end; gap: 8px; }
.result-section { background: #fff; border: 1px solid var(--color-border); border-radius: 10px; padding: 24px; min-height: 320px; }
.section-heading { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 16px; }
.section-heading h2 { margin: 0; font-size: 17px; }
.reference-workspace :deep(.el-table small) { display: block; color: var(--color-text-secondary); margin-top: 4px; }
.money { font-variant-numeric: tabular-nums; }
.el-pagination { justify-content: flex-end; margin-top: 20px; }
.error { color: var(--color-danger); }
@media (max-width: 1000px) { .filter-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .reference-workspace { padding: 24px 20px; } }
@media (max-width: 600px) { .reference-workspace { padding: 20px 12px; } .filter-grid { grid-template-columns: 1fr; } .result-section { padding: 16px; } }
</style>
