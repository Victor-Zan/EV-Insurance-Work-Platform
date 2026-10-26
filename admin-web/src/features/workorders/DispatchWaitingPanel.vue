<script setup lang="ts">
import {computed} from 'vue'
import {localTime} from '@/shared/ui/presentation'
import type {WorkOrder} from './work-orders'
const props=defineProps<{order:WorkOrder}>()
const assignment=computed(()=>props.order.currentAssignment)
const destinationName=computed(()=>assignment.value?.destinationName??assignment.value?.shopName??`维修网点 · ${assignment.value?.shopId??props.order.shopId??''}`)
const destinationAddress=computed(()=>assignment.value?.destinationAddress??assignment.value?.shopAddress)
const redirected=computed(()=>assignment.value?.destinationShopId!=null&&assignment.value.destinationShopId!==assignment.value.shopId)
</script>

<template>
 <section class="dispatch-waiting" aria-label="已派单，等待网点接单">
  <header class="waiting-status" role="status"><span class="sent-label">已派单</span><h1>等待网点接单</h1><p>案件已派出，当前等待网点确认接单。</p></header>
  <article class="assigned-shop" aria-label="被派单网点"><h2>被派单网点</h2><h3>{{ destinationName }}</h3><p v-if="destinationAddress">{{ destinationAddress }}</p><p v-if="redirected" class="muted">开发接单端：{{ assignment?.shopName||'开发维修网点' }}</p><p v-if="assignment?.assignedAt" class="muted">派单时间：{{ localTime(assignment.assignedAt) }}</p><p v-if="assignment?.locationPrecision==='ROAD'" class="location-note">已按路段级粗略位置派单</p></article>
  <article class="waiting-case" aria-label="案件信息"><h2>案件信息</h2><dl>
   <div><dt>系统工单号</dt><dd>{{ order.businessNo||'—' }}</dd></div><div><dt>报案号</dt><dd>{{ order.claimNo||'—' }}</dd></div>
   <div><dt>保险公司</dt><dd>{{ order.insuranceCompany||'—' }}</dd></div><div><dt>车主</dt><dd>{{ order.ownerName||'—' }}</dd></div>
   <div><dt>车主电话</dt><dd>{{ order.ownerPhone||'—' }}</dd></div><div><dt>车辆品牌 / 型号</dt><dd>{{ [order.vehicleBrand,order.vehicleModel].filter(Boolean).join(' / ')||'—' }}</dd></div>
   <div><dt>车牌号</dt><dd>{{ order.vehiclePlate||'—' }}</dd></div><div><dt>车架号 / VIN</dt><dd>{{ order.vehicleVin||order.vehicleOtherIdentifier||'—' }}</dd></div>
   <div><dt>查勘员</dt><dd>{{ order.surveyorName||'—' }}</dd></div><div><dt>查勘员电话</dt><dd>{{ order.surveyorPhone||'—' }}</dd></div>
   <div class="wide"><dt>事故地点</dt><dd>{{ order.accidentAddress||'—' }}</dd></div><div class="wide"><dt>接车地点</dt><dd>{{ order.parkingAddress||order.accidentAddress||'—' }}</dd></div>
   <div><dt>事故时间</dt><dd>{{ order.accidentAt?localTime(order.accidentAt):'—' }}</dd></div><div class="wide" v-if="order.accidentDescription"><dt>案件说明</dt><dd>{{ order.accidentDescription }}</dd></div>
  </dl></article>
 </section>
</template>

<style scoped>
.dispatch-waiting{margin-top:20px;display:grid;gap:22px;color:#29473b}.waiting-status{padding:30px 32px;background:#edf6f0;border:1px solid #d8e7dc;border-radius:12px}.sent-label{display:inline-block;background:#367657;color:white;padding:5px 11px;border-radius:16px;font-size:12px}.waiting-status h1{font-size:26px;margin:14px 0 8px;line-height:1.4}.waiting-status p{font-size:14px;color:#647d6c;margin:0;line-height:1.8}.assigned-shop,.waiting-case{padding:26px 32px;background:white;border:1px solid #dde6df;border-radius:12px}.dispatch-waiting h2{font-size:15px;color:#6b8072;margin:0 0 18px}.assigned-shop h3{font-size:20px;line-height:1.6;margin:0 0 10px;overflow-wrap:anywhere}.assigned-shop p{line-height:1.8;margin:7px 0;font-size:14px;overflow-wrap:anywhere}.assigned-shop .muted{font-size:12px;color:#738378}.location-note{color:#8a6c34}.waiting-case dl{margin:0;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:22px 32px}.waiting-case dt{font-size:12px;color:#7a897f;margin-bottom:7px}.waiting-case dd{margin:0;font-size:14px;line-height:1.8;overflow-wrap:anywhere}.wide{grid-column:1/-1}@media(max-width:650px){.waiting-status,.assigned-shop,.waiting-case{padding:22px 18px}.waiting-case dl{grid-template-columns:1fr;gap:18px}.waiting-status h1{font-size:23px}.assigned-shop h3{font-size:18px}}
</style>
