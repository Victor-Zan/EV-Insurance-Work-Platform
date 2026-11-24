<script setup lang="ts">
import {computed,onMounted,onUnmounted,ref,watch} from 'vue'
import {ElMessageBox} from 'element-plus'
import {httpClient} from '@/shared/http/client'
import {ApiError} from '@/shared/http/create-client'
import AmapLocationMap from '../materials/AmapLocationMap.vue'
import AccidentDistrictReview from './AccidentDistrictReview.vue'
import type {WorkOrder} from './work-orders'
interface Point{latitude:number;longitude:number}
interface Entry{id:number;name:string;address:string;contactPhone:string|null;districtId:number;district:string}
interface Choice{token:string;candidate:{address:string;point:Point;status:string;mock:boolean}}
const props=defineProps<{caseId:string;planId?:string;pickupReady:boolean;accidentReady:boolean;disabled?:boolean;order?:WorkOrder}>()
const emit=defineEmits<{changed:[order?:WorkOrder];relocate:[]}>()
const base=computed(()=>`/maps/cases/${props.caseId}/workshop-directory`)
const query=ref(''),page=ref(1),total=ref(0),rows=ref<Entry[]>([]),busy=ref(false),loading=ref(false),error=ref('')
const selected=ref<Entry>(),choices=ref<Choice[]>([]),token=ref(''),verified=ref(false),correction=ref<Point>(),requestKey=ref(crypto.randomUUID())
const chosen=computed(()=>choices.value.find(c=>c.token===token.value))
const blockingReasons=computed(()=>{
 const reasons:string[]=[]
 if(!props.accidentReady)reasons.push('事故区县尚未保存，请核实并保存实际事故发生的区县。')
 if(!props.pickupReady||!props.planId)reasons.push('接车位置尚未定位或已过期，请重新定位接车位置。')
 if(!selected.value)reasons.push('尚未选择维修门店。')
 else if(!chosen.value||chosen.value.candidate.mock||chosen.value.candidate.status!=='REQUIRES_CONFIRMATION')reasons.push('门店位置尚未核验，请选择明确位置或核验地图校正点。')
 if(!verified.value)reasons.push('请勾选门店位置、维修能力及本案承接意愿核实。')
 if(props.disabled||busy.value)reasons.push('正在处理定位或提交，请等待完成。')
 return reasons
})
const canDispatch=computed(()=>blockingReasons.value.length===0)
let generation=0,searchGeneration=0,disposed=false
const cleanName=(name:string)=>name.startsWith('【待核验】')?name.slice(5):name
function clearSelection(){generation++;selected.value=undefined;choices.value=[];token.value='';verified.value=false;correction.value=undefined;requestKey.value=crypto.randomUUID()}
async function search(next=1){
 if(busy.value)return
 const current=++searchGeneration;loading.value=true;error.value=''
 try{const value=(await httpClient.get(base.value,{params:{query:query.value.trim(),page:next,size:20}})).data.data as {records:Entry[];total:number};if(disposed||current!==searchGeneration)return;rows.value=value.records;total.value=value.total;page.value=next}
 catch(e){if(!disposed&&current===searchGeneration){rows.value=[];error.value=e instanceof Error?e.message:'候选网点读取失败，请重试'}}finally{if(current===searchGeneration)loading.value=false}
}
async function choose(entry:Entry,point?:Point){
 if(busy.value||props.disabled)return
 clearSelection();selected.value=entry;busy.value=true;error.value='';const current=generation
 try{const value=(await httpClient.post(`${base.value}/prepare`,{sourceShopId:entry.id,point:point??null},{timeout:20000})).data.data as {choices:Choice[]};if(disposed||current!==generation)return;choices.value=value.choices;const valid=value.choices.filter(c=>!c.candidate.mock&&c.candidate.status==='REQUIRES_CONFIRMATION');if(valid.length===1)token.value=valid[0]!.token;if(!value.choices.length)error.value='没有解析到门店位置，请点击地图校正后核对。'}
 catch(e){if(!disposed&&current===generation)error.value=e instanceof Error?e.message:'门店定位失败，未自动重试'}finally{if(!disposed&&current===generation)busy.value=false}
}
function pick(point:Point){correction.value=point;token.value='';verified.value=false}
async function correct(){if(selected.value&&correction.value)await choose(selected.value,correction.value)}
async function dispatch(confirmPossibleDuplicate=false,duplicateReason:string|null=null){
 if(!canDispatch.value||!selected.value||!props.planId)return
 busy.value=true;error.value=''
 try{const result=(await httpClient.post(`${base.value}/dispatch`,{planId:props.planId,sourceShopId:selected.value.id,shopLocationToken:token.value,verified:verified.value,confirmPossibleDuplicate,duplicateReason},{headers:{'Idempotency-Key':requestKey.value}})).data.data as WorkOrder;emit('changed',result)}
 catch(e){
  if(e instanceof ApiError&&e.code==='POSSIBLE_DUPLICATE'&&!confirmPossibleDuplicate){try{const answer=await ElMessageBox.prompt('该车主在此网点存在短期案件，请核实后填写继续派单原因','疑似重复案件',{inputValidator:value=>Boolean(value.trim())||'原因必填'});busy.value=false;await dispatch(true,answer.value.trim())}catch{/* Keep the selected shop when the user cancels. */}}
  else error.value=e instanceof Error?e.message:'派单结果未确认，请刷新案件核对；重试使用原操作标识'
 }finally{busy.value=false}
}
watch(token,()=>{verified.value=false;requestKey.value=crypto.randomUUID()})
watch(()=>[props.caseId,props.planId],()=>{clearSelection();busy.value=false;error.value='';if(!disposed)void search()})
onMounted(()=>void search())
onUnmounted(()=>{disposed=true;generation++;searchGeneration++})
</script>

<template>
 <section class="directory-dispatch" aria-label="深圳候选网点核对与派单" :aria-busy="busy||loading">
  <header><div><h4>深圳候选维修网点</h4><p>云端已导入 {{ total }} 家匹配门店。包含待核验、停用资料；选店后确认实际维修能力和承接意愿，仅为本案登记，不会批量启用。</p></div></header>
  <form class="search" @submit.prevent="search()"><label>搜索网点<input v-model="query" maxlength="100" placeholder="门店名称、区县、地址或公开电话" :disabled="busy"></label><button type="submit" :disabled="busy||loading">{{ loading?'正在读取…':'搜索网点' }}</button></form>
  <p v-if="!accidentReady" role="status">请先在本页上方核实并保存事故区县，再确认派单；仍可浏览和核对门店。</p>
  <p v-if="!pickupReady" role="status">请先完成上方接车位置定位；候选门店不受合作网点数量为零的限制。</p>
  <div class="entries"><button v-for="entry in rows" :key="entry.id" type="button" :disabled="busy||disabled" :aria-pressed="selected?.id===entry.id" @click="choose(entry)"><span><strong>{{ cleanName(entry.name) }}</strong><small>{{ entry.address }}</small><small>{{ entry.contactPhone?'公开电话：'+entry.contactPhone:'未提供公开电话' }} · 待核验</small></span><span>{{ selected?.id===entry.id?'已选择':'核对并选择' }}</span></button></div>
  <p v-if="!loading&&!rows.length">没有匹配的候选网点，请调整搜索内容。</p>
  <nav v-if="total>20" aria-label="深圳候选网点分页"><button type="button" :disabled="busy||loading||page<=1" @click="search(page-1)">上一页</button><span>第 {{ page }} 页 · 共 {{ total }} 家</span><button type="button" :disabled="busy||loading||page*20>=total" @click="search(page+1)">下一页</button></nav>
  <article v-if="selected" class="verification" aria-label="核对所选门店">
   <h4>核对门店：{{ cleanName(selected.name) }}</h4><p>资料地址：{{ selected.address }}</p>
   <label v-for="choice in choices" :key="choice.token" class="location-choice"><input v-model="token" type="radio" :value="choice.token" :disabled="busy||choice.candidate.mock||choice.candidate.status!=='REQUIRES_CONFIRMATION'">{{ choice.candidate.address }}{{ choice.candidate.status==='REQUIRES_CONFIRMATION'?'':'（定位不够准确，请在地图校正）' }}</label>
   <AmapLocationMap auto-load point-label="维修门店位置" :point="correction??chosen?.candidate.point??choices[0]?.candidate.point" :disabled="busy||disabled" :confirmed="Boolean(chosen)&&!correction" @pick="pick"/>
   <button v-if="correction" type="button" :disabled="busy" @click="correct">核验所选门店位置</button>
   <p>地图可点击或拖动校正门店位置。未计算道路距离，本列表不按距离排序。</p>
   <label class="confirm"><input v-model="verified" type="checkbox" :disabled="busy||!chosen">我已核对接车位置、门店名称及位置，确认该店能维修本案车辆并愿意承接本案。</label>
   <p>确认后将登记本案网点并派单；门店接单账号仍需管理员邀请开通。</p>
   <section v-if="blockingReasons.length" class="blocked" aria-label="派单尚缺条件" role="status"><strong>暂不能派单，请完成以下事项：</strong><ul><li v-for="reason in blockingReasons" :key="reason">{{ reason }}</li></ul><button v-if="!pickupReady||!planId" type="button" :disabled="busy||disabled" @click="emit('relocate')">重新定位接车位置</button></section>
   <AccidentDistrictReview v-if="!accidentReady&&order" :order="order" @changed="emit('changed')"/>
   <button class="primary" type="button" :disabled="!canDispatch" @click="dispatch()">{{ busy?'处理中…':'核对位置和所选网点并派单' }}</button>
  </article>
  <p v-if="error" role="alert">{{ error }}</p>
 </section>
</template>

<style scoped>
.directory-dispatch{display:grid;gap:15px;padding:18px;border:1px solid #c9dace;background:#f7faf7;border-radius:9px}.directory-dispatch h4{margin:0;font-size:17px}.directory-dispatch p{font-size:13px;line-height:1.8;margin:8px 0}.search,nav{display:flex;gap:12px;align-items:end;justify-content:space-between}.search label{flex:1;display:grid;gap:8px}.directory-dispatch input:not([type=checkbox]):not([type=radio]){width:100%;box-sizing:border-box;padding:10px;border:1px solid #becfc3;border-radius:6px}.directory-dispatch button{font:inherit;padding:10px 14px;border:1px solid #b8ccbd;border-radius:6px;background:white;color:#365b45;cursor:pointer}.directory-dispatch button:disabled{opacity:.5;cursor:not-allowed}.entries{display:grid;gap:8px;max-height:410px;overflow:auto}.entries button{display:flex;justify-content:space-between;text-align:left;gap:16px}.entries button[aria-pressed=true]{border-color:#367657;background:#e8f4eb}.entries small{display:block;font-size:12px;line-height:1.8;color:#63796b}.verification{display:grid;gap:12px;border-top:1px solid #ceddd2;padding-top:18px}.confirm,.location-choice{display:flex;gap:10px;align-items:start;line-height:1.8;font-size:14px}.directory-dispatch .primary{justify-self:end;background:#365f4b;color:white}.directory-dispatch [role=alert]{color:#a53d34;padding:12px;background:#fff0ec}@media(max-width:650px){.search{align-items:stretch;flex-direction:column}.entries button{flex-direction:column}.directory-dispatch{padding:12px}.primary{width:100%}}
</style>
<style scoped>.blocked{padding:14px 16px;background:#fff5e5;border:1px solid #d9b66e;border-radius:7px;color:#755322;font-size:14px;line-height:1.8}.blocked ul{padding-left:22px;margin:8px 0}</style>
