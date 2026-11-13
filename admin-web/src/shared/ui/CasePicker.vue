<script setup lang="ts">
import { ref } from 'vue'
import WorkOrderAutocomplete from './WorkOrderAutocomplete.vue'
import type { WorkOrder } from '@/features/workorders/work-orders'

defineProps<{ modelValue: string }>()
const emit = defineEmits<{'update:modelValue': [id: string]}>()
const selectedLabel = ref('')

function select(order: WorkOrder) {
  selectedLabel.value = [order.businessNo, order.claimNo].filter(Boolean).join(' · ')
  emit('update:modelValue', order.id)
}
</script>

<template>
  <fieldset class="case-picker">
    <legend>人工核对后选择案件</legend>
    <WorkOrderAutocomplete placeholder="输入案件号或报案号（至少两个字符）" formal-only @select="select" />
    <p v-if="modelValue">已选择：{{ selectedLabel || '已选案件' }} <button type="button" @click="emit('update:modelValue', '')">清除案件选择</button></p>
  </fieldset>
</template>

<style scoped>
.case-picker { min-width: 0; border: 1px solid #d0d5dd; margin: 12px 0; padding: 12px; }
</style>
