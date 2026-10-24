<script setup lang="ts">
import {computed,onMounted,ref,watch} from 'vue'
import {httpClient} from '@/shared/http/client'
import {workOrderApi,type WorkOrder,type ShopOption} from './work-orders'
const props=defineProps<{order:WorkOrder;expanded?:boolean;selectedShopId?:number}>()
const emit=defineEmits<{changed:[order:WorkOrder];editing:[];pending:[value:boolean];select:[id:number|undefined]}>(),api=workOrderApi(httpClient)
const address=ref(props.order.parkingAddress??''),district=ref(String(props.order.parkingRegionId??''))
const saved=ref(props.order.dispatchAddressConfirmed===true&&Boolean(address.value.trim())),confirmed=ref(saved.value),busy=ref(false),error=ref('')
const rows=ref<{id:number;name:string}[]>([]),regionPage=ref(0),regionTotal=ref(0),regionBusy=ref(false),regionError=ref('')
const shops=ref<ShopOption[]>([]),shopPage=ref(0),shopTotal=ref(0),shopBusy=ref(false),shopError=ref('')
let shopRevision=0
const chosen=computed(()=>shops.value.find(shop=>shop.id===props.selectedShopId))
const actionName=computed(()=>props.order.status==='PENDING_DISPATCH'?'派单':'改派')
const assignedName=computed(()=>props.order.currentAssignment?(shops.value.find(shop=>shop.id===props.order.currentAssignment?.shopId)?.name??'网点ID '+props.order.currentAssignment.shopId):'')
const missing=computed(()=>!props.order.accidentRegionId?'请先在上方“事故区县”核实并补全事故服务区县。':!saved.value?'请填写并确认车辆当前停放区域和详细地址，再保存停车位置。':shopError.value?'网点列表读取失败，请重试；不能用旧列表派单。':shopBusy.value?'正在读取事故区县内的可派网点…':!shops.value.length?'该事故区县暂无启用且服务关系匹配的网点，请联系管理员核实测试网点资料；不要为匹配网点修改事故事实。':!chosen.value?(assignedName.value?'当前派单网点：'+assignedName.value+'；如需改派，请重新人工选择合格网点。':'请选择下方一个有资格的网点，再点击原“'+actionName.value+'”按钮。'):'已选网点：'+chosen.value.name+'；请点击下方原“'+actionName.value+'”按钮。')
function pending(){emit('pending',!saved.value||busy.value||shopBusy.value||Boolean(shopError.value))}
function clearSelection(){emit('select',undefined)}
function changed(){emit('editing');saved.value=false;confirmed.value=false;error.value='';clearSelection();pending()}
function confirmationChanged(){emit('editing');if(!confirmed.value){saved.value=false;clearSelection();pending()}}
async function regions(append=false){
 if(regionBusy.value)return
 regionBusy.value=true;regionError.value=''
 try{const next=append?regionPage.value+1:1,result=await api.accidentDistricts('',next);rows.value=append?[...rows.value,...result.records]:result.records;regionPage.value=next;regionTotal.value=result.total}
 catch(e){regionError.value=e instanceof Error?e.message:'停车区域读取失败'}finally{regionBusy.value=false}
}
async function moreShops(append=false){
 if(append&&shopBusy.value)return
 const revision=++shopRevision
 if(!append){shops.value=[];shopPage.value=0;shopTotal.value=0;clearSelection()}
 shopError.value=''
 if(!props.order.accidentRegionId){shopBusy.value=false;pending();return}
 shopBusy.value=true;pending()
 try{const next=append?shopPage.value+1:1,result=await api.shops(props.order.id,next,false);if(revision!==shopRevision)return;shops.value=append?[...shops.value,...result.records]:result.records;shopPage.value=next;shopTotal.value=result.total}
 catch(e){if(revision===shopRevision){shopError.value=e instanceof Error?e.message:'网点列表读取失败';clearSelection()}}finally{if(revision===shopRevision){shopBusy.value=false;pending()}}
}
async function save(){
 if(busy.value)return
 if(!props.order.accidentRegionId||!district.value||!address.value.trim()||!confirmed.value){error.value='请先补全事故区县，并填写、核对车辆停放区域和详细地址';return}
 emit('editing');busy.value=true;error.value='';clearSelection();pending()
 try{const updated=await api.dispatchLocation(props.order.id,props.order.version,Number(district.value),address.value.trim());saved.value=true;emit('changed',updated);await moreShops()}
 catch(e){saved.value=false;error.value=e instanceof Error?e.message:'停车位置保存失败，输入已保留，请重试'}finally{busy.value=false;pending()}
}
function select(id:number){if(saved.value&&!busy.value&&!shopBusy.value&&!shopError.value&&props.order.accidentRegionId&&shops.value.some(shop=>shop.id===id)){emit('select',id);pending()}}
watch(()=>[props.order.parkingAddress,props.order.parkingRegionId,props.order.dispatchAddressConfirmed],()=>{
 const same=address.value.trim()===(props.order.parkingAddress??'').trim()&&district.value===String(props.order.parkingRegionId??'')
 if(same){saved.value=props.order.dispatchAddressConfirmed===true&&Boolean(address.value.trim());confirmed.value=saved.value}
 else{saved.value=false;confirmed.value=false;error.value='已保存的停车位置发生变化，请保留当前输入并重新核对、保存。'}
 clearSelection();pending()
})
watch(()=>props.order.accidentRegionId,()=>{clearSelection();void moreShops()})
onMounted(async()=>{pending();await Promise.all([regions(),moreShops()])})
</script>
<template><details class="manual-dispatch" :open="expanded"><summary>{{ expanded?'人工确认停车位置与选择网点':'原人工派单（不调用地图）' }}</summary>
 <p>服务资格按<strong>事故服务区县</strong>及网点启用、服务关系判断；<strong>车辆当前停放位置</strong>独立保存，不覆盖事故地址或事故区县。不需要真实地图Key、道路距离或推荐会话；仍通过原派单服务复核并提交。</p>
 <label>车辆停放区域<select v-model="district" :disabled="busy" @change="changed"><option value="">请选择已核实区县</option><option v-if="district&&!rows.some(row=>String(row.id)===district)" :value="district">已保存区域（ID {{ district }}）</option><option v-for="row in rows" :key="row.id" :value="String(row.id)">{{ row.name }}</option></select></label>
 <button v-if="rows.length<regionTotal" type="button" :disabled="regionBusy||busy" @click="regions(true)">更多停车区县</button><p v-if="regionError" role="alert">{{ regionError }} <button type="button" :disabled="regionBusy" @click="regions()">重试读取区域</button></p>
 <label>车辆当前停放详细地址<input v-model="address" :disabled="busy" maxlength="255" placeholder="填写街道、门牌或明确入口，不是事故地址" @input="changed"/></label>
 <label><input v-model="confirmed" type="checkbox" :disabled="busy" @change="confirmationChanged"/>已人工核实当前停放位置，不修改事故地址/区县</label>
 <button type="button" :disabled="busy||!order.accidentRegionId||!confirmed||!district||!address.trim()" @click="save">{{ busy?'正在保存停车位置…':'保存人工核对的停车位置' }}</button><p v-if="saved" role="status">停车位置已确认；重新打开无需重复定位。</p>
 <p class="dispatch-guidance" role="status">{{ missing }}</p><p v-if="error" role="alert">{{ error }}</p>
 <h4>按事故服务区县筛选的合作网点（{{ shopTotal }}）</h4><p>道路距离未计算；列表按名称与ID稳定分页，不宣称道路最近。不代表已核实车型能力。网点能力未完善不等于没有维修能力。</p>
 <button type="button" :disabled="shopBusy||busy||!order.accidentRegionId" @click="moreShops()">{{ shopBusy?'正在读取网点…':'刷新可派网点（会清除旧选择）' }}</button>
 <ul aria-label="人工派单合格网点"><li v-for="shop in shops" :key="shop.id"><span>{{ shop.name }} · {{ shop.address||'地址待完善' }} · 道路距离未计算</span><button type="button" :disabled="busy||shopBusy||!saved||Boolean(shopError)" :aria-pressed="selectedShopId===shop.id" @click="select(shop.id)">{{ selectedShopId===shop.id?'已选择：':'人工选择：' }}{{ shop.name }}</button></li></ul>
 <button v-if="shops.length<shopTotal" type="button" :disabled="shopBusy||busy" @click="moreShops(true)">加载更多可派网点（{{ shops.length }}/{{ shopTotal }}）</button><p v-if="shopError" role="alert">{{ shopError }}；请刷新网点列表重试。</p>
</details></template>
<style scoped>.manual-dispatch{padding:14px 0;border-top:1px solid #dce5e4;scroll-margin-top:var(--workflow-header-height,160px)}.manual-dispatch summary{font-weight:600;cursor:pointer}.manual-dispatch label{display:block;margin:12px 0}.manual-dispatch input:not([type=checkbox]),select{display:block;padding:10px;width:100%;box-sizing:border-box}.manual-dispatch p,.manual-dispatch li{font-size:13px;line-height:1.7}.manual-dispatch ul{padding-left:18px}.manual-dispatch li{margin:12px 0}.manual-dispatch li span{display:block;margin-bottom:6px}.manual-dispatch button[aria-pressed=true]{outline:2px solid #406951}.dispatch-guidance{padding:12px;background:#eef5ef;border-radius:6px}.manual-dispatch [role=alert]{color:#a7313c}</style>
