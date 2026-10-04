<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElAlert, ElButton, ElCard, ElInput, ElSelect, ElOption, ElTable, ElTableColumn, ElPagination } from 'element-plus'
import { httpClient, currentUser } from '@/shared/http/client'
import type { ApiResponse, Page } from '@/shared/http/create-client'
import { canMaintain } from './catalogue'
import { priceTypes, scopeLabel, chinaToday, filterPayload } from './prices'
import type { Price } from './prices'
const router = useRouter(), writable = computed(() => canMaintain(currentUser.value))
const filters = reactive({ internalCode: '', alias: '', brandId: '', modelId: '', priceType: '', scope: '', regionId: '', shopId: '', queryDate: chinaToday() })
const rows = ref<Price[]>([]), total = ref(0), page = ref(1), busy = ref(false), error = ref('')
async function load() {
 if (busy.value) return; busy.value = true; error.value = ''
 try { const response = await httpClient.get<ApiResponse<Page<Price>>>('/pricing/prices', { params: filterPayload(filters, page.value) }); rows.value = response.data.data.records; total.value = response.data.data.total }
 catch (cause) { error.value = cause instanceof Error ? cause.message : '查询失败' }
 finally { busy.value = false }
}
function search() { page.value = 1; void load() }
onMounted(() => { void load() })
</script>
<template>
 <main class="price-page"><ElButton @click="router.push('/')">管理端首页</ElButton><h1>价格数据库</h1>
  <nav><ElButton v-for="item in [{ path: 'brands', label: '品牌' },{ path: 'models', label: '车型' },{ path: 'parts', label: '标准配件' },{ path: 'aliases', label: '配件别名' },{ path: 'sources', label: '数据来源' }]" :key="item.path" @click="router.push('/pricing/catalogue/' + item.path)">{{ item.label }}</ElButton>
   <ElButton @click="router.push('/pricing/part-models')">车型适配</ElButton>
   <template v-if="writable"><ElButton type="primary" @click="router.push('/pricing/new')">新增价格</ElButton><ElButton @click="router.push('/pricing/import')">Excel / CSV 导入</ElButton><ElButton @click="router.push('/pricing/batches')">导入批次</ElButton></template>
  </nav>
  <p>以下为参考价格候选；范围并列展示，由管理员或客服明确筛选。正式适用优先级尚待确认。</p>
  <ElAlert v-if="error" :title="error" type="error" :closable="false" role="alert" />
  <ElCard><form @submit.prevent="search">
   <label>内部配件编号（精确）<ElInput v-model="filters.internalCode" maxlength="64" /></label><label>别名（精确）<ElInput v-model="filters.alias" maxlength="120" /></label>
   <label>品牌 ID<ElInput v-model="filters.brandId" /></label><label>车型 ID<ElInput v-model="filters.modelId" /></label>
   <label>价格类型<ElSelect v-model="filters.priceType" clearable><ElOption v-for="(label, key) in priceTypes" :key="key" :value="key" :label="label" /></ElSelect></label>
   <label>适用范围<ElSelect v-model="filters.scope" clearable><ElOption value="NATIONAL" label="全国通用" /><ElOption value="REGION" label="区域" /><ElOption value="SHOP" label="指定网点" /></ElSelect></label>
   <label>区域 ID<ElInput v-model="filters.regionId" /></label><label>网点 ID<ElInput v-model="filters.shopId" /></label><label>适用自然日（可清空查历史）<ElInput v-model="filters.queryDate" type="date" /></label>
   <ElButton native-type="submit" :disabled="busy">查询</ElButton>
  </form><p>区域/网点筛选同时包含全国及匹配的服务区域候选；范围筛选可进一步收窄。不会按层级自动回退。</p></ElCard>
  <p v-if="busy" role="status">查询中…</p><ElTable :data="rows" empty-text="暂无符合条件的价格">
   <ElTableColumn prop="internalCode" label="内部编号" /><ElTableColumn prop="partName" label="标准配件" /><ElTableColumn prop="brandName" label="品牌" /><ElTableColumn prop="modelName" label="车型" />
   <ElTableColumn label="价格类型" width="170"><template #default="scope">{{ priceTypes[(scope.row as Price).priceType] }}</template></ElTableColumn>
   <ElTableColumn label="适用范围" width="190"><template #default="scope">{{ scopeLabel(scope.row as Price) }}</template></ElTableColumn>
   <ElTableColumn label="金额 CNY"><template #default="scope">{{ scope.row.amount }}</template></ElTableColumn><ElTableColumn prop="effectiveFrom" label="开始日" /><ElTableColumn label="截止日"><template #default="scope">{{ scope.row.effectiveTo ?? '持续有效' }}</template></ElTableColumn>
   <ElTableColumn prop="versionNo" label="版本" width="70" /><ElTableColumn prop="sourceName" label="数据来源" />
   <ElTableColumn label="查看" width="170"><template #default="scope"><ElButton @click="router.push('/pricing/records/' + scope.row.recordId + '/history')">详情 / 历史</ElButton><ElButton v-if="writable" @click="router.push('/pricing/records/' + scope.row.recordId + '/new')">新版本</ElButton></template></ElTableColumn>
  </ElTable><ElPagination v-model:current-page="page" :page-size="20" :total="total" :disabled="busy" layout="total,prev,pager,next" @current-change="load" />
 </main>
</template>
<style scoped>.price-page { padding: 28px; } nav, form { display: flex; flex-wrap: wrap; gap: 12px; align-items: end; } label { width: 210px; } .el-card { margin: 20px 0; } .el-select { width: 100%; }</style>
