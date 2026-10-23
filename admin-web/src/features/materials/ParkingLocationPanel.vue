<script setup lang="ts">
import {computed,onMounted,ref,watch} from 'vue'
import {httpClient} from '@/shared/http/client'
import {mapStatus} from './map-recommendation'
const props=defineProps<{caseId?:string;selectedShopId?:number;initialAddress?:string}>()
const emit=defineEmits<{choose:[value:{shopId:number;queryId:string;name:string}|undefined];pending:[value:boolean];confirmed:[value:{districtId:number;address:string}]}>()
interface Region {id:number;name:string;adcode:string}
interface Candidate {token:string;address:string;province:string;city:string;district:string;adcode:string;level:string;status:string;mock:boolean;point:{longitude:number;latitude:number}}
interface ShopRow {shopId:number;name:string;address:string;qualification:string;status:string;metres:number|null;seconds:number|null;calculatedAt:string|null;mock:boolean}
interface Recommendation {queryId:string;provider:string;mock:boolean;scope:string;complete:boolean;expiresAt:string;shops:{page:number;size:number;total:number;records:ShopRow[]}}
interface Exclusion {id:number;name:string;address:string;status:string}
const cities=ref<Region[]>([]),districts=ref<Region[]>([]),city=ref(''),district=ref(''),detail=ref(props.initialAddress??''),candidates=ref<Candidate[]>([]),confirmed=ref<Candidate>(),error=ref(''),busy=ref(false),cityPage=ref(1),districtPage=ref(1),cityTotal=ref(0),districtTotal=ref(0)
const budget=ref<{callsEnabled:boolean;reason:string;operations?:{operation:string;total:number;used:number;reserved:number;remaining:number}[]}>()
const operationName:Record<string,string>={GEOCODE:'地址解析',REVERSE:'逆地理核验',DRIVING:'驾车路线'}
function operationAvailable(operation:string){if(!budget.value?.callsEnabled)return false;const row=budget.value.operations?.find(item=>item.operation===operation);return !row||row.remaining>0}
const geocodeAvailable=computed(()=>operationAvailable('GEOCODE')),drivingAvailable=computed(()=>operationAvailable('DRIVING'))
const estimate=ref<{candidates:number;maximumRouteCalls:number;routesRemaining:number|null;fits:boolean}>(),estimateFresh=ref(false)
const resolveReasons=computed(()=>[...(!geocodeAvailable.value?['地址解析额度尚未开放或已用完']:[]),...(!city.value?['请选择车辆停放城市']:[]),...(!district.value?['请选择车辆停放区县']:[]),...(detail.value.trim().length<4?['请填写至少4个字符的街道、门牌或明确地标']:[])])
async function loadBudget(){try{budget.value=(await httpClient.get('/maps/locations/budget')).data.data}catch(e){budget.value={callsEnabled:false,reason:'无法确认地图调用状态，请刷新服务状态'};error.value=e instanceof Error?e.message:'服务状态读取失败'}}
let generation=0
const shopCity=ref(''),recommendation=ref<Recommendation>(),exclusions=ref<Exclusion[]>([]),excludedPage=ref(1),excludedTotal=ref(0)
const context=ref<{accidentRegion:string;vehicleBrand:string;vehicleModel:string}>()
let queryKey='',queryIdentity=''
function clearRecommendation(){emit('pending',true);recommendation.value=undefined;estimate.value=undefined;exclusions.value=[];excludedPage.value=1;excludedTotal.value=0;emit('choose',undefined)}
async function regions(kind:'city'|'district'){
  if(busy.value)return
  busy.value=true;error.value='';const requestedCity=city.value
  try{const page=kind==='city'?cityPage.value:districtPage.value;const response=(await httpClient.get('/maps/locations/regions',{params:{page,size:20,...(kind==='district'?{cityId:Number(city.value)}:{})}})).data.data as {total:number;records:Region[]}
    if(kind==='district'&&requestedCity!==city.value)return
    if(kind==='city'){cities.value.push(...response.records);cityTotal.value=response.total;cityPage.value++}else{districts.value.push(...response.records);districtTotal.value=response.total;districtPage.value++}
  }catch(e){error.value=e instanceof Error?e.message:'区域加载失败'}finally{busy.value=false}
}
watch(city,async()=>{generation++;district.value='';districts.value=[];districtPage.value=1;districtTotal.value=0;if(city.value)await regions('district')})
watch([city,district,detail],()=>{generation++;confirmed.value=undefined;candidates.value=[];clearRecommendation()})
watch(shopCity,()=>{generation++;clearRecommendation()})
async function resolve(){
  if(busy.value||!geocodeAvailable.value||!district.value||detail.value.trim().length<4)return
  busy.value=true;error.value='';confirmed.value=undefined;candidates.value=[];const requestGeneration=generation
  try{const response=(await httpClient.post('/maps/locations/resolve',{districtId:Number(district.value),detail:detail.value.trim()})).data.data as {token:string;candidate:Candidate}[];if(requestGeneration===generation)candidates.value=response.map(item=>({...item.candidate,token:item.token}))}
  catch(e){error.value=e instanceof Error?e.message:'地址解析失败，未自动重试'}finally{busy.value=false;await loadBudget()}
}
async function confirm(item:Candidate){if(busy.value)return;busy.value=true;error.value='';try{await httpClient.post(`/maps/locations/${item.token}/confirm`);confirmed.value=item;clearRecommendation();const selectedCity=cities.value.find(row=>String(row.id)===city.value);const selectedDistrict=districts.value.find(row=>String(row.id)===district.value);emit('confirmed',{districtId:Number(district.value),address:`广东省${selectedCity?.name??''}${selectedDistrict?.name??''}${detail.value.trim()}`})}catch(e){error.value=e instanceof Error?e.message:'位置确认失败'}finally{busy.value=false}}
async function excluded(page=1){if(!props.caseId)return;const value=(await httpClient.get(`/maps/cases/${props.caseId}/recommendation-exclusions`,{params:{page,size:20,shopCityId:shopCity.value?Number(shopCity.value):undefined}})).data.data as {total:number;records:Exclusion[]};exclusions.value=value.records;excludedPage.value=page;excludedTotal.value=value.total}
async function query(fresh=false){
  if(busy.value||!drivingAvailable.value||!confirmed.value||!props.caseId)return
  busy.value=true;error.value='';estimate.value=undefined;const requestGeneration=generation
  try{await loadBudget();const value=(await httpClient.get(`/maps/cases/${props.caseId}/recommendation-estimate`,{params:{shopCityId:shopCity.value?Number(shopCity.value):undefined}})).data.data;if(requestGeneration===generation){estimate.value=value;estimateFresh.value=fresh}}
  catch(e){error.value=e instanceof Error?e.message:'候选及预计用量核对失败；未调用高德'}finally{busy.value=false}
}
async function performQuery(){
  if(busy.value||!drivingAvailable.value||!confirmed.value||!props.caseId||!estimate.value?.fits||estimate.value.candidates===0)return
  const fresh=estimateFresh.value;estimate.value=undefined
  busy.value=true;error.value='';emit('choose',undefined)
  const identity=`${confirmed.value.token}:${shopCity.value}`
  if(fresh||identity!==queryIdentity||!queryKey){queryKey=crypto.randomUUID();queryIdentity=identity}
  try{recommendation.value=(await httpClient.post(`/maps/cases/${props.caseId}/recommendations`,{locationToken:confirmed.value.token,shopCityId:shopCity.value?Number(shopCity.value):null},{timeout:75000,headers:{'Idempotency-Key':queryKey}})).data.data as Recommendation;const nearest=recommendation.value.shops.records.find(row=>row.status==='READY'&&row.metres!=null);if(nearest)emit('choose',{shopId:nearest.shopId,queryId:recommendation.value.queryId,name:nearest.name});await excluded();await loadBudget()}
  catch(e){error.value=e instanceof Error?e.message:'附近网点查询失败，未自动重试'}finally{busy.value=false;await loadBudget()}
}
async function resultPage(page:number){if(!recommendation.value||busy.value)return;busy.value=true;error.value='';try{recommendation.value=(await httpClient.get(`/maps/recommendations/${recommendation.value.queryId}`,{params:{page,size:20}})).data.data as Recommendation}catch(e){error.value=e instanceof Error?e.message:'分页读取失败'}finally{busy.value=false}}
async function excludedPageChange(page:number){if(busy.value)return;busy.value=true;error.value='';try{await excluded(page)}catch(e){error.value=e instanceof Error?e.message:'资料提示加载失败'}finally{busy.value=false}}
onMounted(async()=>{await loadBudget();await regions('city');if(props.caseId)try{context.value=(await httpClient.get(`/maps/cases/${props.caseId}/recommendation-context`)).data.data}catch(e){error.value=e instanceof Error?e.message:'案件资格上下文加载失败'}})
</script>
<template><section class="parking" :aria-busy="busy"><h3>车辆当前停放位置 · 高德 Web 服务</h3><button type="button" :disabled="busy" @click="loadBudget">刷新地图服务状态（不新增地图调用）</button><p v-if="budget?.callsEnabled===false" role="status">{{ budget.reason }}。可查看已有结果，不会自动调用高德。</p><div v-if="budget?.operations?.length" class="map-allowance" role="status"><strong>本轮开发测试额度（不是高德账户余额）</strong><p v-for="row in budget.operations" :key="row.operation">{{ operationName[row.operation]??row.operation }}：剩余 {{ row.remaining }} / {{ row.total }} 次，已尝试 {{ row.used }} 次，查询预留 {{ row.reserved }} 次。</p><p>失败、超时计次；页面刷新和服务重启不补额度。只有明确点击解析或确认计算才请求高德，无自动重试。</p></div><p role="note">若网点名称含【测试／非真实合作方】，仅用于隔离联调；学校不是合作维修商，不代表具备维修能力。</p><p>事故区域决定服务资格；这里填写车辆现在的位置，仅用于“车辆→网点”的送店距离，不是上门接车距离，不修改事故地址。</p>
  <p v-if="context">案件事故区域：{{ context.accidentRegion }} · 车型：{{ context.vehicleBrand }} / {{ context.vehicleModel }}。下方为独立的车辆当前停放位置。</p>
  <fieldset :disabled="busy"><label>省份<input value="广东省" readonly></label><label>城市<select v-model="city" aria-label="车辆停放城市"><option value="">请选择</option><option v-for="item in cities" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label><button v-if="cities.length<cityTotal" type="button" @click="regions('city')">加载更多城市</button>
  <label>区县<select v-model="district" aria-label="车辆停放区县"><option value="">请选择</option><option v-for="item in districts" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label><button v-if="districts.length<districtTotal" type="button" @click="regions('district')">加载更多区县</button>
  <label>街道、门牌或明确地标<input v-model="detail" maxlength="200" aria-label="车辆停放详细地址"></label><button type="button" :disabled="!geocodeAvailable||!district||detail.trim().length<4" @click="resolve">{{ busy?'处理中，请稍候…':'解析停放位置（最多1次地址解析）' }}</button></fieldset>
  <ul v-if="!busy&&resolveReasons.length" class="map-prerequisites" aria-label="地址解析尚缺条件"><li v-for="reason in resolveReasons" :key="reason">{{ reason }}</li></ul>
  <p v-if="error" role="alert">{{ error }}</p><p v-if="!busy&&cities.length===0">尚未配置启用的广东行政区域，请管理员维护；不会用城市中心代替车辆位置。</p>
  <ul v-if="candidates.length"><li v-for="(item,index) in candidates" :key="index"><strong>{{ item.address }}</strong><p>{{ item.province }} / {{ item.city }} / {{ item.district }} · {{ item.adcode }} · 匹配级别：{{ item.level }}</p>
  <button v-if="item.status==='REQUIRES_CONFIRMATION'" :data-location-token="item.token" type="button" :disabled="busy" @click="confirm(item)">确认这是当前车辆停放位置{{ item.mock?'（Mock合成）':'' }}</button><span v-else>{{ item.status==='REGION_MISMATCH'?'与所选广东城市/区县不符，不能使用':'定位过粗，请补充详细地址' }}</span></li></ul>
  <p v-if="confirmed" role="status">已确认：{{ confirmed.address }}（修改地址即失效，确认最多保留10分钟）</p>
  <label>明确查询范围：网点所在城市<select v-model="shopCity" :disabled="busy" aria-label="推荐网点所在城市"><option value="">全部广东合作网点</option><option v-for="item in cities" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label>
  <button type="button" :disabled="!drivingAvailable||busy||!confirmed||!props.caseId" @click="query(false)">{{ busy?'处理中，请稍候…':'查询附近可服务网点（先核对用量）' }}</button><button v-if="recommendation||error" type="button" :disabled="!drivingAvailable||busy||!confirmed" @click="query(true)">明确重新计算（先核对用量）</button>
  <p v-if="!busy&&!confirmed">请先解析并确认车辆当前停放位置，再核对候选及路线用量。</p>
  <div v-if="estimate" role="status"><p>本次范围内合格候选 {{ estimate.candidates }} 个，最多消耗 {{ estimate.maximumRouteCalls }} 次驾车路线请求<span v-if="estimate.routesRemaining!=null">；当前剩余 {{ estimate.routesRemaining }} 次</span>。</p><p v-if="estimate.candidates===0">没有符合当前服务区域、启用、车型和有效位置条件的网点；尚未调用高德路线。</p><p v-else-if="!estimate.fits">候选超过单次上限或剩余路线额度不足，未调用高德。请明确缩小网点查询范围或申请追加授权；不要修改事故事实来匹配网点。</p><button v-else type="button" :disabled="busy||!drivingAvailable" @click="performQuery">确认计算道路距离（最多 {{ estimate.maximumRouteCalls }} 次）</button></div>
  <div v-if="recommendation"><p>{{ recommendation.mock?'Mock演示：不提供真实道路距离':'高德地图驾车规划v5 · 策略32（高德推荐）' }} · 车辆→网点。按主方案道路距离排序，不是理论最短距离、实际里程、骑行距离或拖车报价。</p><p>覆盖：{{ recommendation.scope }}。全部候选计算后分页；{{ recommendation.complete?'距离计算完成':'部分或全部距离未知，未完成全体真实距离排序' }}。推荐有效至 {{ new Date(recommendation.expiresAt).toLocaleTimeString() }}。</p>
  <p v-if="recommendation.shops.total===0">没有符合服务区域、启用、车型及省内位置条件的网点。</p>
  <table v-else><thead><tr><th>合作网点/地址</th><th>资格</th><th>驾车距离/预计时间</th><th>计算状态/时间</th><th>选择</th></tr></thead><tbody><tr v-for="item in recommendation.shops.records" :key="item.shopId"><td>{{ item.name }}<br>{{ item.address }}</td><td>{{ item.qualification }}</td><td>{{ item.metres==null?'距离未知':`${(item.metres/1000).toFixed(2)} km` }}<br>{{ item.seconds==null?'':`约${Math.ceil(item.seconds/60)}分钟` }}</td><td>{{ mapStatus(item.status) }}<br>{{ item.calculatedAt?new Date(item.calculatedAt).toLocaleString():'' }}</td><td><button :aria-pressed="props.selectedShopId===item.shopId" :disabled="busy||item.status!=='READY'" type="button" @click="emit('choose',{shopId:item.shopId,queryId:recommendation.queryId,name:item.name})">{{ props.selectedShopId===item.shopId?'已选网点':'选择此网点' }}</button></td></tr></tbody></table>
  <button type="button" :disabled="busy||recommendation.shops.page<=1" @click="resultPage(recommendation.shops.page-1)">上一页</button><span>第{{ recommendation.shops.page }}页，共{{ recommendation.shops.total }}个</span><button type="button" :disabled="busy||recommendation.shops.page*20>=recommendation.shops.total" @click="resultPage(recommendation.shops.page+1)">下一页</button>
  <p>选择只填入原派单控件，仍需点击原派单/改派按钮；提交时重新校验资格。距离未知可人工处理，不会自动派单。</p>
  <details v-if="excludedTotal"><summary>未进入推荐的资料提示（{{ excludedTotal }}个）</summary><p v-for="item in exclusions" :key="item.id">{{ item.name }}：{{ mapStatus(item.status) }}</p><button :disabled="busy||excludedPage<=1" @click="excludedPageChange(excludedPage-1)">上一页</button><button :disabled="busy||excludedPage*20>=excludedTotal" @click="excludedPageChange(excludedPage+1)">下一页</button></details></div>
  <p>本入口没有加载交互地图，也没有调用 POI 搜索。原人工派单仍可使用。</p>
</section></template>
<style scoped>.parking table{width:100%;border-collapse:collapse;font-size:12px}.parking th,.parking td{padding:12px 8px;text-align:left;border-bottom:1px solid #dce5e4;overflow-wrap:anywhere}.parking button[aria-pressed="true"]{background:#345d4d;color:white}.parking{padding:16px;border:1px solid var(--color-border);border-radius:8px;margin:16px 0}.parking fieldset{border:0;padding:0;display:flex;flex-wrap:wrap;gap:12px}.parking label{display:flex;flex-direction:column;gap:6px}.parking input,.parking select,.parking button{min-height:36px}.parking p{color:var(--color-text-secondary)}.parking li{margin:12px 0}</style>
