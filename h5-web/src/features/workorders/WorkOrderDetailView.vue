<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Button, Cell, CellGroup, Field, NavBar, showSuccessToast, showToast, Tag } from 'vant'
import { currentUser, httpClient } from '@/shared/http/client'
import { statusLabels, workOrderApi, type History, type WorkOrder } from './work-orders'
const route=useRoute(),router=useRouter(),id=String(route.params.id),api=workOrderApi(httpClient)
const order=ref<WorkOrder>(),history=ref<History[]>([]),reason=ref(''),busy=ref(false)
const isShop=computed(()=>currentUser.value?.roles.includes('REPAIR_SHOP')===true)
const assignmentVersion=computed(()=>order.value?.currentAssignment?.assignmentVersion)
async function load(){try{order.value=await api.detail(id);history.value=(await api.history(id)).records}catch(error){showToast(error instanceof Error?error.message:'加载失败')}}
async function action(operation:'accept'|'reject'|'exception'|'continue'|'arrive'){
  if((operation==='reject'||operation==='exception')&&!reason.value.trim())return showToast('请填写原因')
  const version=assignmentVersion.value
  if(version===undefined)return showToast('当前派单版本缺失，请刷新后重试')
  busy.value=true
  try{
    if(operation==='accept')order.value=await api.accept(id,version)
    if(operation==='reject')order.value=await api.reject(id,version,reason.value.trim())
    if(operation==='exception')order.value=await api.exception(id,version,reason.value.trim())
    if(operation==='continue')order.value=await api.continueWaiting(id,version,reason.value.trim()||null)
    if(operation==='arrive')order.value=await api.arrive(id,version)
    reason.value='';showSuccessToast('操作成功');await load()
  }catch(error){showToast(error instanceof Error?error.message:'操作失败')}finally{busy.value=false}
}
onMounted(load)
</script>
<template><main v-if="order" class="mobile-shell"><NavBar :title="order.businessNo||'案件详情'" left-arrow @click-left="router.push('/work-orders')"/><section class="case-head"><Tag type="primary" size="large">{{ statusLabels[order.status] }}</Tag><h1>{{ order.vehicleBrand }} {{ order.vehicleModel }}</h1><p>{{ order.accidentAddress || '未填写详细地址' }}</p></section><CellGroup inset><Cell title="保险公司" :value="order.insuranceCompany"/><Cell title="报案号" :value="order.claimNo"/><Cell title="车主" :value="order.ownerName"/><Cell title="联系电话" :value="order.ownerPhone"/><Cell title="事故说明" :label="order.accidentDescription"/></CellGroup>
  <section v-if="isShop&&order.status==='PENDING_ACCEPTANCE'" class="operation"><h2>接单处理</h2><Field v-model="reason" label="拒绝原因" type="textarea" placeholder="拒绝时必填"/><div class="button-row"><Button block type="primary" :loading="busy" @click="action('accept')">接单</Button><Button block :loading="busy" @click="action('reject')">拒单</Button></div></section>
  <section v-if="isShop&&order.status==='PENDING_ARRIVAL'" class="operation"><h2>车辆到店</h2><Field v-model="reason" label="未到店原因" type="textarea" placeholder="出现延误时填写"/><div class="button-row"><Button block :loading="busy" @click="action('exception')">记录到店异常</Button><Button block type="primary" :loading="busy" @click="action('arrive')">确认到店</Button></div></section>
  <section v-if="isShop&&order.status==='ARRIVAL_EXCEPTION'" class="operation"><h2>到店异常处理</h2><Field v-model="reason" label="备注" type="textarea" placeholder="继续等待时可选填"/><div class="button-row"><Button block :loading="busy" @click="action('continue')">继续等待</Button><Button block type="primary" :loading="busy" @click="action('arrive')">确认到店</Button></div></section>
  <section class="operation"><h2>进度记录</h2><CellGroup><Cell v-for="item in history" :key="item.id" :title="statusLabels[item.toStatus as keyof typeof statusLabels]||item.toStatus" :value="item.occurredAt" :label="item.reason||item.action"/></CellGroup></section>
 </main></template>
<style scoped>.case-head,.operation{padding:22px 20px}.case-head h1{font-size:24px;margin:14px 0 8px}.case-head p{color:#667085}.operation h2{font-size:18px}.button-row{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:16px}</style>
