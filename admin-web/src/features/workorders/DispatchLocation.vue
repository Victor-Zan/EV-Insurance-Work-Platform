<script setup lang="ts">
import {onMounted,ref} from 'vue'
import {httpClient,currentUser} from '@/shared/http/client'
import {workOrderApi,type WorkOrder} from './work-orders'
import MockDispatchLocation from './MockDispatchLocation.vue'
import ParkingLocationPanel from '../materials/ParkingLocationPanel.vue'
import AccidentDistrictReview from './AccidentDistrictReview.vue'
import ManualDispatchChoice from './ManualDispatchChoice.vue'
const props=defineProps<{caseId:string;order:WorkOrder;selectedShopId?:number}>()
const emit=defineEmits<{changed:[];pending:[value:boolean];select:[id:number|undefined];recommendation:[id:string|undefined]}>()
const config=ref<{provider:string;mock:boolean;testEnvironment?:boolean}>(),error=ref('')
const api=workOrderApi(httpClient)
const mapRevision=ref(0)
const realRevision=ref(0),parkingInitial=ref<string>()
function manualChanged(order:WorkOrder){parkingInitial.value=order.parkingAddress;choose(undefined);realRevision.value++;emit('pending',true);emit('changed')}
const accidentBusy=ref(false),mapPending=ref(true)
function pending(value:boolean){mapPending.value=value;emit('pending',value||accidentBusy.value)}
function accidentPending(value:boolean){accidentBusy.value=value;emit('pending',value||mapPending.value)}
function accidentChanged(){choose(undefined);mapRevision.value++;emit('pending',true);emit('changed')}
function choose(value:{shopId:number;queryId:string}|undefined){emit('select',value?.shopId);emit('recommendation',value?.queryId)}
async function confirm(value:{districtId:number;address:string}){
 try{await api.dispatchLocation(props.caseId,props.order.version,value.districtId,value.address);emit('pending',false);emit('changed')}
 catch(e){emit('pending',true);error.value=e instanceof Error?e.message:'停车位置保存失败'}
}
onMounted(async()=>{emit('pending',true);try{config.value=(await httpClient.get('/maps/locations/configuration')).data.data}catch(e){error.value=e instanceof Error?e.message:'地图配置读取失败'}})
</script>
<template><section class="dispatch-location" data-reading="定位与推荐"><p v-if="config?.testEnvironment" role="note" class="test-banner">隔离测试环境 · 三个学校仅为虚构网点目的地，不是合作维修商，不代表具备维修能力；没有真实事故、车辆或客户。</p><AccidentDistrictReview v-if="['PENDING_DISPATCH','PENDING_ACCEPTANCE','PENDING_ARRIVAL','ARRIVAL_EXCEPTION'].includes(order.status)" :order="order" @changed="accidentChanged" @pending="accidentPending"/><p v-if="error" role="alert">{{ error }}</p><p v-if="!config&&!error">正在读取地图供应商…</p><MockDispatchLocation :key="'mock-'+mapRevision" v-if="config?.mock" :case-id="caseId" :order="order" :selected-shop-id="selectedShopId" @changed="emit('changed')" @pending="pending" @select="emit('select',$event)"/><ParkingLocationPanel :key="'amap-'+mapRevision+'-'+realRevision" v-else-if="config&&currentUser?.roles.includes('CUSTOMER_SERVICE')" :case-id="caseId" :selected-shop-id="selectedShopId" :initial-address="parkingInitial??order.parkingAddress??order.accidentAddress??''" @choose="choose" @pending="pending" @confirmed="confirm"/><ManualDispatchChoice v-if="config&&!config.mock" :key="'manual-'+mapRevision" :order="order" @changed="manualChanged" @pending="pending" @select="choose($event==null?undefined:{shopId:$event,queryId:''})"/></section></template>

<style scoped>.test-banner{padding:12px;border:1px solid #c58a28;background:#fff8e6;color:#684714;border-radius:8px}</style>
