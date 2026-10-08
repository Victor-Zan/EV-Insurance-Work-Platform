<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Cell, CellGroup, Empty, NavBar, PullRefresh, showToast, Tag } from 'vant'
import { httpClient } from '@/shared/http/client'
import { statusLabels, workOrderApi, type WorkOrder } from './work-orders'
const router=useRouter(),api=workOrderApi(httpClient),rows=ref<WorkOrder[]>([]),loading=ref(false)
async function load(){loading.value=true;try{rows.value=(await api.list()).records}catch(error){showToast(error instanceof Error?error.message:'加载失败')}finally{loading.value=false}}
onMounted(load)
</script>
<template><main class="mobile-shell"><NavBar title="我的保险案件" left-text="首页" left-arrow @click-left="router.push('/')"/><PullRefresh v-model="loading" @refresh="load"><CellGroup v-if="rows.length" inset class="case-list"><Cell v-for="item in rows" :key="item.id" is-link @click="router.push(`/work-orders/${item.id}`)"><template #title><strong>{{ item.businessNo || '案件草稿' }}</strong> <Tag type="primary">{{ statusLabels[item.status] }}</Tag></template><template #label><span>{{ item.vehicleBrand }} {{ item.vehicleModel }}</span><br><span>{{ item.accidentAddress || '未填写详细地址' }}</span></template></Cell></CellGroup><Empty v-else description="暂无可查看的案件"/></PullRefresh></main></template>
<style scoped>.case-list{margin-top:18px}.van-tag{margin-left:8px}.van-cell__label{line-height:1.7}</style>
