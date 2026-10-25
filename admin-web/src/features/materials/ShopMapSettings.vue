<script setup lang="ts">
import {onMounted,ref} from 'vue'
import {httpClient} from '@/shared/http/client'
const props=defineProps<{shopId:number;address:string|null}>()
interface Option {id:number;name:string;code?:string}
interface Position {version:number;districtId:number;longitude:number;latitude:number;source:string;addressSnapshot:string}
const brands=ref<Option[]>([]),brand=ref(''),brandPage=ref(1),brandTotal=ref(0)
const selected=ref<number[]>([]),models=ref<Option[]>([]),modelPage=ref(1),modelTotal=ref(0),cities=ref<Option[]>([]),districts=ref<Option[]>([]),city=ref(''),district=ref(''),longitude=ref(''),latitude=ref(''),synthetic=ref(false),position=ref<Position>(),busy=ref(false),error=ref(''),message=ref(''),cityPage=ref(1),cityTotal=ref(0),districtPage=ref(1),districtTotal=ref(0)
async function loadModels(){const page=(await httpClient.get('/pricing/models',{params:{page:modelPage.value,size:20,brandId:brand.value?Number(brand.value):undefined}})).data.data as {records:Option[];total:number};models.value=page.records;modelTotal.value=page.total}
async function loadBrands(){const page=(await httpClient.get('/pricing/brands',{params:{page:brandPage.value,size:20}})).data.data as {records:Option[];total:number};brands.value.push(...page.records);brandTotal.value=page.total;brandPage.value++}
async function regions(kind:'city'|'district'){
  const value=(await httpClient.get('/maps/locations/admin-regions',{params:{page:kind==='city'?cityPage.value:districtPage.value,size:20,...(kind==='district'?{cityId:Number(city.value)}:{})}})).data.data as {records:Option[];total:number}
  if(kind==='city'){cities.value.push(...value.records);cityTotal.value=value.total;cityPage.value++}else{districts.value.push(...value.records);districtTotal.value=value.total;districtPage.value++}
}
async function run(action:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';message.value='';try{await action()}catch(e){error.value=e instanceof Error?e.message:'资料维护失败'}finally{busy.value=false}}
async function chooseCity(){district.value='';districts.value=[];districtPage.value=1;districtTotal.value=0;if(city.value)await run(()=>regions('district'))}
async function load(){const cap=(await httpClient.get(`/maps/admin/shops/${props.shopId}/capabilities`,{params:{page:1,size:100}})).data.data as {records:number[]};selected.value=cap.records;position.value=(await httpClient.get(`/maps/admin/shops/${props.shopId}/location`)).data.data as Position|undefined;await loadBrands();await loadModels();await regions('city')}
function toggle(id:number,checked:boolean){selected.value=checked?[...selected.value,id]:selected.value.filter(value=>value!==id)}
async function saveModels(){await run(async()=>{await httpClient.put(`/maps/admin/shops/${props.shopId}/capabilities`,{modelIds:selected.value});message.value='车型适配已保存并审计'})}
async function savePosition(){await run(async()=>{if(!district.value||!longitude.value||!latitude.value)throw new Error('请选择区县、输入经纬度并确认坐标来源');await httpClient.post(`/maps/admin/shops/${props.shopId}/location`,{districtId:Number(district.value),longitude:longitude.value.trim(),latitude:latitude.value.trim(),gcj02Confirmed:true,synthetic:synthetic.value});position.value=(await httpClient.get(`/maps/admin/shops/${props.shopId}/location`)).data.data as Position;message.value='位置已追加新版本并审计，历史保留'})}
onMounted(()=>run(load))
</script>
<template><section><p>网点原地址：{{ props.address||'未填写，请先维护地址' }}</p><p v-if="error" role="alert">{{ error }}</p><p v-if="message" role="status">{{ message }}</p><fieldset :disabled="busy"><legend>网点适配车型（管理员维护）</legend><p>按工单品牌名＋车型名精确匹配启用目录。未配置表示“能力资料未完善”，不代表没有维修能力。</p>
  <label>按品牌筛选车型<select v-model="brand" @change="modelPage=1;run(loadModels)"><option value="">全部品牌</option><option v-for="item in brands" :key="item.id" :value="String(item.id)">{{ item.name }}（{{ item.code }}）</option></select></label><button v-if="brands.length<brandTotal" @click="run(loadBrands)">加载更多品牌</button>
  <label v-for="item in models" :key="item.id" class="model"><input type="checkbox" :checked="selected.includes(item.id)" @change="toggle(item.id,($event.target as HTMLInputElement).checked)">{{ item.name }}（{{ item.code }} / ID {{ item.id }}）</label>
  <button :disabled="modelPage<=1" @click="modelPage--;run(loadModels)">上一页车型</button><span>第{{ modelPage }}页，共{{ modelTotal }}条；已选{{ selected.length }}个</span><button :disabled="modelPage*20>=modelTotal" @click="modelPage++;run(loadModels)">下一页车型</button><button @click="saveModels">保存车型适配</button></fieldset>
  <fieldset :disabled="busy"><legend>网点自有位置 · GCJ-02</legend><p v-if="position">当前版本{{ position.version }}，来源{{ position.source }}；{{ position.addressSnapshot===props.address?'地址快照一致':'地址已改变，旧定位失效' }}。经度{{ position.longitude }}，纬度{{ position.latitude }}。</p><p v-else>尚无已确认位置，不使用旧Mock、GPS或百度坐标代替。</p>
  <label>广东城市<select v-model="city" @change="chooseCity"><option value="">请选择</option><option v-for="item in cities" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label><button v-if="cities.length<cityTotal" @click="run(()=>regions('city'))">加载更多城市</button>
  <label>区县<select v-model="district"><option value="">请选择</option><option v-for="item in districts" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label><button v-if="districts.length<districtTotal" @click="run(()=>regions('district'))">加载更多区县</button>
  <label>经度（在前）<input v-model="longitude" aria-label="网点经度"></label><label>纬度（在后）<input v-model="latitude" aria-label="网点纬度"></label><p>点击保存即确认已核对坐标系为GCJ-02及经纬度顺序；真实资料须来自自有可信来源，测试资料另勾选合成。</p><label><input v-model="synthetic" type="checkbox">仅dev Mock验收：明确使用合成测试坐标（不能当真实位置）</label>
  <p>真实位置保存时仅发送坐标给高德逆编码核实广东及区县，会消耗已批准预算；默认外呼关闭。保存管理员输入，不将高德原始地址/返回永久入库。原地址改变后需重新维护。</p><button @click="savePosition">确认并追加位置版本</button></fieldset></section></template>
<style scoped>fieldset{border:1px solid var(--color-border);margin:14px 0;padding:12px}label{display:block;margin:10px 0}input,select,button{min-height:34px;margin:4px}.model{display:inline-flex;align-items:center;margin-right:16px}</style>
