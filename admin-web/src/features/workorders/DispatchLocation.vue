<script setup lang="ts">
import {computed,onMounted,ref} from 'vue'
import {ElOption,ElSelect} from 'element-plus'
import {httpClient} from '@/shared/http/client'
import {workOrderApi,type WorkOrder,type RegionOption,type MapLocation,type MapShop} from './work-orders'
import {locationAddress,mapMarkers,recommendedShop} from './dispatch-map'
const props=defineProps<{caseId:string;order:WorkOrder;selectedShopId?:number}>()
const emit=defineEmits<{changed:[];pending:[value:boolean];select:[id:number|undefined]}>(),api=workOrderApi(httpClient)
const address=ref(props.order.accidentAddress??''),region=ref<number>(),cities=ref<RegionOption[]>([]),cityQuery=ref(''),cityPage=ref(0),cityTotal=ref(0),cityBusy=ref(false)
const confirmed=ref(false),busy=ref(false),error=ref(''),saved=ref(false),origin=ref<MapLocation>(),candidates=ref<MapShop[]>([]),page=ref(0),total=ref(0)
let cityRevision=0
const markers=computed(()=>origin.value?mapMarkers(origin.value,candidates.value):[])
const recommended=computed(()=>recommendedShop(candidates.value))
const chosen=computed(()=>candidates.value.find(shop=>shop.id===props.selectedShopId))
async function searchCities(query:string,append=false){
 const revision=++cityRevision;cityBusy.value=true;cityQuery.value=query
 try{const next=append?cityPage.value+1:1,result=await api.mapCities(query,next);if(revision!==cityRevision)return;cities.value=append?[...cities.value,...result.records]:result.records;cityPage.value=next;cityTotal.value=result.total}catch(e){if(revision===cityRevision)error.value=e instanceof Error?e.message:'城市加载失败'}finally{if(revision===cityRevision)cityBusy.value=false}
}
function changed(){confirmed.value=false;saved.value=false;origin.value=undefined;candidates.value=[];page.value=0;total.value=0;emit('select',undefined);emit('pending',true)}
async function moreShops(){const result=await api.mapShops(props.caseId,page.value+1);candidates.value.push(...result.records);page.value++;total.value=result.total}
async function locate(){
 busy.value=true;error.value='';saved.value=false;emit('select',undefined);emit('pending',true)
 try{
  const city=cities.value.find(row=>row.id===region.value)
  if(!city||!address.value.trim()||!confirmed.value)throw Error('请选择城市，并核对接车地址')
  const value=locationAddress(city.name,address.value)
  if(value.length>255)throw Error('接车地址不能超过255个字符')
  const point=await api.mapLocation(value)
  await api.dispatchLocation(props.caseId,props.order.version,city.id,value)
  emit('changed')
  address.value=value;origin.value=point;candidates.value=[];page.value=0;total.value=0
  await moreShops();saved.value=true;emit('select',recommended.value??candidates.value[0]?.id);emit('pending',false)
 }catch(e){error.value=e instanceof Error?e.message:'定位失败，请重试'}finally{busy.value=false}
}
async function loadMore(){busy.value=true;error.value='';try{await moreShops()}catch(e){error.value=e instanceof Error?e.message:'网点加载失败'}finally{busy.value=false}}
function selectShop(id:number){if(saved.value&&!busy.value)emit('select',id)}
const distance=(shop:MapShop)=>shop.distanceMetres==null?'距离待补':(shop.distanceMetres/1000).toFixed(2)+' km'
onMounted(async()=>{
 emit('pending',true);await searchCities('')
 const city=cities.value.find(row=>row.id===props.order.accidentRegionId)||cities.value.find(row=>address.value.includes(row.name))
 if(city)region.value=city.id
 if(props.order.dispatchAddressConfirmed&&city&&address.value.trim()){
  busy.value=true
  try{origin.value=await api.mapLocation(address.value);await moreShops();saved.value=true;confirmed.value=true;const current=props.selectedShopId??props.order.currentAssignment?.shopId;emit('select',candidates.value.some(shop=>shop.id===current)?current:recommended.value);emit('pending',false)}catch(e){error.value=e instanceof Error?e.message:'地图加载失败'}finally{busy.value=false}
 }
})
</script>

<template>
 <section class="dispatch-location" data-reading="定位与推荐">
  <header><div><span class="eyebrow">LOCATION / DISPATCH</span><h3>定位接车位置</h3></div><span class="mock-badge">Mock 地图</span></header>
  <div class="location-fields">
   <label>城市<ElSelect v-model="region" filterable remote :remote-method="searchCities" :loading="cityBusy" :disabled="busy" placeholder="输入城市，如深圳" @change="changed"><ElOption v-for="city in cities" :key="city.id" :label="city.name" :value="city.id"/></ElSelect></label>
   <label>接车地址<input v-model="address" maxlength="255" :disabled="busy" placeholder="自动采用聊天地址，可修改" @input="changed"/></label>
  </div>
  <button v-if="cities.length<cityTotal" class="text-button" :disabled="cityBusy||busy" @click="searchCities(cityQuery,true)">更多城市</button>
  <div class="locate-actions"><label><input v-model="confirmed" type="checkbox" :disabled="busy"/>已核对接车地址</label><button class="locate-button" :disabled="busy||!region||!confirmed||!address.trim()" @click="locate">{{ busy?'正在定位…':'定位并推荐网点' }}</button></div>
  <p v-if="error" role="alert" class="error">{{ error }}</p>
  <div class="map-surface">
   <div class="map-caption"><span>{{ saved?'接车位置与合作网点':'先选择城市并定位' }}</span><span>模拟坐标 · 非真实道路</span></div>
   <svg viewBox="0 0 600 320" role="group" aria-label="模拟地图：接车位置和合作网点">
    <defs><pattern id="dispatch-grid" width="40" height="40" patternUnits="userSpaceOnUse"><path d="M40 0H0V40" fill="none" stroke="#cedfd7" stroke-width="1"/></pattern></defs>
    <rect width="600" height="320" fill="#edf3ed"/><rect width="600" height="320" fill="url(#dispatch-grid)"/>
    <path d="M-20 235C90 100 180 270 310 130S510 130 640-30" fill="none" stroke="#c1dedf" stroke-width="32"/>
    <path d="M-20 90L620 250M100-20L430 340M-20 280L600 40" fill="none" stroke="#fffdf7" stroke-width="13"/>
    <path d="M-20 90L620 250M100-20L430 340M-20 280L600 40" fill="none" stroke="#dbe2d5" stroke-width="1"/>
    <template v-if="origin">
     <line v-if="chosen&&markers[candidates.indexOf(chosen)+1]" :x1="markers[0]!.x" :y1="markers[0]!.y" :x2="markers[candidates.indexOf(chosen)+1]!.x" :y2="markers[candidates.indexOf(chosen)+1]!.y" stroke="#647b71" stroke-width="2" stroke-dasharray="5 6"/>
     <g v-for="(shop,index) in candidates" :key="shop.id" role="button" tabindex="0" :aria-label="'选择网点：'+shop.name" :aria-pressed="selectedShopId===shop.id" class="map-pin" @click="selectShop(shop.id)" @keydown.enter="selectShop(shop.id)" @keydown.space.prevent="selectShop(shop.id)">
      <circle :cx="markers[index+1]!.x" :cy="markers[index+1]!.y" :r="selectedShopId===shop.id?16:12" :fill="selectedShopId===shop.id?'#244f40':'#fff'" stroke="#406951" stroke-width="2"/>
      <text :x="markers[index+1]!.x" :y="markers[index+1]!.y+4" text-anchor="middle" :fill="selectedShopId===shop.id?'#fff':'#244f40'" font-size="11" font-weight="600">{{ index+1 }}</text>
     </g>
     <g><circle :cx="markers[0]!.x" :cy="markers[0]!.y" r="12" fill="#e39e58" stroke="#fff" stroke-width="3"/><text :x="markers[0]!.x" :y="markers[0]!.y-20" text-anchor="middle" fill="#58452f" font-size="12">接车位置</text></g>
    </template>
    <text v-else x="300" y="166" text-anchor="middle" fill="#61756b" font-size="14">定位后显示接车位置与合作网点</text>
   </svg>
  </div>
  <template v-if="saved">
   <div class="recommend-heading"><h4>合作网点 <span>{{ total }}</span></h4><small>按模拟直线距离排序，可手动改选</small></div>
   <div class="shop-list" aria-label="合作网点候选"><button v-for="(shop,index) in candidates" :key="shop.id" :disabled="busy" :aria-pressed="selectedShopId===shop.id" :class="{selected:selectedShopId===shop.id}" @click="selectShop(shop.id)"><span class="shop-number">{{ index+1 }}</span><span class="shop-name">{{ shop.name }}<small v-if="shop.id===recommended">距离最近 · 推荐</small></span><span class="shop-distance">{{ distance(shop) }}</span></button></div>
   <p v-if="!candidates.length" class="empty">该城市暂无可派单合作网点，请修改城市或联系管理员。</p>
   <button v-if="candidates.length<total" class="text-button" :disabled="busy" @click="loadMore">更多网点（{{ candidates.length }}/{{ total }}）</button>
   <p v-if="chosen" class="selected-label">已选网点：{{ chosen.name }}</p>
  </template>
  <small class="map-note">门店详细地址待补充；当前定位与距离仅用于演示。</small>
 </section>
</template>

<style scoped>
.dispatch-location{padding:0 0 24px;margin-bottom:20px;border-bottom:1px solid #dce5e4}.dispatch-location header{display:flex;justify-content:space-between;align-items:center;margin-bottom:22px}.eyebrow{font-size:10px;letter-spacing:.14em;color:#6b7d74}.dispatch-location h3{font-size:21px;margin:6px 0 0}.mock-badge{font-size:11px;color:#526b60;border:1px solid #c4d4cb;padding:6px 10px;border-radius:20px}.location-fields{display:grid;grid-template-columns:1fr 2fr;gap:16px}.location-fields label{display:grid;gap:8px;font-size:12px}.location-fields input{box-sizing:border-box;width:100%;padding:9px 11px;border:1px solid #b9c9c9;border-radius:7px;background:white;font:inherit}.location-fields :deep(.el-select){width:100%}.locate-actions{display:flex;align-items:center;justify-content:space-between;gap:16px;margin:16px 0}.locate-actions label{font-size:12px;color:#52665c}.locate-button{background:#345d4d!important;color:white!important;border-color:#345d4d!important}.map-surface{overflow:hidden;border:1px solid #d0ddd4;border-radius:12px;background:#edf3ed}.map-caption{display:flex;justify-content:space-between;gap:12px;padding:12px 14px;background:#f9fcf6e8;font-size:11px;color:#4b6356}.map-caption span:last-child{color:#718379}.map-surface svg{display:block;width:100%;max-height:330px}.map-pin{cursor:pointer;outline:none}.map-pin:focus-visible circle{stroke:#cb7434;stroke-width:4}.recommend-heading{display:flex;justify-content:space-between;align-items:center;gap:12px;margin:20px 0 12px}.recommend-heading h4{margin:0;font-size:15px}.recommend-heading h4 span{margin-left:6px;font-size:12px;color:#6b7d74}.recommend-heading small,.map-note{font-size:11px;color:#6a7d72}.shop-list{display:grid;grid-template-columns:1fr 1fr;gap:8px;max-height:290px;overflow-y:auto;padding:2px}.shop-list button{display:flex;gap:9px;align-items:center;text-align:left;background:#fff!important;border-color:#d7e2da!important;padding:12px!important;min-width:0}.shop-list button.selected{background:#eff8f0!important;border-color:#44745a!important}.shop-number{font-size:11px;min-width:20px;color:#6b7d74}.shop-name{font-size:12px;line-height:1.5;flex:1;overflow-wrap:anywhere}.shop-name small{display:block;color:#367347;font-size:10px;margin-top:4px}.shop-distance{font-size:11px;white-space:nowrap;color:#61766a}.selected-label{font-size:12px;padding:12px 0 0;color:#2f6145}.map-note{display:block;margin-top:14px}.error{color:#a7313c;font-size:12px}.empty{font-size:12px;color:#687a70}.text-button{font-size:12px!important;margin-top:10px}@media(max-width:680px){.location-fields,.shop-list{grid-template-columns:1fr}.map-caption,.recommend-heading{align-items:flex-start;flex-direction:column}.locate-actions{align-items:flex-start;gap:12px;flex-direction:column}}
</style>
