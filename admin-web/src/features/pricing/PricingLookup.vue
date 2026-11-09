<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { ElSelect, ElOption, ElButton } from 'element-plus'
import { httpClient } from '@/shared/http/client'
import type { ApiResponse, Page } from '@/shared/http/create-client'
import type { CatalogueItem } from './catalogue'

const props = defineProps<{ modelValue?: number | null; kind: 'brands' | 'models' | 'parts'; brandId?: number | null; label?: string; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: number | null]; selected: [value: CatalogueItem | undefined] }>()
const rows = ref<CatalogueItem[]>([]), query = ref(''), page = ref(1), total = ref(0), loading = ref(false), error = ref('')
let generation = 0
async function load(more = false) {
  const request = ++generation
  loading.value = true; error.value = ''
  const nextPage = more ? page.value + 1 : 1
  try {
    const result = (await httpClient.get<ApiResponse<Page<CatalogueItem>>>(`/pricing/${props.kind}`, {
      params: { name: query.value || undefined, brandId: props.brandId || undefined, page: nextPage, size: 20 },
    })).data.data
    if (request !== generation) return
    rows.value = more ? [...rows.value, ...result.records] : result.records
    page.value = nextPage; total.value = result.total
  } catch (cause) { if (request === generation) error.value = cause instanceof Error ? cause.message : '资料加载失败' }
  finally { if (request === generation) loading.value = false }
}
function search(value: string) { query.value = value; void load() }
function choose(value: number | undefined) {
  emit('update:modelValue', value || null)
  emit('selected', rows.value.find(row => row.id === value))
}
watch(() => [props.kind, props.brandId], () => { rows.value = []; query.value = ''; void load() }, { immediate: true })
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <div class="pricing-lookup">
    <ElSelect :model-value="modelValue ?? undefined" :disabled="disabled" filterable remote clearable
      :remote-method="search" :loading="loading" placeholder="输入名称查找" @update:model-value="choose">
      <ElOption v-if="modelValue && !rows.some(row => row.id === modelValue)" :value="modelValue" :label="label || '已选资料'" />
      <ElOption v-for="row in rows" :key="row.id" :value="row.id" :label="row.name + (row.internalCode ? ` · ${row.internalCode}` : '')" />
      <template #footer><ElButton v-if="rows.length < total" text :disabled="loading" @click="load(true)">加载更多（{{ rows.length }} / {{ total }}）</ElButton></template>
    </ElSelect>
    <small v-if="error" role="alert">{{ error }} <button type="button" @click="load()">重试</button></small>
  </div>
</template>

<style scoped>
.pricing-lookup,.el-select{width:100%;min-width:0}small{display:block;color:#a04c47;margin-top:6px;font-size:12px}button{font:inherit}
</style>
