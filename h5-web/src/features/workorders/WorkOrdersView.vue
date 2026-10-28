<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Button, Empty, List, NavBar, PullRefresh, Search, Tag } from 'vant'
import { currentUser, httpClient } from '@/shared/http/client'
import { actionFor, caseStatusLabel, workOrderApi, type WorkOrder } from './work-orders'

const router=useRouter(),api=workOrderApi(httpClient),rows=ref<WorkOrder[]>([]),page=ref(1),total=ref(0),loading=ref(false),refreshing=ref(false),error=ref(''),query=ref('')
const isShop=computed(()=>currentUser.value?.roles.includes('REPAIR_SHOP')===true)
const finished=computed(()=>rows.value.length>=total.value&&total.value>=0)
function destination(row:WorkOrder){return `/work-orders/${row.id}?section=${actionFor(row.status,isShop.value?'REPAIR_SHOP':'OWNER').section}`}
async function load(reset=false){if(reset){page.value=1;rows.value=[];refreshing.value=true}else loading.value=true;error.value='';try{const result=await api.list(page.value,20,query.value);rows.value.push(...result.records);total.value=result.total;if(rows.value.length<result.total)page.value++}catch(e){error.value=e instanceof Error?e.message:'案件加载失败'}finally{loading.value=false;refreshing.value=false}}
onMounted(()=>load(true))
</script>

<template><div class="mobile-page"><NavBar :title="isShop?'本店案件':'我的案件'"/><Search v-model="query" show-action placeholder="工单号或报案号" aria-label="搜索案件" @search="load(true)" @clear="load(true)"><template #action><button class="search-action" @click="load(true)">搜索</button></template></Search>
  <p v-if="error" class="inline-error" role="alert">{{ error }} <Button size="small" @click="load(rows.length===0)">重试</Button></p>
  <PullRefresh v-model="refreshing" @refresh="load(true)"><List v-model:loading="loading" :finished="finished" :immediate-check="false" :finished-text="rows.length?'已显示全部案件':''" @load="load(false)"><button v-for="item in rows" :key="item.id" class="case-row" @click="router.push(destination(item))"><span><strong>{{ item.businessNo||'案件' }}</strong><small>{{ item.vehicleBrand||'未填写品牌' }} {{ item.vehicleModel }} · {{ actionFor(item.status,isShop?'REPAIR_SHOP':'OWNER').label }}</small><small v-if="isShop">{{ item.ownerName||'未填写车主' }}</small></span><Tag plain type="primary">{{ caseStatusLabel(item.status,isShop?'REPAIR_SHOP':'OWNER') }}</Tag></button><Empty v-if="!rows.length&&!loading&&!error" image-size="72" :description="query?'没有符合条件的案件':'当前没有可查看的案件'"/></List></PullRefresh>
  <p v-if="rows.length" class="list-total">已显示 {{ rows.length }} / {{ total }} 条</p></div></template>

<style scoped>.search-action{min-width:48px;min-height:40px;padding:0;border:0;background:transparent;color:var(--color-accent)}.list-total{margin:14px 16px;color:var(--color-text-secondary);font-size:12px;text-align:center}.case-row .van-tag{flex:0 0 auto}</style>
