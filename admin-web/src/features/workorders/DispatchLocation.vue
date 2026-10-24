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
// Prop synchronization in the folded manual form must not invalidate a saved map location.
const accidentBusy=ref(false),mapPending=ref(true),manualIsPending=ref(true),activePath=ref<'map'|'manual'>('map')
function publishPending(){emit('pending',accidentBusy.value||(activePath.value==='map'?mapPending.value:manualIsPending.value))}
function clearChoice(){emit('select',undefined);emit('recommendation',undefined)}
function manualEditing(){activePath.value='manual';manualIsPending.value=true;clearChoice();publishPending()}
function manualChanged(order:WorkOrder){manualEditing();parkingInitial.value=order.parkingAddress;mapPending.value=true;realRevision.value++;emit('changed')}
function pending(value:boolean){activePath.value='map';mapPending.value=value;publishPending()}
function manualPending(value:boolean){manualIsPending.value=value;publishPending()}
function accidentPending(value:boolean){accidentBusy.value=value;publishPending()}
function accidentChanged(){clearChoice();mapPending.value=true;manualIsPending.value=true;mapRevision.value++;publishPending();emit('changed')}
function choose(value:{shopId:number;queryId:string}|undefined){if(value){activePath.value='map';emit('select',value.shopId);emit('recommendation',value.queryId);publishPending()}else if(activePath.value==='map')clearChoice()}
function manualChoose(id:number|undefined){if(id!=null){activePath.value='manual';emit('select',id);emit('recommendation','');publishPending()}else if(activePath.value==='manual')clearChoice()}
async function confirm(value:{districtId:number;address:string}){
 error.value=''
 pending(true)
 try{await api.dispatchLocation(props.caseId,props.order.version,value.districtId,value.address);pending(false);emit('changed')}
 catch(e){emit('pending',true);error.value=e instanceof Error?e.message:'停车位置保存失败'}
}
async function loadConfiguration(){emit('pending',true);error.value='';if(!currentUser.value?.roles.includes('CUSTOMER_SERVICE')){config.value={provider:'MANUAL',mock:false};return}try{config.value=(await httpClient.get('/maps/locations/configuration')).data.data}catch(e){error.value=e instanceof Error?e.message:'地图配置读取失败'}}
onMounted(loadConfiguration)
</script>
<template><section class="dispatch-location" data-reading="定位与推荐"><p v-if="config?.testEnvironment" role="note" class="test-banner">隔离测试环境 · 三个学校仅为虚构网点目的地，不是合作维修商，不代表具备维修能力；没有真实事故、车辆或客户。</p><AccidentDistrictReview v-if="['PENDING_DISPATCH','PENDING_ACCEPTANCE','PENDING_ARRIVAL','ARRIVAL_EXCEPTION'].includes(order.status)" :order="order" @changed="accidentChanged" @pending="accidentPending"/><p v-if="error" role="alert">{{ error }} <button v-if="!config" type="button" @click="loadConfiguration">重试读取地图配置</button></p><p v-if="!config&&!error">正在读取地图供应商…</p><MockDispatchLocation :key="'mock-'+mapRevision" v-if="config?.mock" :case-id="caseId" :order="order" :selected-shop-id="selectedShopId" @changed="emit('changed')" @pending="pending" @select="choose($event==null?undefined:{shopId:$event,queryId:''})"/><ParkingLocationPanel :key="'amap-'+mapRevision+'-'+realRevision" v-else-if="config&&currentUser?.roles.includes('CUSTOMER_SERVICE')" :case-id="caseId" :selected-shop-id="selectedShopId" :parking-saved="!mapPending&&!accidentBusy&&order.dispatchAddressConfirmed" :initial-address="parkingInitial??order.parkingAddress??order.accidentAddress??''" @choose="choose" @pending="pending" @confirmed="confirm"/><ManualDispatchChoice v-if="config&&!config.mock" :key="'manual-'+mapRevision" :order="order" :selected-shop-id="selectedShopId" @changed="manualChanged" @editing="manualEditing" @pending="manualPending" @select="manualChoose"/></section></template>

<style scoped>.test-banner{padding:12px;border:1px solid #c58a28;background:#fff8e6;color:#684714;border-radius:8px}</style>
