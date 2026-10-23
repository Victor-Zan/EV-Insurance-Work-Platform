<script setup lang="ts">
import {computed,inject,nextTick,onMounted,ref,watch} from 'vue'
import {httpClient} from '@/shared/http/client'
import {mapStatus} from './map-recommendation'
import {mapPreviewClientKey} from './map-preview-client'
import DispatchMapCanvas from './DispatchMapCanvas.vue'
import DispatchCandidateList from './DispatchCandidateList.vue'
import AmapLocationMap from './AmapLocationMap.vue'
const props=defineProps<{caseId?:string;selectedShopId?:number;initialAddress?:string;previewOnly?:boolean}>()
const emit=defineEmits<{choose:[value:{shopId:number;queryId:string;name:string}|undefined];pending:[value:boolean];confirmed:[value:{districtId:number;address:string}]}>()
const injectedPreviewClient=inject(mapPreviewClientKey,undefined)
if(props.previewOnly&&!injectedPreviewClient)throw new Error('合成预览必须提供隔离传输，不允许回退真实接口')
const client=props.previewOnly?injectedPreviewClient!:httpClient
interface Region {id:number;name:string;adcode:string}
interface Candidate {token:string;address:string;province:string;city:string;district:string;adcode:string;level:string;status:string;mock:boolean;point:{longitude:number;latitude:number}}
interface ShopRow {shopId:number;name:string;address:string;qualification:string;status:string;metres:number|null;seconds:number|null;calculatedAt:string|null;mock:boolean}
interface Recommendation {queryId:string;provider:string;mock:boolean;scope:string;complete:boolean;expiresAt:string;shops:{page:number;size:number;total:number;records:ShopRow[]}}
interface Exclusion {id:number;name:string;address:string;status:string}
const cities=ref<Region[]>([]),districts=ref<Region[]>([]),city=ref(''),district=ref(''),detail=ref(props.initialAddress??''),candidates=ref<Candidate[]>([]),confirmed=ref<Candidate>(),error=ref(''),busy=ref(false),cityPage=ref(1),districtPage=ref(1),cityTotal=ref(0),districtTotal=ref(0)
const budget=ref<{callsEnabled:boolean;reason:string;operations?:{operation:string;total:number;used:number;reserved:number;remaining:number}[]}>()
const operationName:Record<string,string>={GEOCODE:'完整地址解析',REVERSE:'逆地理核验',DRIVING:'驾车路线',POI_SEARCH:'地点名称搜索'}
const searchMode=ref<'address'|'place'>('address'),placePage=ref(1),placeHasMore=ref(false),searchAttempted=ref(false),candidatePreview=ref<Candidate>()
const placeAvailable=computed(()=>!props.previewOnly&&Boolean(budget.value?.callsEnabled&&budget.value.operations?.some(row=>row.operation==='POI_SEARCH'&&row.remaining>0)))
const searchAvailable=computed(()=>searchMode.value==='place'?placeAvailable.value:geocodeAvailable.value)
const searchMaximum=computed(()=>searchMode.value==='place'?80:200)
function operationAvailable(operation:string){if(!budget.value?.callsEnabled)return false;const row=budget.value.operations?.find(item=>item.operation===operation);return !row||row.remaining>0}
const selectedPoint=ref<{longitude:number;latitude:number}>()
const reverseAvailable=computed(()=>operationAvailable('REVERSE'))
const geocodeAvailable=computed(()=>operationAvailable('GEOCODE')),drivingAvailable=computed(()=>operationAvailable('DRIVING'))
const estimate=ref<{candidates:number;maximumRouteCalls:number;routesRemaining:number|null;fits:boolean}>(),estimateFresh=ref(false)
const resolveReasons=computed(()=>[...(!searchAvailable.value?[searchMode.value==='place'?'地点搜索独立额度尚未开放或已用完':'地址解析额度尚未开放或已用完']:[]),...(!city.value?['请选择车辆停放城市']:[]),...(!district.value?['请选择车辆停放区县']:[]),...(detail.value.trim().length<4?['请填写至少4个字符的街道、门牌或明确地标']:[]),...(detail.value.trim().length>searchMaximum.value?[`当前搜索方式最多${searchMaximum.value}个字符，请修改后再搜索`]:[])])
async function loadBudget(){try{budget.value=(await client.get('/maps/locations/budget')).data.data}catch(e){budget.value={callsEnabled:false,reason:'无法确认地图调用状态，请刷新服务状态'};error.value=e instanceof Error?e.message:'服务状态读取失败'}}
let generation=0
const shopCity=ref(''),recommendation=ref<Recommendation>(),exclusions=ref<Exclusion[]>([]),excludedPage=ref(1),excludedTotal=ref(0)
const context=ref<{accidentRegion:string;vehicleBrand:string;vehicleModel:string}>()
let queryKey='',queryIdentity=''
const previewMarkers=computed(()=>props.previewOnly?(recommendation.value?.shops.records??[]).map((row,index)=>({id:row.shopId,name:row.name,x:22+index*25,y:36+(index%2)*35,selected:props.selectedShopId===row.shopId})):[])
function selectShop(id:number){const query=recommendation.value,row=query?.shops.records.find(item=>item.shopId===id);if(query&&new Date(query.expiresAt).getTime()<=Date.now()){error.value='本次推荐已过期，请明确重新查询；未自动重算';emit('choose',undefined);return}if(!busy.value&&query&&row?.status==='READY')emit('choose',{shopId:id,queryId:query.queryId,name:row.name})}
function pickRealPoint(point:{longitude:number;latitude:number}){if(props.previewOnly||busy.value)return;generation++;selectedPoint.value=point;confirmed.value=undefined;candidates.value=[];candidatePreview.value=undefined;placeHasMore.value=false;searchAttempted.value=false;error.value='';clearRecommendation()}
function previewCandidate(item:Candidate){if(!busy.value)candidatePreview.value=item}
async function verifyPoint(){
 if(props.previewOnly||busy.value||!selectedPoint.value||!district.value||!reverseAvailable.value)return
 busy.value=true;error.value='';const requestGeneration=generation
 try{const choices=(await client.post('/maps/locations/point',{districtId:Number(district.value),...selectedPoint.value},{timeout:15000})).data.data as {token:string;candidate:Candidate}[];if(requestGeneration===generation)candidates.value=choices.map(item=>({...item.candidate,token:item.token}))}
 catch(e){error.value=e instanceof Error?e.message:'选点核验失败；未自动重试'}finally{busy.value=false;await loadBudget()}
}
async function previewPick(point:{x:number;y:number}){if(!props.previewOnly||busy.value)return;if(!city.value||!district.value){error.value='请先选择合成城市和区县，再在模拟网格选点';return}detail.value=`合成测试入口（网格 ${Math.round(point.x)} / ${Math.round(point.y)}）`;await nextTick();await resolve()}

function clearRecommendation(){emit('pending',!confirmed.value);recommendation.value=undefined;estimate.value=undefined;exclusions.value=[];excludedPage.value=1;excludedTotal.value=0;emit('choose',undefined)}
async function regions(kind:'city'|'district'){
  if(busy.value)return
  busy.value=true;error.value='';const requestedCity=city.value
  try{const page=kind==='city'?cityPage.value:districtPage.value;const response=(await client.get('/maps/locations/regions',{params:{page,size:20,...(kind==='district'?{cityId:Number(city.value)}:{})}})).data.data as {total:number;records:Region[]}
    if(kind==='district'&&requestedCity!==city.value)return
    if(kind==='city'){cities.value.push(...response.records);cityTotal.value=response.total;cityPage.value++}else{districts.value.push(...response.records);districtTotal.value=response.total;districtPage.value++}
  }catch(e){error.value=e instanceof Error?e.message:'区域加载失败'}finally{busy.value=false}
}
watch(city,async()=>{generation++;district.value='';districts.value=[];districtPage.value=1;districtTotal.value=0;if(city.value)await regions('district')})
watch([city,district,detail,searchMode],()=>{generation++;confirmed.value=undefined;selectedPoint.value=undefined;candidates.value=[];candidatePreview.value=undefined;placePage.value=1;placeHasMore.value=false;searchAttempted.value=false;error.value='';clearRecommendation()})
watch(shopCity,()=>{generation++;clearRecommendation()})
async function resolve(){
  if(searchMode.value==='place'){await searchPlaces(1);return}
  if(busy.value||!geocodeAvailable.value||!district.value||detail.value.trim().length<4||detail.value.trim().length>200)return
  searchAttempted.value=false;candidatePreview.value=undefined
  busy.value=true;error.value='';confirmed.value=undefined;selectedPoint.value=undefined;candidates.value=[];clearRecommendation();const requestGeneration=generation
  try{const response=(await client.post('/maps/locations/resolve',{districtId:Number(district.value),detail:detail.value.trim()},{timeout:15000})).data.data as {token:string;candidate:Candidate}[];if(requestGeneration===generation)candidates.value=response.map(item=>({...item.candidate,token:item.token}))}
  catch(e){error.value=e instanceof Error?e.message:'地址解析失败，未自动重试'}finally{busy.value=false;await loadBudget()}
}
async function searchPlaces(page:number){
 if(busy.value||!placeAvailable.value||!district.value||detail.value.trim().length<4||detail.value.trim().length>80||page<1||page>20)return
 busy.value=true;error.value='';confirmed.value=undefined;selectedPoint.value=undefined;candidatePreview.value=undefined;candidates.value=[];placeHasMore.value=false;searchAttempted.value=false;clearRecommendation();const requestGeneration=generation
 try{const result=(await client.post('/maps/locations/places',{districtId:Number(district.value),keywords:detail.value.trim(),page},{timeout:15000})).data.data as {page:number;hasMore:boolean;records:{token:string;candidate:Candidate}[]};if(requestGeneration!==generation)return;candidates.value=result.records.map(item=>({...item.candidate,token:item.token}));placePage.value=result.page;placeHasMore.value=result.hasMore;searchAttempted.value=true}
 catch(e){error.value=e instanceof Error?e.message:'地点名称搜索失败；未自动切换接口或重试，原输入保留'}finally{busy.value=false;await loadBudget()}
}
async function confirm(item:Candidate){if(busy.value)return;const requestGeneration=generation;busy.value=true;error.value='';try{await client.post(`/maps/locations/${item.token}/confirm`);if(requestGeneration!==generation)return;confirmed.value=item;clearRecommendation();emit('confirmed',{districtId:Number(district.value),address:item.address})}catch(e){error.value=e instanceof Error?e.message:'位置确认失败'}finally{busy.value=false}}
async function excluded(page=1){if(!props.caseId)return;const value=(await client.get(`/maps/cases/${props.caseId}/recommendation-exclusions`,{params:{page,size:20,shopCityId:shopCity.value?Number(shopCity.value):undefined}})).data.data as {total:number;records:Exclusion[]};exclusions.value=value.records;excludedPage.value=page;excludedTotal.value=value.total}
async function query(fresh=false){
  if(busy.value||!drivingAvailable.value||!confirmed.value||!props.caseId)return
  busy.value=true;error.value='';estimate.value=undefined;const requestGeneration=generation
  try{await loadBudget();const value=(await client.get(`/maps/cases/${props.caseId}/recommendation-estimate`,{params:{shopCityId:shopCity.value?Number(shopCity.value):undefined}})).data.data;if(requestGeneration===generation){estimate.value=value;estimateFresh.value=fresh}}
  catch(e){error.value=e instanceof Error?e.message:'候选及预计用量核对失败；未调用高德'}finally{busy.value=false}
}
async function performQuery(){
  if(busy.value||!drivingAvailable.value||!confirmed.value||!props.caseId||!estimate.value?.fits||estimate.value.candidates===0)return
  const fresh=estimateFresh.value,requestGeneration=generation;estimate.value=undefined
  busy.value=true;error.value='';recommendation.value=undefined;emit('choose',undefined)
  const identity=`${confirmed.value.token}:${shopCity.value}`
  if(fresh||identity!==queryIdentity||!queryKey){queryKey=crypto.randomUUID();queryIdentity=identity}
  try{const result=(await client.post(`/maps/cases/${props.caseId}/recommendations`,{locationToken:confirmed.value.token,shopCityId:shopCity.value?Number(shopCity.value):null},{timeout:75000,headers:{'Idempotency-Key':queryKey}})).data.data as Recommendation;if(requestGeneration!==generation)return;recommendation.value=result;const nearest=recommendation.value.shops.records.find(row=>row.status==='READY'&&row.metres!=null);if(nearest&&new Date(result.expiresAt).getTime()>Date.now())emit('choose',{shopId:nearest.shopId,queryId:result.queryId,name:nearest.name});await excluded();await loadBudget()}
  catch(e){error.value=e instanceof Error?e.message:'附近网点查询失败，未自动重试'}finally{busy.value=false;await loadBudget()}
}
async function resultPage(page:number){if(!recommendation.value||busy.value)return;busy.value=true;error.value='';try{recommendation.value=(await client.get(`/maps/recommendations/${recommendation.value.queryId}`,{params:{page,size:20}})).data.data as Recommendation}catch(e){error.value=e instanceof Error?e.message:'分页读取失败'}finally{busy.value=false}}
async function excludedPageChange(page:number){if(busy.value)return;busy.value=true;error.value='';try{await excluded(page)}catch(e){error.value=e instanceof Error?e.message:'资料提示加载失败'}finally{busy.value=false}}
onMounted(async()=>{await loadBudget();await regions('city');if(props.caseId)try{context.value=(await client.get(`/maps/cases/${props.caseId}/recommendation-context`)).data.data}catch(e){error.value=e instanceof Error?e.message:'案件资格上下文加载失败'}})
</script>
<template>
 <section class="parking dispatch-workspace" :aria-busy="busy">
  <article class="dispatch-card location-card">
   <header class="dispatch-card-header"><div><span class="step-label">01 · 确认位置</span><h3>车辆当前停放位置</h3></div><span class="location-state" :class="{ready:confirmed}">{{ confirmed?'已确认':'待核对' }}</span></header>
   <p v-if="previewOnly" class="preview-notice" role="note">Mock交互预览：所有地址、网点与排序数值均为合成资料，不调用高德、不提交正式派单。</p>
   <p class="section-hint">停车位置只用于送店距离；事故服务区县单独核实，不随选点改变。</p>
   <AmapLocationMap v-if="!previewOnly" :point="selectedPoint??confirmed?.point??candidatePreview?.point" :confirmed="Boolean(confirmed)" :disabled="busy" :shops="recommendation?.shops.records" :selected-shop-id="props.selectedShopId" @pick="pickRealPoint" @choose="selectShop"/>
   <DispatchMapCanvas v-else :preview="previewOnly" :confirmed="Boolean(confirmed)" :disabled="busy" :markers="previewMarkers" :label="confirmed?'已确认：'+confirmed.address:candidates.length?'待核对：'+(candidates[0]?.address??''):'尚未确认停车位置，请搜索地址或在合成预览中选点'" @pick="previewPick" @choose="selectShop"/>
   <div v-if="selectedPoint&&!previewOnly" class="query-confirmation"><p>暂选坐标（GCJ-02）：{{ selectedPoint.longitude.toFixed(6) }}，{{ selectedPoint.latitude.toFixed(6) }}。尚未确认车辆位置。</p><button type="button" class="primary-action" :disabled="busy||!district||!reverseAvailable" @click="verifyPoint">核验所选位置（最多1次逆地理核验）</button><p v-if="!district">请先选择停车城市和区县。</p><p v-else-if="!reverseAvailable">逆地理核验额度未开放或已用完。</p></div>
   <fieldset class="location-search" :disabled="busy"><legend class="sr-only">搜索车辆停放地址</legend>
    <label v-if="!previewOnly">搜索方式<select v-model="searchMode" aria-label="停车位置搜索方式"><option value="address">完整地址（街道、门牌）</option><option value="place">地点名称（学校、校门等）</option></select></label>
    <label>城市<select v-model="city" aria-label="车辆停放城市"><option value="">请选择广东城市</option><option v-for="item in cities" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label><button v-if="cities.length<cityTotal" type="button" @click="regions('city')">更多城市</button>
    <label>区县<select v-model="district" aria-label="车辆停放区县"><option value="">请选择区县</option><option v-for="item in districts" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label><button v-if="districts.length<districtTotal" type="button" @click="regions('district')">更多区县</button>
    <label class="detail-field">{{ searchMode==='place'?'地点名称':'详细地址' }}<input v-model="detail" :maxlength="searchMaximum" aria-label="车辆停放详细地址" :placeholder="searchMode==='place'?'学校、校门或明确地标名称':'街道、门牌或明确入口'"></label><button type="button" class="primary-action" :title="previewOnly?'仅合成预览':searchMode==='place'?'最多1次地点名称搜索，失败或超时也计次':'最多1次地址解析，失败或超时也计次'" :disabled="!searchAvailable||!district||detail.trim().length<4||detail.trim().length>searchMaximum" @click="resolve">{{ busy?'处理中…':searchMode==='place'?'搜索地点':'搜索地址' }}</button>
   </fieldset>
   <p v-if="searchMode==='place'" class="section-hint">仅搜索所选广东区县的停车地点，每页最多10个；不代表合作网点。请核对校区、校门与道路入口，校园中心不能直接当作停车入口。查看候选不会额外查询，确认后才使用该位置。</p>
   <ul v-if="!busy&&resolveReasons.length" class="map-prerequisites" aria-label="地址解析尚缺条件"><li v-for="reason in resolveReasons" :key="reason">{{ reason }}</li></ul>
   <p v-if="!busy&&cities.length===0" class="inline-warning">暂无启用的广东城市资料，请联系管理员维护。</p>
   <ul v-if="candidates.length&&!confirmed" class="location-candidates" aria-label="待核对的地址候选"><li v-for="item in candidates" :key="item.token"><div><strong>{{ item.address }}</strong><small>{{ item.city }} / {{ item.district }} · {{ item.level }}</small></div><button type="button" :disabled="busy" @click="previewCandidate(item)">{{ candidatePreview?.token===item.token?'正在地图查看':'在地图上查看' }}</button><button v-if="item.status==='REQUIRES_CONFIRMATION'" :data-location-token="item.token" type="button" class="primary-action" :disabled="busy" @click="confirm(item)">{{ item.mock?'确认模拟停放位置':'确认停车位置' }}</button><span v-else class="inline-warning">{{ item.status==='REGION_MISMATCH'?'城市或区县不符，不能使用':'定位不够准确，请补充地址' }}</span></li></ul>
   <p v-if="searchMode==='place'&&searchAttempted&&!candidates.length" role="status" class="inline-warning">本页未找到地点；未自动扩大范围或改用其他接口。可修改名称、明确选择完整地址，或在地图上选点。</p>
   <div v-if="searchMode==='place'&&searchAttempted&&!confirmed" class="candidate-toolbar"><span>地点候选第{{ placePage }}页；翻页会新增1次地点搜索调用。</span><button type="button" :disabled="busy||placePage<=1||!placeAvailable" @click="searchPlaces(placePage-1)">上一页地点</button><button type="button" :disabled="busy||!placeHasMore||!placeAvailable" @click="searchPlaces(placePage+1)">下一页地点</button></div>
   <p v-if="confirmed" class="confirmed-note" role="status">位置已确认；修改地址后需重新核对。仅在本次确认有效期内使用。</p>
  </article>
  <p v-if="error" class="dispatch-error" role="alert">{{ error }}</p>
  <article class="dispatch-card candidates-card">
   <header class="dispatch-card-header"><div><span class="step-label">02 · 选择网点</span><h3>智能分拨候选</h3></div><button type="button" class="primary-action" :disabled="!drivingAvailable||busy||!confirmed||!props.caseId" @click="query(Boolean(recommendation))">{{ busy?'处理中…':recommendation?'重新查询网点':'查询推荐网点' }}</button></header>
   <div class="candidate-toolbar"><p class="section-hint">先筛选服务资格，再按车辆→网点的道路距离排序，最终由你选择。</p><label>查询范围<select v-model="shopCity" :disabled="busy" aria-label="推荐网点所在城市"><option value="">全部广东合作网点</option><option v-for="item in cities" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label></div>
   <p v-if="!busy&&!confirmed" class="inline-warning">请先确认上方停车位置。</p><p v-else-if="!drivingAvailable" class="inline-warning">道路查询额度未开放或已用完，可使用下方人工派单。</p>
   <div v-if="estimate" class="query-confirmation" role="status"><p>符合条件的候选 {{ estimate.candidates }} 家，本次最多计算 {{ estimate.maximumRouteCalls }} 条路线<span v-if="estimate.routesRemaining!=null">（剩余 {{ estimate.routesRemaining }} 次）</span>。</p><p v-if="estimate.candidates===0">暂无合格候选。请核对网点服务区域、车型与位置资料；尚未调用高德路线。</p><p v-else-if="!estimate.fits">候选超出单次上限或剩余额度。请明确缩小查询范围或申请预算；不要修改事故信息。</p><button v-else type="button" class="primary-action" :disabled="busy||!drivingAvailable" @click="performQuery">{{ previewOnly?'确认模拟计算':'确认计算道路距离' }}</button></div>
   <p v-if="recommendation" class="result-caption">{{ previewOnly?'Mock合成排序数值，非真实道路距离':recommendation.mock?'Mock模式，不提供真实道路距离':'高德驾车规划 · 策略32（高德推荐）' }} · {{ recommendation.complete?'本次候选计算完成':'部分距离未知，未完成全部排序' }}<br>范围：{{ recommendation.scope }}</p>
   <DispatchCandidateList :rows="recommendation?.shops.records??[]" :selected-shop-id="props.selectedShopId" :busy="busy" :preview="previewOnly" :complete="recommendation?.complete" :page="recommendation?.shops.page" :total="recommendation?.shops.total" :expires-at="recommendation?.expiresAt" @choose="selectShop" @page="resultPage"/>
   <p class="submission-hint">03 · 核对所选网点后，点击下方原派单按钮。选择或推荐均不会自动提交。</p>
  </article>
  <details class="map-service-details"><summary>服务状态与高级信息</summary><button type="button" :disabled="busy" @click="loadBudget">刷新地图服务状态（不新增地图调用）</button><p v-if="budget?.callsEnabled===false" role="status">{{ budget.reason }}。不会自动调用高德。</p><div v-if="budget?.operations?.length" class="map-allowance" role="status"><strong>{{ previewOnly?'合成预览用量（不关联网站真实预算）':'本轮开发测试额度（不是高德账户余额）' }}</strong><p v-for="row in budget.operations" :key="row.operation">{{ operationName[row.operation]??row.operation }}：剩余 {{ row.remaining }} / {{ row.total }} 次，已尝试 {{ row.used }} 次，查询预留 {{ row.reserved }} 次。</p><p>失败、超时计次；刷新和重启不补次数，无自动重试。</p></div><p>省份固定广东省。位置确认最多10分钟，推荐以服务端有效期为准；过期需明确重新查询，提交仍由后端复核。</p><p v-if="context">事故服务区域：{{ context.accidentRegion }} · 车型：{{ context.vehicleBrand }} / {{ context.vehicleModel }}</p><p v-if="recommendation">供应商：{{ recommendation.provider }} · 方向：车辆→网点 · 推荐有效至 {{ new Date(recommendation.expiresAt).toLocaleTimeString() }}。道路规划距离不是理论最短路、实际里程或拖车报价。</p><p>若名称含【测试／非真实合作方】，仅用于隔离联调；学校不是合作维修商，不代表具备维修能力。</p><p>真实底图仅在明确点击加载后请求在线资源；地图点击/拖动不自动核验。选点核验、完整地址、地点名称、道路计算分别计入REVERSE/GEOCODE/POI_SEARCH/DRIVING独立额度；未使用设备定位，地点搜索不会创建合作网点。</p>
   <details v-if="excludedTotal"><summary>未进入推荐的资料提示（{{ excludedTotal }}个）</summary><p v-for="item in exclusions" :key="item.id">{{ item.name }}：{{ mapStatus(item.status) }}</p><button :disabled="busy||excludedPage<=1" @click="excludedPageChange(excludedPage-1)">上一页</button><button :disabled="busy||excludedPage*20>=excludedTotal" @click="excludedPageChange(excludedPage+1)">下一页</button></details>
  </details>
 </section>
</template>
<style scoped>
.parking{margin:16px 0;display:grid;gap:16px}.dispatch-card{border:1px solid #dce4df;border-radius:11px;background:#fff;overflow:hidden;padding:20px}.dispatch-card-header{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:14px}.dispatch-card-header h3{margin:4px 0 0;font-size:18px}.step-label{font-size:11px;letter-spacing:1px;color:#7c8b81}.section-hint{font-size:12px;color:#718078;line-height:1.7;margin:0 0 12px}.location-state{font-size:12px;padding:5px 10px;border-radius:20px;background:#f3f3ed;color:#85734e}.location-state.ready{background:#ebf5ee;color:#367451}.location-search{border:0;padding:0;display:flex;align-items:flex-end;gap:10px;flex-wrap:wrap;margin:17px 0 0}.parking label{display:flex;flex-direction:column;gap:6px;font-size:12px;color:#627468}.parking select,.parking input,.parking button{font:inherit;border:1px solid #cdd9d1;border-radius:6px;min-height:37px;padding:7px 10px;box-sizing:border-box;background:white;color:#304c3b}.parking select{max-width:100%}.detail-field{flex:1;min-width:170px}.primary-action{background:#365f4b!important;border-color:#365f4b!important;color:white!important;white-space:nowrap}.primary-action:disabled{background:#e8ede9!important;color:#93a297!important;border-color:#dae2dc!important;cursor:not-allowed}.parking button:focus-visible,.parking input:focus-visible,.parking select:focus-visible{outline:2px solid #477b60;outline-offset:2px}.map-prerequisites{display:flex;gap:6px 18px;flex-wrap:wrap;padding-left:16px;font-size:12px;color:#8a7140;margin:12px 0 0}.location-candidates{list-style:none;padding:0;margin:14px 0 0}.location-candidates li{display:flex;justify-content:space-between;align-items:center;gap:14px;border:1px solid #d9e5dd;border-radius:7px;padding:13px;margin-top:8px;font-size:13px}.location-candidates small{display:block;font-size:11px;margin-top:5px;color:#7a8b80}.confirmed-note{font-size:12px;color:#397451;margin-bottom:0}.candidate-toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:16px}.candidate-toolbar .section-hint{margin:0;max-width:65%}.candidate-toolbar select{font-size:12px;max-width:190px}.inline-warning{font-size:12px;color:#8a7140}.query-confirmation{background:#f4f8f5;border:1px solid #dce8df;border-radius:7px;padding:12px 14px;margin-bottom:14px;font-size:13px}.query-confirmation p{margin:0 0 9px;line-height:1.7}.result-caption{font-size:12px;color:#76877b;line-height:1.8}.submission-hint{font-size:12px;color:#6a7b70;margin:17px 0 0;border-top:1px solid #e5ebe7;padding-top:12px}.map-service-details{border:1px solid #e1e8e3;border-radius:8px;padding:13px 16px;font-size:12px;color:#6c7c71}.map-service-details summary{cursor:pointer}.map-service-details p{line-height:1.8}.map-service-details button{margin-top:12px}.dispatch-error{margin:0;padding:12px 15px;border:1px solid #edcfca;border-radius:7px;background:#fff5f2;color:#8c493d;font-size:13px;line-height:1.8}.preview-notice{padding:10px 12px;border-radius:7px;background:#fff7e4;color:#8a6b27;font-size:12px;line-height:1.7}.sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0,0,0,0)}@media(max-width:600px){.dispatch-card{padding:14px}.candidate-toolbar{align-items:flex-start;flex-direction:column}.candidate-toolbar .section-hint{max-width:100%}.location-search label{flex:1;min-width:110px}.location-search .detail-field{flex-basis:100%}.location-search .primary-action{width:100%}.location-candidates li{align-items:flex-start;flex-direction:column}.dispatch-card-header h3{font-size:16px}}
</style>
