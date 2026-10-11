<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Button, Empty, List, NavBar, PullRefresh, Tag } from 'vant'
import { httpClient } from '@/shared/http/client'
import { statusLabels, workOrderApi, type WorkOrder } from '@/features/workorders/work-orders'
const router=useRouter(),api=workOrderApi(httpClient),rows=ref<WorkOrder[]>([]),page=ref(1),total=ref(0),processed=ref(0),loading=ref(false),refreshing=ref(false),error=ref('')
const allowed=['REPAIRING','WAITING_OWNER_CONFIRMATION','COMPLETED','WAITING_INSURER_PAYMENT','WAITING_SHOP_SETTLEMENT']
async function load(reset=false){if(reset){page.value=1;rows.value=[];processed.value=0;refreshing.value=true}else loading.value=true;error.value='';try{const result=await api.list(page.value,20);rows.value.push(...result.records.filter(row=>allowed.includes(row.status)));processed.value+=result.records.length;total.value=result.total;if(processed.value<result.total)page.value++}catch(e){error.value=e instanceof Error?e.message:'案件加载失败'}finally{loading.value=false;refreshing.value=false}}
onMounted(()=>load(true))
</script>
<template><div class="mobile-page"><NavBar title="投诉入口"/><header class="page-lead"><p class="eyebrow">OWNER / COMPLAINT</p><h1>选择相关案件</h1><p>投诉必须关联具体案件；提交后原文不可修改，也不会自动改变维修状态或评分。</p></header><p v-if="error" class="inline-error" role="alert">{{ error }} <Button size="small" @click="load(true)">重试</Button></p><PullRefresh v-model="refreshing" @refresh="load(true)"><List v-model:loading="loading" :finished="processed>=total" :immediate-check="false" finished-text="已显示全部可投诉案件" @load="load(false)"><button v-for="row in rows" :key="row.id" class="case-row" @click="router.push(`/work-orders/${row.id}?section=complaint`)"><span><strong>{{ row.businessNo||'案件' }}</strong><small>{{ row.vehicleBrand }} {{ row.vehicleModel }}</small></span><Tag plain type="primary">{{ statusLabels[row.status] }}</Tag></button><Empty v-if="!rows.length&&!loading&&!error&&processed>=total" description="当前没有可投诉的案件"/></List></PullRefresh></div></template>
