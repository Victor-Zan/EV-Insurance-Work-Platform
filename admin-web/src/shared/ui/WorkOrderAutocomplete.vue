<script setup lang="ts">
import { ref } from 'vue'
import { ElAutocomplete } from 'element-plus'
import { httpClient } from '@/shared/http/client'
import { statusLabels, workOrderApi, type WorkOrder } from '@/features/workorders/work-orders'

const props = defineProps<{ placeholder: string; excludeCompleted?: boolean; formalOnly?: boolean }>()
const emit = defineEmits<{ select: [order: WorkOrder] }>()
type Suggestion = { value: string; order: WorkOrder }
const input = ref('')
const error = ref('')
const autocomplete = ref<{ suggestions: Suggestion[]; loading: boolean }>()
const api = workOrderApi(httpClient)
let request = 0

function invalidate() {
  request++
  error.value = ''
  if (autocomplete.value) {
    autocomplete.value.suggestions = []
    autocomplete.value.loading = false
  }
}

async function suggest(value: string, show: (items: Suggestion[]) => void) {
  const keyword = value.trim()
  const version = ++request
  if (keyword.length < 2) {
    show([])
    return
  }
  try {
    const result = await api.list(1, '', keyword, props.excludeCompleted ?? false)
    if (version !== request || keyword !== input.value.trim()) return
    const options = result.records.filter(order => !props.formalOnly || order.status !== 'DRAFT')
      .map(order => ({ value: order.businessNo || order.claimNo || '案件草稿', order }))
    show(options)
    if (!options.length) error.value = '没有匹配的工单'
  } catch {
    if (version !== request || keyword !== input.value.trim()) return
    show([])
    error.value = '查询失败，请重新输入关键词'
  }
}

function select(item: Record<string, unknown>) {
  emit('select', (item as Suggestion).order)
  input.value = ''
}
</script>

<template>
  <div class="work-order-autocomplete">
    <!-- @vue-generic {Suggestion} -->
    <ElAutocomplete ref="autocomplete" v-model="input" :placeholder="placeholder" :aria-label="placeholder"
      :fetch-suggestions="suggest" :trigger-on-focus="false" :debounce="250" clearable
      @input="invalidate" @clear="invalidate" @select="select">
      <template #default="{ item }">
        {{ item.value }} · {{ item.order.claimNo || '无报案号' }} · {{ statusLabels[item.order.status as WorkOrder['status']] }}
        <span v-if="formalOnly && (item.order.vehicleBrand || item.order.vehicleModel)"> · {{ item.order.vehicleBrand }} {{ item.order.vehicleModel }}</span>
      </template>
    </ElAutocomplete>
    <span v-if="error" class="lookup-status" role="status" :title="error">{{ error }}</span>
  </div>
</template>

<style scoped>
.work-order-autocomplete { position: relative; width: 100%; min-width: 0; }
.el-autocomplete { width: 100%; }
.lookup-status { position: absolute; left: 0; top: 100%; font-size: 12px; color: var(--color-text-secondary); }
</style>
