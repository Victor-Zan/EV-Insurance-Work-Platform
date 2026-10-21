<script setup lang="ts">
import {onMounted,ref} from 'vue'
import {httpClient} from '@/shared/http/client'
import {workOrderApi,type WorkOrder} from './work-orders'
import MockDispatchLocation from './MockDispatchLocation.vue'
import ParkingLocationPanel from '../materials/ParkingLocationPanel.vue'
const props=defineProps<{caseId:string;order:WorkOrder;selectedShopId?:number}>()
const emit=defineEmits<{changed:[];pending:[value:boolean];select:[id:number|undefined];recommendation:[id:string|undefined]}>()
const config=ref<{provider:string;mock:boolean;testEnvironment?:boolean}>(),error=ref('')
const api=workOrderApi(httpClient)
function choose(value:{shopId:number;queryId:string}|undefined){emit('select',value?.shopId);emit('recommendation',value?.queryId)}
async function confirm(value:{districtId:number;address:string}){
 try{await api.dispatchLocation(props.caseId,props.order.version,value.districtId,value.address);emit('pending',false);emit('changed')}
 catch(e){emit('pending',true);error.value=e instanceof Error?e.message:'停车位置保存失败'}
}
onMounted(async()=>{emit('pending',true);try{config.value=(await httpClient.get('/maps/locations/configuration')).data.data}catch(e){error.value=e instanceof Error?e.message:'地图配置读取失败'}})
</script>
<template><section class="dispatch-location" data-reading="定位与推荐"><p v-if="config?.testEnvironment" role="note" class="test-banner">隔离测试环境 · 三个学校仅为虚构网点目的地，不是合作维修商，不代表具备维修能力；没有真实事故、车辆或客户。</p><p v-if="error" role="alert">{{ error }}</p><p v-if="!config&&!error">正在读取地图供应商…</p><MockDispatchLocation v-if="config?.mock" :case-id="caseId" :order="order" :selected-shop-id="selectedShopId" @changed="emit('changed')" @pending="emit('pending',$event)" @select="emit('select',$event)"/><ParkingLocationPanel v-else-if="config" :case-id="caseId" :selected-shop-id="selectedShopId" :initial-address="order.parkingAddress??order.accidentAddress??''" @choose="choose" @pending="emit('pending',$event)" @confirmed="confirm"/></section></template>

<style scoped>.test-banner{padding:12px;border:1px solid #c58a28;background:#fff8e6;color:#684714;border-radius:8px}</style>
