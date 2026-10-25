<script setup lang="ts">
import {computed,onMounted,ref} from 'vue'
import {httpClient} from '@/shared/http/client'
import {workOrderApi,type WorkOrder} from './work-orders'
const props=defineProps<{order:WorkOrder}>()
const emit=defineEmits<{changed:[];pending:[value:boolean]}>(),api=workOrderApi(httpClient)
interface District {id:number;province:string;city:string;name:string;fullName:string}
const rows=ref<District[]>([]),province=ref(''),city=ref(''),selected=ref(''),reason=ref('')
const busy=ref(false),loading=ref(false),error=ref(''),regionError=ref('')
const provinces=computed(()=>[...new Set(rows.value.map(row=>row.province))])
const cities=computed(()=>[...new Set(rows.value.filter(row=>row.province===province.value).map(row=>row.city))])
const districts=computed(()=>rows.value.filter(row=>row.province===province.value&&row.city===city.value))
const selectedDistrict=computed(()=>districts.value.find(row=>String(row.id)===selected.value))
const currentName=computed(()=>rows.value.find(row=>row.id===props.order.accidentRegionId)?.fullName??'')
function provinceChanged(){city.value='';selected.value=''}
function cityChanged(){selected.value=''}
async function loadRegions(){
 if(loading.value||busy.value)return
 loading.value=true;regionError.value=''
 try{
  const loaded:District[]=[]
  // Bounded reads of the existing paginated staff API; no map provider requests.
  for(let page=1;page<=50;page++){
   const result=await api.accidentDistricts('',page,100)
   if(result.total>5000)throw new Error('区域资料超过本选择器读取上限，请联系管理员；不会显示截断列表。')
   for(const row of result.records){
    const parts=row.name.split('/').map(value=>value.trim())
    if(parts.length!==3||parts.some(value=>!value))throw new Error('部分区域资料缺少完整省、市、区县层级，请联系管理员核对。')
    loaded.push({id:row.id,province:parts[0]!,city:parts[1]!,name:parts[2]!,fullName:row.name})
   }
   if(loaded.length>=result.total){
    if(loaded.length!==result.total||new Set(loaded.map(row=>row.id)).size!==loaded.length)throw new Error('区域资料在读取期间发生变化，请重新加载。')
    rows.value=loaded
    const existing=loaded.find(row=>row.id===props.order.accidentRegionId)
    if(existing&&!province.value&&!city.value&&!selected.value){province.value=existing.province;city.value=existing.city;selected.value=String(existing.id)}
    return
   }
   if(!result.records.length)throw new Error('区域资料尚未完整加载，请重试。')
  }
  throw new Error('区域资料尚未完整加载，请联系管理员。')
 }catch(e){rows.value=[];regionError.value=e instanceof Error?e.message:'事故区域读取失败'}finally{loading.value=false}
}
async function save(){
 if(busy.value||loading.value||!selectedDistrict.value||!reason.value.trim())return
 busy.value=true;error.value='';emit('pending',true)
 try{await api.accidentDistrict(props.order.id,props.order.version,selectedDistrict.value.id,reason.value.trim());reason.value='';emit('changed')}
 catch(e){error.value=e instanceof Error?e.message:'事故区县保存失败，选择和原因已保留'}finally{busy.value=false;emit('pending',false)}
}
onMounted(loadRegions)
</script>
<template>
 <section class="accident-review" aria-label="事故区县人工核对">
  <h3>事故区县</h3>
  <p>当前：{{ currentName||(order.accidentRegionId?'已登记（区县ID '+order.accidentRegionId+'）':'缺失，请人工核实并补全') }}</p>
  <p>用于网点服务资格，与下方车辆当前停放位置独立；不得为匹配目标网点修改事故事实。城市级服务关系不会自动扩展为区县。</p>
  <details :open="!order.accidentRegionId">
   <summary>人工核对或更正事故区县</summary>
   <p>依次选择实际事故发生的省份、城市和区县，无需输入编码。选择不会自动保存，也不会读取停车位置来代填。</p>
   <p v-if="loading" role="status">正在读取省、市、区县资料…</p>
   <p v-if="regionError" role="alert">{{ regionError }} <button type="button" :disabled="loading||busy" @click="loadRegions">重新加载区域</button></p>
   <p v-else-if="!loading&&!rows.length" role="status">暂无启用的完整省市区县资料，请联系管理员维护。</p>
   <div class="region-selectors">
    <label>事故省份<select v-model="province" :disabled="busy||loading||!provinces.length" aria-label="事故省份" @change="provinceChanged"><option value="">请选择省份</option><option v-for="item in provinces" :key="item" :value="item">{{ item }}</option></select></label>
    <label>事故城市<select v-model="city" :disabled="busy||loading||!province||!cities.length" aria-label="事故城市" @change="cityChanged"><option value="">请先选择省份，再选择城市</option><option v-for="item in cities" :key="item" :value="item">{{ item }}</option></select></label>
    <label>事故区县<select v-model="selected" :disabled="busy||loading||!city||!districts.length" aria-label="事故区县"><option value="">请先选择城市，再选择区县</option><option v-for="row in districts" :key="row.id" :value="String(row.id)">{{ row.name }}</option></select></label>
   </div>
   <p v-if="selectedDistrict" role="status">待人工核实：{{ selectedDistrict.fullName }}</p>
   <label>核对或更正原因<textarea v-model="reason" maxlength="500" :disabled="busy" rows="2"/></label>
   <p>点击保存即确认已人工核实事故事实，不是根据停车位置或目标网点推断。</p>
   <button type="button" :disabled="busy||loading||!selectedDistrict||!reason.trim()" @click="save">{{ busy?'处理中…':'保存事故区县并记录审计' }}</button>
  </details>
  <p v-if="error" role="alert">{{ error }}</p>
 </section>
</template>
<style scoped>
.accident-review{padding:12px 16px;margin-bottom:20px;border:1px solid #d8e2dc;border-radius:8px;background:#f7faf8}.accident-review h3{margin:0 0 8px}.accident-review p{font-size:13px;line-height:1.6}.accident-review label{display:block;margin:12px 0}.accident-review select,.accident-review textarea{display:block;box-sizing:border-box;width:100%;padding:8px;border:1px solid #bacdc4;border-radius:6px}.region-selectors{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.region-selectors label{min-width:0}.accident-review [role=alert]{color:#a7313c}@media(max-width:600px){.region-selectors{grid-template-columns:1fr;gap:0}}
</style>
