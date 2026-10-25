<script setup lang="ts">
import {computed,onMounted,onUnmounted,ref,watch} from 'vue'
import {httpClient} from '@/shared/http/client'
type Point={longitude:number;latitude:number}
interface Row{id:string;source:string;name:string;address:string;classification:string;reason:string;point:Point;metres:number|null;seconds:number|null;calculatedAt:string|null;status:string}
interface Result{queryId:string;provider:string;scope:string;expiresAt:string;page:number;hasMore:boolean;excluded:number;rows:Row[]}
interface Region{id:number;name:string}
interface Choice{token:string;candidate:{address:string;city:string;district:string;status:string}}
const props=defineProps<{caseId?:string;locationToken?:string;parkingReady?:boolean;inspectedId?:string}>()
const emit=defineEmits<{choose:[value:{shopId:number;queryId:string;name:string}|undefined];markers:[value:{id:string;name:string;point:Point}[]];focus:[value:Point];budget:[]}>()
const settings=ref<{enabled:boolean;radiusMetres:number;searchRemaining:number|null;routesRemaining:number|null;providerManaged?:boolean}>(),result=ref<Result>(),error=ref(''),busy=ref(false),selected=ref<Row>(),ownName=ref(''),ownAddress=ref(''),notice=ref('')
const phase=ref<'search'|'routes'>(),routeSummary=ref('')
const manual=ref(false),manualName=ref(''),manualCity=ref(''),manualDistrict=ref(''),manualAddress=ref(''),manualLocation=ref(''),manualChoices=ref<Choice[]>([]),cities=ref<Region[]>([]),districts=ref<Region[]>([]),cityPage=ref(1),cityTotal=ref(0)
let generation=0
const now=ref(Date.now());let clock:ReturnType<typeof setInterval>|undefined
const identities=new Map<string,string>()
function key(kind:string,input:unknown){const identity=kind+JSON.stringify(input);if(!identities.has(identity))identities.set(identity,crypto.randomUUID());return identities.get(identity)!}
const ready=computed(()=>Boolean(settings.value?.enabled&&props.caseId&&props.locationToken&&props.parkingReady!==false))
const expired=computed(()=>Boolean(result.value&&new Date(result.value.expiresAt).getTime()<=now.value))
const searchRemaining=computed(()=>settings.value?.searchRemaining??0),routesRemaining=computed(()=>settings.value?.routesRemaining??0)
const rows=computed(()=>result.value?.rows??[])
const providerManaged=computed(()=>settings.value?.providerManaged===true),canSearch=computed(()=>providerManaged.value||searchRemaining.value>0)
async function refresh(){try{settings.value=(await httpClient.get('/maps/public-workshops/configuration')).data.data}catch{settings.value={enabled:false,radiusMetres:10000,searchRemaining:0,routesRemaining:0};error.value='公开维修门店服务未启用或无法读取；原人工派单仍可使用'}}
function accept(value:Result){result.value=value;selected.value=undefined;notice.value='';emit('choose',undefined);emit('markers',value.rows.map(r=>({id:r.id,name:r.name,point:r.point})))}
async function action(run:()=>Promise<Result>,calculate=false){
 if(busy.value)return
 busy.value=true;error.value='';routeSummary.value='';phase.value=calculate?'search':undefined;const current=generation;emit('choose',undefined)
 try{
  const value=await run();if(current!==generation)return;accept(value)
  if(calculate&&value.rows.length){
   if(value.rows.length>20){error.value='候选超过本次20家上限，未计算任何路线。';return}
   await refresh();if(current!==generation||!ready.value)return
   if(expired.value){error.value='停车位置或候选已过期，未计算路线；不会自动重新搜索。';return}
   if(!providerManaged.value&&routesRemaining.value<value.rows.length){error.value=`本页有${value.rows.length}家候选，需要${value.rows.length}次路线，当前剩余${routesRemaining.value}次。未计算任何路线，也未截取部分候选；当前列表不是道路距离排序。`;return}
   phase.value='routes'
   const body={queryId:value.queryId,selected:value.rows.map(row=>row.id).sort()}
   const sorted=(await httpClient.post('/maps/public-workshops/routes',body,{timeout:75000,headers:{'Idempotency-Key':key('routes',body)}})).data.data as Result
   if(current!==generation)return;accept(sorted)
   const successful=sorted.rows.filter(validDistance).length
   routeSummary.value=successful===sorted.rows.length?`本页${successful}家候选道路距离已计算，按道路距离升序排列。`:`本页${successful}/${sorted.rows.length}家路线成功；失败或未完成项距离留空并列在后方，不代表完整道路距离排序通过。`
  }
 }catch(e){if(current===generation)error.value=e instanceof Error?e.message:'操作失败，未自动重试；输入保留'}finally{phase.value=undefined;busy.value=false;await refresh();emit('budget')}
}
function search(page=1,fresh=false){if(busy.value||!ready.value||!canSearch.value)return;const body={caseId:props.caseId,locationToken:props.locationToken,page};if(fresh&&result.value)identities.delete('search'+JSON.stringify(body));void action(async()=>(await httpClient.post('/maps/public-workshops/search',body,{timeout:15000,headers:{'Idempotency-Key':key('search',body)}})).data.data,true)}
function inspect(row:Row){emit('focus',row.point)}
function select(row:Row){selected.value=row;ownName.value=row.name;ownAddress.value=row.address;notice.value='';emit('choose',undefined);inspect(row)}
watch(()=>props.inspectedId,id=>{const row=rows.value.find(r=>r.id===id);if(row)select(row)})
watch([ownName,ownAddress],()=>{notice.value='';emit('choose',undefined)})
watch(()=>[props.locationToken,props.caseId],()=>{generation++;result.value=undefined;selected.value=undefined;routeSummary.value='';manualLocation.value='';manualChoices.value=[];emit('markers',[]);emit('choose',undefined);notice.value='';error.value=''})
async function createTemporary(){
 if(busy.value||!ready.value||!selected.value||!result.value||expired.value)return
 const body={queryId:result.value.queryId,candidateId:selected.value.id,name:ownName.value.trim(),address:ownAddress.value.trim(),verified:true},current=generation
 busy.value=true;error.value=''
 try{const saved=(await httpClient.post('/maps/public-workshops/temporary-shop',body,{headers:{'Idempotency-Key':key('temporary',body)}})).data.data as {shopId:number;name:string;notice:string};if(current!==generation)return;notice.value=saved.notice;emit('choose',{shopId:saved.shopId,queryId:'',name:saved.name})}catch(e){error.value=e instanceof Error?e.message:'登记失败；不会自动派单，输入保留'}finally{busy.value=false}
}
async function loadCities(){if(busy.value)return;busy.value=true;try{const page=(await httpClient.get('/maps/locations/regions',{params:{page:cityPage.value,size:20}})).data.data;cities.value.push(...page.records);cityTotal.value=page.total;cityPage.value++}catch(e){error.value=e instanceof Error?e.message:'城市读取失败'}finally{busy.value=false}}
async function openManual(){manual.value=!manual.value;if(manual.value&&!cities.value.length)await loadCities()}
watch(manualCity,async()=>{manualDistrict.value='';districts.value=[];const requested=manualCity.value;if(!requested)return;try{const page=(await httpClient.get('/maps/locations/regions',{params:{cityId:Number(requested),page:1,size:100}})).data.data;if(manualCity.value===requested)districts.value=page.records}catch(e){error.value=e instanceof Error?e.message:'区县读取失败'}})
watch([manualName,manualCity,manualDistrict,manualAddress],()=>{manualLocation.value='';manualChoices.value=[]})
async function findManual(){
 if(busy.value||!manualDistrict.value||manualAddress.value.trim().length<4||!canSearch.value)return
 busy.value=true;error.value='';const original=JSON.stringify([manualCity.value,manualDistrict.value,manualAddress.value])
 try{const value=(await httpClient.post('/maps/locations/places',{districtId:Number(manualDistrict.value),keywords:manualAddress.value.trim(),page:1},{timeout:15000})).data.data;if(original===JSON.stringify([manualCity.value,manualDistrict.value,manualAddress.value]))manualChoices.value=value.records}catch(e){error.value=e instanceof Error?e.message:'门店地址搜索失败；未自动重试'}finally{busy.value=false;await refresh();emit('budget')}
}
async function chooseManual(choice:Choice){if(busy.value||choice.candidate.status!=='REQUIRES_CONFIRMATION')return;busy.value=true;error.value='';try{await httpClient.post(`/maps/locations/${choice.token}/confirm`);manualLocation.value=choice.token}catch(e){error.value=e instanceof Error?e.message:'门店地址确认失败'}finally{busy.value=false}}
function addManual(){if(!ready.value||!manualLocation.value||manualName.value.trim().length<2)return;const body={caseId:props.caseId,queryId:result.value?.queryId??null,parkingToken:props.locationToken,shopLocationToken:manualLocation.value,name:manualName.value.trim()};void action(async()=>(await httpClient.post('/maps/public-workshops/manual',body,{headers:{'Idempotency-Key':key('manual',body)}})).data.data)}
function status(row:Row){if(row.calculatedAt)return now.value-new Date(row.calculatedAt).getTime()<120000?'道路距离已计算':'道路结果已过期';return ({NOT_CALCULATED:'未计算道路距离',ROUTE_EXPIRED:'道路结果已过期',QUERY_TIME_BUDGET:'本次计算时间用尽',MAP_NO_ROUTE:'无可行驶路线',MAP_QUOTA:'供应商额度不足',MAP_TIMEOUT:'请求超时，可能已计次',MAP_LOCAL_BUDGET:'本机额度不足',MAP_CREDENTIALS:'高德凭据错误',MAP_PERMISSION:'高德接口权限不足',MAP_RATE_LIMIT:'高德限流，未自动重试',MAP_NETWORK:'网络异常，未自动重试',MAP_UPSTREAM:'高德服务返回异常'} as Record<string,string>)[row.status]??row.status}
function validDistance(row:Row){return row.metres!=null&&row.calculatedAt!=null&&now.value-new Date(row.calculatedAt).getTime()<120000}
onMounted(()=>{void refresh();clock=setInterval(()=>{now.value=Date.now()},1000)})
onUnmounted(()=>{clearInterval(clock);generation++})
</script>
<template>
 <article class="public-workshops" :aria-busy="busy">
  <header><div><span>02 · 选择维修门店</span><h3>智能分拨候选</h3></div><button type="button" :disabled="busy||!ready||!canSearch" @click="search(1,true)">{{ phase==='search'?'正在查询维修门店…':phase==='routes'?'正在计算道路距离…':result?'重新查询并排序':'查询附近维修门店' }}</button></header>
  <p>两轮电动自行车维修 · 停车点周边10公里 · 单次最多20家公开结果，不限是否合作。地图资料不能证明实际维修能力。</p>
  <p v-if="!ready" class="warning">{{ !settings?.enabled?'此功能未启用，请使用原人工派单。':'请先确认并保存上方车辆停车位置。' }}</p>
  <p v-if="providerManaged">使用高德服务，本机不设累计调用次数上限；高德实际配额、权限及限流以接口返回为准。查询后自动计算本页最多20家候选的道路距离，不自动重试、扩圈或派单。</p><p v-else>受控测试模式：搜索剩余 {{ searchRemaining }} 次，路线剩余 {{ routesRemaining }} 次；每次查询后自动计算本页候选道路距离，仍受原授权额度限制。</p><p v-if="phase" role="status">{{ phase==='search'?'正在查询本页维修门店…':'门店已找到，正在自动计算道路距离并排序，请等待本批结果…' }}</p><p v-if="routeSummary" role="status">{{ routeSummary }} 道路结果有效2分钟，过期状态以各行提示为准。</p>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
  <p v-if="result">{{ result.scope }}；本页排除 {{ result.excluded }} 个无关/汽车专修/纯销售结果。</p>
  <p v-if="expired" class="warning">候选或停车会话已过期；不会自动查询或补次数。</p>
  <div v-if="result&&!rows.length" role="status">本页没有保留的两轮车维修候选。可明确查询下一页或手动补充，系统不会自动扩大范围。</div>
  <div v-if="rows.length" class="scroll"><table><thead><tr><th>维修门店／地址</th><th>驾车道路距离</th><th>操作</th></tr></thead><tbody><tr v-for="(row,index) in rows" :key="row.id" :class="{selected:selected?.id===row.id}"><td><strong>{{ index+1 }}. {{ row.name }}</strong><p>{{ row.address }}</p><small>{{ row.classification }} · {{ row.source==='MANUAL'?'人工补充':'高德公开资料；合作状态未核实' }}</small><small>{{ row.reason }}</small></td><td>{{ validDistance(row)?((row.metres??0)/1000).toFixed(2)+' km':phase==='routes'?'计算中…':'未知' }}<small>{{ validDistance(row)&&row.seconds!=null?'约'+Math.ceil(row.seconds/60)+'分钟':'' }}</small><small>{{ phase==='routes'?'等待本批道路计算结果':status(row) }}<template v-if="row.calculatedAt"> · {{ new Date(row.calculatedAt).toLocaleTimeString() }}</template></small></td><td><button type="button" :disabled="busy||expired" @click="inspect(row)">地图查看</button><button type="button" :disabled="busy||expired" @click="select(row)">选择并核实</button></td></tr></tbody></table></div>
  <div v-if="rows.length" class="tools"><small>车辆→门店 · 高德驾车策略32 · 按道路距离升序、同距离稳定排序；不是理论最短路或骑行距离。未知距离在后，只有已成功计算的集合可以比较；道路结果有效2分钟。</small></div>
  <div v-if="result" class="tools"><button type="button" :disabled="busy||expired||result.page<=1||!canSearch" @click="search(result.page-1)">上一页门店</button><span>第{{ result.page }}页，翻页将查询并自动计算该页候选路线；不会与其他页合称完整最近排序。</span><button type="button" :disabled="busy||expired||!result.hasMore||!canSearch" @click="search(result.page+1)">下一页门店</button></div>
  <button type="button" :disabled="busy||!ready" @click="openManual">{{ manual?'收起手动输入':'手动输入网点' }}</button>
  <fieldset v-if="manual" :disabled="busy||!ready"><legend>手动补充门店（每次最多5家，不代表已合作）</legend><p>自动道路计算仅处理本次公开搜索结果；手动补充不自动追加路线请求，距离可保持未知，仍可人工核实并登记。</p><label>门店名称<input v-model="manualName" maxlength="80" aria-label="手动门店名称"></label><label>城市<select v-model="manualCity" aria-label="手动门店城市"><option value="">请选择</option><option v-for="r in cities" :key="r.id" :value="String(r.id)">{{ r.name }}</option></select></label><button v-if="cities.length<cityTotal" type="button" @click="loadCities">更多城市</button><label>区县<select v-model="manualDistrict" aria-label="手动门店区县"><option value="">请选择</option><option v-for="r in districts" :key="r.id" :value="String(r.id)">{{ r.name }}</option></select></label><label>输入地址<input v-model="manualAddress" maxlength="80" aria-label="手动门店地址"></label><button type="button" :disabled="!manualDistrict||manualAddress.trim().length<4||!canSearch" @click="findManual">搜索门店地址</button><ul><li v-for="choice in manualChoices" :key="choice.token">{{ choice.candidate.address }}<button type="button" :disabled="choice.candidate.status!=='REQUIRES_CONFIRMATION'" @click="chooseManual(choice)">{{ manualLocation===choice.token?'门店地址已确认':'确认门店地址' }}</button></li></ul><button type="button" :disabled="!manualLocation||manualName.trim().length<2" @click="addManual">加入本次候选（不派单）</button></fieldset>
  <fieldset v-if="selected" :disabled="busy||expired"><legend>核实后登记本案临时门店</legend><p>请填写经核实、可用于本案业务登记的资料；不将未经核实的地图资料直接视为正式网点。</p><label>核实门店名称<input v-model="ownName" maxlength="80" aria-label="核实门店名称"></label><label>核实门店地址<input v-model="ownAddress" maxlength="255" aria-label="核实门店地址"></label><p>点击登记即确认已核实该门店可维修两轮电动自行车、愿意处理本案，名称和地址正确且可用于本案登记。</p><button type="button" :disabled="ownName.trim().length<2||ownAddress.trim().length<4" @click="createTemporary">登记并选择本案临时网点</button><p class="warning">仅限本案，不自动开通账号或提交派单。登记后仍须点击下方原“派单/改派”按钮；门店接单账号由管理员沿用受邀开通流程准备。</p><p v-if="notice" role="status">{{ notice }}</p></fieldset>
 </article>
</template>
<style scoped>
.public-workshops{padding:20px;border:1px solid #dce4df;border-radius:11px;background:white;font-size:13px;color:#355443}.public-workshops header{display:flex;gap:12px;align-items:center;justify-content:space-between}.public-workshops h3{margin:5px 0;font-size:18px}.public-workshops p,.public-workshops small{line-height:1.7}.public-workshops small{display:block;color:#6d7f73}.public-workshops button,.public-workshops input,.public-workshops select{font:inherit;padding:8px;border:1px solid #cdd9d1;border-radius:6px;background:#fff;color:#355443}.public-workshops button{cursor:pointer}.public-workshops button:disabled{cursor:not-allowed;opacity:.5}.public-workshops button:focus-visible,.public-workshops input:focus-visible,.public-workshops select:focus-visible{outline:2px solid #477b60;outline-offset:2px}.scroll{overflow:auto}.public-workshops table{width:100%;border-collapse:collapse}.public-workshops th,.public-workshops td{text-align:left;padding:10px;border-bottom:1px solid #e0e8e2;vertical-align:top}.public-workshops td button{margin-bottom:7px;white-space:nowrap}.selected{background:#edf6ef}.warning{color:#8a6c2e}.error{color:#a44e40;background:#fff4f1;padding:12px;border-radius:6px}.tools{margin:14px 0;display:flex;flex-wrap:wrap;gap:10px;align-items:center}.tools small{flex-basis:100%}.public-workshops fieldset{margin:15px 0;border:1px solid #d8e4db;border-radius:7px;padding:14px;display:flex;gap:12px;flex-wrap:wrap}.public-workshops label{display:flex;flex-direction:column;gap:5px}.public-workshops fieldset p,.public-workshops fieldset ul{flex-basis:100%}.public-workshops ul{padding-left:18px}.public-workshops li{margin:10px 0}@media(max-width:600px){.public-workshops{padding:13px}.public-workshops header{align-items:flex-start;flex-direction:column}.public-workshops fieldset label{width:100%}.public-workshops td{min-width:90px}.public-workshops td:nth-child(1){min-width:200px}}
</style>
