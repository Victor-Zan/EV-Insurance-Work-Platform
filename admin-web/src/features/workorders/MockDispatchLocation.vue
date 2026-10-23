<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import {httpClient} from '@/shared/http/client'
import {workOrderApi,type WorkOrder,type MapLocation,type MapShop} from './work-orders'
import {mapMarkers} from './dispatch-map'
import ManualDispatchChoice from './ManualDispatchChoice.vue'
const props=defineProps<{caseId:string;order:WorkOrder;selectedShopId?:number}>()
const emit=defineEmits<{changed:[];pending:[value:boolean];select:[id:number|undefined]}>(),api=workOrderApi(httpClient)
const busy=ref(false),error=ref(''),origin=ref<MapLocation>(),candidates=ref<MapShop[]>([]),total=ref(0)
let previewRevision=0
const markers=computed(()=>origin.value?mapMarkers(origin.value,candidates.value):[])
function changed(){previewRevision++;origin.value=undefined;candidates.value=[];total.value=0;error.value='';busy.value=false}
function manualChanged(){changed();emit('changed')}
async function preview(){
 if(busy.value||!props.order.dispatchAddressConfirmed||!props.order.parkingAddress)return
 const revision=++previewRevision;busy.value=true;error.value=''
 try{const point=await api.mapLocation(props.order.parkingAddress),result=await api.mapShops(props.caseId,1);if(revision!==previewRevision)return;origin.value=point;candidates.value=result.records;total.value=result.total}
 catch(e){if(revision===previewRevision)error.value=(e instanceof Error?e.message:'Mock位置读取失败')+'；不影响上方已确认地址与人工派单。'}finally{if(revision===previewRevision)busy.value=false}
}
watch(()=>[props.order.parkingAddress,props.order.parkingRegionId,props.order.accidentRegionId],changed)
</script>
<template><section class="mock-dispatch" aria-label="Mock人工派单">
 <p class="mock-notice" role="note">Mock模拟地图，位置和距离仅用于测试，不代表真实道路。</p>
 <ManualDispatchChoice :order="order" :selected-shop-id="selectedShopId" expanded @changed="manualChanged" @pending="emit('pending',$event)" @select="emit('select',$event)"/>
 <details class="mock-preview"><summary>可选：查看Mock模拟地图（不影响人工派单）</summary><p>仅显示当前第一页候选的模拟位置，不是高德底图或真实导航；人工选择以上方完整分页列表为准，不按此示意图推荐网点。</p>
  <button type="button" :disabled="busy||!order.dispatchAddressConfirmed||!order.parkingAddress" @click="preview">{{ busy?'正在读取模拟位置…':'显示已保存停车位置的Mock示意' }}</button><p v-if="!order.dispatchAddressConfirmed">请先在上方确认停车位置。</p><p v-if="error" role="alert">{{ error }}</p>
  <svg viewBox="0 0 600 300" role="img" aria-label="Mock模拟位置示意图，非真实地图"><rect width="600" height="300" fill="#edf3ed"/><path d="M0 80L600 240M90 0L410 300M0 270L600 30" stroke="#fff" stroke-width="15"/>
   <template v-if="origin"><circle :cx="markers[0]!.x" :cy="markers[0]!.y" r="10" fill="#c47d34"/><text :x="markers[0]!.x" :y="markers[0]!.y-16" text-anchor="middle" font-size="12">模拟停车位置</text><g v-for="(shop,index) in candidates" :key="shop.id"><circle :cx="markers[index+1]!.x" :cy="markers[index+1]!.y" r="10" fill="#406951"/><text :x="markers[index+1]!.x" :y="markers[index+1]!.y+4" text-anchor="middle" fill="white" font-size="11">{{ index+1 }}</text></g></template>
   <text v-else x="300" y="155" text-anchor="middle" fill="#61756b" font-size="14">可在上方直接人工派单，无需加载模拟位置</text>
  </svg><p v-if="origin">仅模拟展示 {{ candidates.length }}/{{ total }} 个候选；道路距离未知，不据此作全体网点距离排名。</p>
 </details>
</section></template>
<style scoped>.mock-notice{padding:12px;border:1px solid #c1d0c4;background:#f0f6ef;border-radius:8px;color:#355345}.mock-preview{margin:16px 0}.mock-preview p{font-size:13px;line-height:1.7}.mock-preview svg{display:block;width:100%;max-height:300px;border:1px solid #dce5df;border-radius:8px}.mock-preview [role=alert]{color:#a7313c}</style>
