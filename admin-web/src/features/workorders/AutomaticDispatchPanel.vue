<script setup lang="ts">
import {computed,onMounted,onUnmounted,ref,watch} from 'vue'
import {ElMessageBox} from 'element-plus'
import {httpClient,currentUser} from '@/shared/http/client'
import {ApiError} from '@/shared/http/create-client'
import AmapLocationMap from '../materials/AmapLocationMap.vue'
import {mapStatus} from '../materials/map-recommendation'
import type {WorkOrder} from './work-orders'

const props=defineProps<{caseId:string;order:WorkOrder;simulationDestinations?:boolean}>()
const emit=defineEmits<{changed:[order?:WorkOrder];manual:[]}>()
interface Point {latitude:number;longitude:number}
interface Shop {shopId:number;name:string;address:string;status:string;metres:number|null;seconds:number|null;point?:Point}
interface Recommendation {queryId:string;complete:boolean;expiresAt:string;scope?:string;shops:{page:number;size:number;total:number;records:Shop[]}}
interface Plan {id:string;status:string;message:string;inputAddress:string;source:string;accidentRegion:string;parkingRegion:string;location?:{address:string;point:Point};recommendation?:Recommendation;selectedShopId?:number;expiresAt:string;approximate?:boolean;simulation?:boolean}
interface Draft {address:string;corrected:boolean;point?:Point;selected?:number;planId?:string;requestKey:string;expires:number}
const base=computed(()=>`/maps/cases/${props.caseId}/automatic-dispatch`)
const storageKey=computed(()=>`dispatch-draft:${currentUser.value?.id}:${props.caseId}:${props.order.version}`)
const initialAddress=()=>props.order.accidentAddress??''
const address=ref(initialAddress()),corrected=ref(false),point=ref<Point>(),plan=ref<Plan>(),selected=ref<number>(),selectedShop=ref<Shop>()
const busy=ref(false),submitting=ref(false),editing=ref(false),error=ref(''),now=ref(Date.now()),requestKey=ref<string>(crypto.randomUUID())
const catalog=ref<{page:number;size:number;total:number;records:Shop[]}>({page:1,size:20,total:0,records:[]})
const visibleShops=computed(()=>plan.value?.recommendation?.shops??catalog.value)
const comparisonUrl=(id:string)=>`/maps/${plan.value?.simulation?'simulation-recommendations':'recommendations'}/${id}`
async function loadCatalog(page=1){if(!props.simulationDestinations)return;try{catalog.value=(await httpClient.get('/maps/simulation-shops',{params:{page,size:20}})).data.data}catch(e){error.value=e instanceof Error?e.message:'模拟网点列表读取失败'}}
function chooseFromMap(id:number){const shop=visibleShops.value.records.find(s=>s.shopId===id);if(shop)choose(shop)}
const expired=computed(()=>Boolean(plan.value&&new Date(plan.value.expiresAt).getTime()<=now.value))
const canDispatch=computed(()=>!busy.value&&!submitting.value&&!expired.value&&Boolean(plan.value?.location)&&Boolean(selected.value)&&selectedShop.value?.status==='READY'&&selectedShop.value.metres!=null&&Boolean(plan.value&&['READY','PARTIAL','APPROXIMATE'].includes(plan.value.status)))
let generation=0,lastSubmitted='',restoredPlanId:string|undefined,disposed=false,pollTimer:ReturnType<typeof setTimeout>|undefined,clockTimer:ReturnType<typeof setInterval>|undefined
function persist(){
 const draft:Draft={address:address.value,corrected:corrected.value,point:point.value,selected:selected.value,planId:plan.value?.id,requestKey:requestKey.value,expires:Date.now()+600000}
 try{sessionStorage.setItem(storageKey.value,JSON.stringify(draft))}catch{/* Server deduplication remains authoritative when browser storage is unavailable. */}
}
function restore(){
 try{const value=JSON.parse(sessionStorage.getItem(storageKey.value)??'null') as Draft|null;if(value&&value.expires>Date.now()){address.value=value.address;corrected.value=value.corrected;point.value=value.point;selected.value=value.selected;restoredPlanId=value.planId;requestKey.value=value.requestKey}}catch{/* Ignore an unavailable or malformed local draft. */}
}
function invalidate(){generation++;clearTimeout(pollTimer);busy.value=false;lastSubmitted='';restoredPlanId=undefined;plan.value=undefined;selected.value=undefined;selectedShop.value=undefined;error.value='';persist()}
function changeAddress(value:string){editing.value=true;address.value=value;point.value=undefined;invalidate()}
function modifyPickup(){address.value=props.order.parkingAddress??plan.value?.location?.address??address.value;corrected.value=true;editing.value=true;invalidate()}
function pickPoint(value:Point){point.value=value;invalidate()}
function choose(shop:Shop){if(busy.value||expired.value||shop.status!=='READY'||shop.metres==null)return;selected.value=shop.shopId;selectedShop.value=shop;persist()}
async function apply(value:Plan,requestGeneration:number){
 if(disposed||requestGeneration!==generation)return
 if(restoredPlanId&&restoredPlanId!==value.id){selected.value=undefined;selectedShop.value=undefined}
 restoredPlanId=value.id
 plan.value=value;error.value=''
 const rows=value.recommendation?.shops.records??[]
 // A restored manual choice is kept only while it still belongs to this same valid plan.
 if(selected.value&&!rows.some(s=>s.shopId===selected.value)&&value.recommendation){
  for(let page=2;page<=Math.ceil(value.recommendation.shops.total/20)&&page<=3;page++){
   const next=(await httpClient.get(`/maps/${value.simulation?'simulation-recommendations':'recommendations'}/${value.recommendation.queryId}`,{params:{page,size:20}})).data.data as Recommendation
   if(disposed||requestGeneration!==generation)return
   const chosen=next.shops.records.find(s=>s.shopId===selected.value);if(chosen){selectedShop.value=chosen;break}
  }
 }
 if(!selected.value)selected.value=value.selectedShopId??undefined
 selectedShop.value=rows.find(s=>s.shopId===selected.value)??selectedShop.value
 if(selectedShop.value&&(selectedShop.value.status!=='READY'||selectedShop.value.metres==null)){selected.value=undefined;selectedShop.value=undefined}
 if(!['READY','PARTIAL','APPROXIMATE'].includes(value.status)){selected.value=undefined;selectedShop.value=undefined}
 persist()
 if(value.status==='RUNNING')pollTimer=setTimeout(()=>void poll(value.id,requestGeneration),900)
}
async function poll(id:string,requestGeneration:number){
 try{const value=(await httpClient.get(`${base.value}/${id}`)).data.data as Plan;await apply(value,requestGeneration)}
 catch(e){if(!disposed&&requestGeneration===generation){error.value=e instanceof Error?e.message:'任务读取失败，请明确重试';busy.value=false}}
 finally{if(requestGeneration===generation&&plan.value?.status!=='RUNNING')busy.value=false}
}
async function prepare(retry=false){
 if(submitting.value||address.value.trim().length<4)return
 const identity=JSON.stringify([props.order.version,address.value.trim(),corrected.value,point.value])
 if(!retry&&lastSubmitted===identity)return
 lastSubmitted=identity;const requestGeneration=++generation;clearTimeout(pollTimer)
 const priorPlanId=plan.value?.id
 if(retry){requestKey.value=crypto.randomUUID();selected.value=undefined;selectedShop.value=undefined}
 busy.value=true;error.value='';persist()
 try{
  const value=(await httpClient.post(base.value,{address:address.value.trim(),corrected:corrected.value,point:point.value??null,retry,requestKey:requestKey.value},{timeout:85000})).data.data as Plan
  if(disposed||requestGeneration!==generation)return
  if(priorPlanId&&priorPlanId!==value.id){selected.value=undefined;selectedShop.value=undefined}
  await apply(value,requestGeneration)
 }catch(e){if(!disposed&&requestGeneration===generation){error.value=e instanceof Error?e.message:'定位或推荐失败，输入已保留；未自动重试';plan.value=undefined}}
 finally{if(!disposed&&requestGeneration===generation&&plan.value?.status!=='RUNNING')busy.value=false}
}
async function resultPage(page:number){
 if(!plan.value?.recommendation){await loadCatalog(page);return}
 if(busy.value)return
 const requestGeneration=generation;busy.value=true
 try{const result=(await httpClient.get(comparisonUrl(plan.value.recommendation.queryId),{params:{page,size:20}})).data.data as Recommendation;if(requestGeneration===generation&&plan.value)plan.value.recommendation=result}
 catch(e){error.value=e instanceof Error?e.message:'网点分页读取失败'}finally{if(requestGeneration===generation)busy.value=false}
}
async function dispatch(confirmPossibleDuplicate=false,duplicateReason:string|null=null){
 if(!canDispatch.value||!plan.value)return
 submitting.value=true;error.value=''
 try{
  const response=await httpClient.post(`${base.value}/${plan.value.id}/dispatch`,{shopId:selected.value,confirmPossibleDuplicate,duplicateReason},{headers:{'Idempotency-Key':`automatic-dispatch-${plan.value.id}`}})
  try{sessionStorage.removeItem(storageKey.value)}catch{/* Dispatch success does not depend on browser storage. */}
  emit('changed',response.data.data as WorkOrder)
 }catch(e){
  if(e instanceof ApiError&&e.code==='POSSIBLE_DUPLICATE'&&!confirmPossibleDuplicate){
   try{const result=await ElMessageBox.prompt('该车主在此网点存在短期案件，请填写继续派单原因','疑似重复案件',{inputValidator:value=>Boolean(value.trim())||'原因必填'});submitting.value=false;await dispatch(true,result.value.trim())}catch{/* The user kept the plan without dispatching. */}
  }else error.value=e instanceof Error?e.message:'派单结果未确认，请刷新案件核对后重试'
 }finally{submitting.value=false}
}
watch(()=>props.order.version,()=>{generation++;clearTimeout(pollTimer);plan.value=undefined;selected.value=undefined;selectedShop.value=undefined;lastSubmitted='';requestKey.value=crypto.randomUUID();restore();if(!editing.value&&address.value.trim().length>=4)void prepare()})
onMounted(()=>{restore();void loadCatalog();editing.value=!address.value;clockTimer=setInterval(()=>{now.value=Date.now()},1000);if(address.value.trim().length>=4)void prepare()})
onUnmounted(()=>{disposed=true;generation++;clearTimeout(pollTimer);clearInterval(clockTimer)})
</script>

<template>
 <section class="automatic-dispatch" aria-label="自动定位与网点推荐" :aria-busy="busy||submitting">
  <header><div><span class="eyebrow">接车与派单</span><h3>核对位置，确认网点</h3></div><span class="status" role="status">{{ busy?'定位与道路比较中…':expired?'推荐已过期':plan?.approximate?'粗略定位':plan?.status==='READY'?'推荐已就绪':'待核对' }}</span></header>
  <dl class="facts"><div><dt>事故地点</dt><dd>{{ (!corrected?address:order.accidentAddress)||'第一步未填写详细地址' }}<small v-if="plan?.accidentRegion">{{ plan.accidentRegion }}</small></dd></div><div><dt>接车位置</dt><dd>{{ plan?.location?.address||address||'请补充接车地址' }}<small>{{ corrected?'已更正接车位置；事故区县保持原事实':order.dispatchAddressConfirmed?'使用已保存的接车位置':'默认车辆仍在事故地点' }}</small></dd><button type="button" :disabled="submitting" @click="modifyPickup">车辆已转移／修改接车位置</button></div></dl>
  <div class="correction"><label for="automatic-pickup">{{ corrected?'接车地址（车辆已转移）':'事故地址（可补充或更正）' }}<input id="automatic-pickup" :value="address" :disabled="submitting" maxlength="200" placeholder="请输入区县、道路及具体门牌或地标入口" @input="changeAddress(($event.target as HTMLInputElement).value)" @keydown.enter.prevent="prepare()" @blur="prepare()"></label><p>按回车或离开输入框自动定位并计算距离。只有路段信息时展示粗略定位；补充内容将在最后确认派单时保存。</p></div>
  <section class="map-correction" aria-label="事故地点与模拟网点地图"><h4>事故地点与网点位置 <span v-if="plan?.approximate" class="precision-label">路段级粗略定位</span></h4><AmapLocationMap auto-load :point="point??plan?.location?.point" :approximate="plan?.approximate" :confirmed="Boolean(plan?.location)&&!plan?.approximate" :disabled="busy||submitting" :shops="visibleShops.records" :selected-shop-id="selected" @pick="pickPoint" @choose="chooseFromMap"/><button v-if="point" type="button" :disabled="busy||submitting" @click="prepare(true)">使用所选接车点重新推荐</button></section>
  <section class="shop-catalog" aria-label="已导入网点与事故地点距离"><header><h4>{{ simulationDestinations?'已导入模拟维修网点':'本次合作维修网点' }}</h4><small>{{ visibleShops.total }} 家 · {{ plan?.approximate?'按粗略位置估算':'驾车道路距离' }}</small></header><p v-if="!visibleShops.records.length" class="comparison-scope">{{ busy?'正在计算网点距离…':'尚无可展示网点，请核对服务配置或点击重新计算。' }}</p><div class="shop-choices"><button v-for="(shop,index) in visibleShops.records" :key="shop.shopId" type="button" :disabled="busy||submitting||expired||shop.status!=='READY'||shop.metres==null" :aria-pressed="selected===shop.shopId" @click="choose(shop)"><span><strong>{{ (visibleShops.page-1)*visibleShops.size+index+1 }}. {{ shop.name }}</strong><small>{{ shop.address }}</small><small>{{ selected===shop.shopId?'已选择 · ':'' }}{{ mapStatus(shop.status) }}</small></span><b>{{ shop.metres==null?(shop.status==='NOT_CALCULATED'?'待计算':'距离未知'):(shop.metres/1000).toFixed(2)+' km' }}</b></button><nav v-if="visibleShops.total>visibleShops.size" aria-label="候选网点分页"><button :disabled="busy||visibleShops.page<=1" @click="resultPage(visibleShops.page-1)">上一页</button><span>第{{ visibleShops.page }}页 · 共{{ visibleShops.total }}家</span><button :disabled="busy||visibleShops.page*visibleShops.size>=visibleShops.total" @click="resultPage(visibleShops.page+1)">下一页</button></nav></div></section>
  <p v-if="plan?.message&&!busy" class="notice" :class="{ready:plan.status==='READY'}" role="status">{{ plan.message }}</p>
  <p v-if="error" class="failure" role="alert">{{ error }}</p>
  <p v-if="busy" class="notice" role="status">正在复核省市区、定位精度和网点资格，再按接车位置到网点的驾车道路距离比较。请稍候。</p>
  <article v-if="selectedShop" class="recommended"><span class="eyebrow">{{ selected!==plan?.selectedShopId?'当前手动选择':plan?.approximate?'按粗略位置推荐':plan?.status==='READY'?'道路距离最近的网点':'人工核对选择' }}</span><div><h4>{{ selectedShop.name }}</h4><strong>{{ selectedShop.metres==null?'距离未知':(selectedShop.metres/1000).toFixed(2)+' km' }}</strong></div><p>{{ selectedShop.address }}</p><small>驾车道路距离 · 接车位置 → 网点</small></article>
  <p v-if="plan?.recommendation" class="comparison-scope">比较范围：{{ plan.recommendation.scope||'事故区县服务资格内的广东合作网点' }}。本次进入道路计算 {{ plan.recommendation.shops.total }} 家；资料缺失与计算失败时不会认定全体最近。</p>
  <div class="final-action"><button v-if="error||expired||plan&&['NEEDS_REVIEW','PARTIAL','EXPIRED'].includes(plan.status)" type="button" :disabled="busy||submitting||address.trim().length<4" @click="prepare(true)">重新定位并计算</button><button class="primary" type="button" :disabled="!canDispatch" @click="dispatch()">{{ submitting?'正在派单…':'核对位置和网点并派单' }}</button></div>

  <footer><p>最终确认才保存必要位置并派单。推荐失效后需明确重算；系统不会自动派单或自动改派。</p><button type="button" :disabled="busy||submitting" @click="emit('manual')">异常处理／使用人工派单</button></footer>
 </section>
</template>

<style scoped>
.automatic-dispatch{display:grid;gap:20px;padding:24px;border:1px solid #dce4df;border-radius:11px;background:#fff;color:#304c3b}.automatic-dispatch header,.recommended>div,.final-action,.facts>div,footer{display:flex;align-items:center;justify-content:space-between;gap:16px}.automatic-dispatch h3{font-size:20px;margin:5px 0 0}.eyebrow,.status{font-size:12px;color:#697d70}.status{padding:7px 10px;background:#f1f5f2;border-radius:20px}.facts{margin:0;display:grid;gap:20px}.facts>div{align-items:flex-start}.facts dt{width:64px;flex-shrink:0;color:#748479;font-size:13px}.facts dd{flex:1;margin:0;line-height:1.7;font-size:14px;overflow-wrap:anywhere}.facts small,.shop-choices small{display:block;color:#748479;font-size:12px}.automatic-dispatch button,.automatic-dispatch input{font:inherit;border:1px solid #cad7ce;border-radius:6px;background:#fff;color:#365b45;padding:9px 12px;box-sizing:border-box}.automatic-dispatch button{cursor:pointer;font-size:13px;min-height:38px}.automatic-dispatch button:disabled{opacity:.5;cursor:not-allowed}.automatic-dispatch button:focus-visible,.automatic-dispatch input:focus-visible{outline:2px solid #477b60;outline-offset:2px}.automatic-dispatch .primary{background:#365f4b;color:#fff;border-color:#365f4b;padding:12px 22px;margin-left:auto}.correction{padding:16px;background:#f6f8f6;border-radius:7px}.correction label{display:grid;gap:9px;font-size:13px}.correction input{width:100%}.correction p,footer p,.map-correction p{font-size:12px;color:#748479;line-height:1.8;margin-bottom:0}.notice,.failure{padding:12px 15px;margin:0;border-radius:6px;background:#fbf7ec;color:#826839;font-size:13px;line-height:1.8}.notice.ready{background:#eef6ef;color:#3b6a49}.failure{background:#fff2ef;color:#944d40}.recommended{padding:20px;background:#f3f7f3;border:1px solid #d8e5d9;border-radius:8px}.recommended h4{font-size:18px;margin:12px 0}.recommended strong{font-size:22px;white-space:nowrap}.recommended p,.recommended small{font-size:12px;color:#6f8375;margin:0 0 6px}.shop-choices{display:grid;gap:8px}.shop-choices>button{display:flex;text-align:left;align-items:center;justify-content:space-between;gap:16px}.shop-choices [aria-pressed=true]{border-color:#477b60;background:#eff6ef}.shop-choices b{white-space:nowrap}.shop-choices nav{display:flex;justify-content:space-between;align-items:center;font-size:12px}.map-correction{font-size:13px;border-top:1px solid #e4eae5;padding-top:18px}.map-correction summary{cursor:pointer;margin-bottom:12px}footer{align-items:flex-start;border-top:1px solid #e4eae5;padding-top:14px}footer p{margin:0;max-width:60%}@media(max-width:700px){.automatic-dispatch{padding:16px}.facts>div{flex-wrap:wrap}.facts dd{min-width:60%}.facts button{margin-left:80px}.automatic-dispatch header,footer{align-items:flex-start;flex-direction:column}footer p{max-width:none}.final-action{flex-wrap:wrap}.automatic-dispatch .primary{width:100%}}
@media(max-width:700px){.recommended>div{display:block}.recommended strong{display:block;margin:6px 0 12px}.recommended h4{font-size:16px;line-height:1.6}.shop-choices>button{align-items:flex-start;flex-direction:column;gap:8px}}
.comparison-scope{font-size:12px;line-height:1.8;color:#748479;margin:0}.map-correction h4,.shop-catalog h4{font-size:15px;margin:0 0 12px}.shop-catalog header{margin-bottom:10px}.shop-catalog header small{font-size:12px;color:#748479}.precision-label{font-size:12px;color:#936927;font-weight:400;margin-left:8px}
</style>
