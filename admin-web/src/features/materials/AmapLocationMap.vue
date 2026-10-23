<script setup lang="ts">
import {onMounted,onUnmounted,ref,watch} from 'vue'
import {httpClient} from '@/shared/http/client'
import {loadAmapJs,pointFromEvent,type MapPoint,type AmapNamespace,type JsMap,type JsMarker,type JsBootstrap,type JsEvent} from './amap-js'
const props=defineProps<{point?:MapPoint;confirmed?:boolean;disabled?:boolean;selectedShopId?:number;shops?:{shopId:number;name:string;status:string;point?:MapPoint;mock?:boolean}[]}>()
const emit=defineEmits<{pick:[point:MapPoint];choose:[id:number]}>()
const host=ref<HTMLElement>(),state=ref<'idle'|'loading'|'ready'|'failed'>('idle'),error=ref(''),configuration=ref<{enabled:boolean;configured:boolean;reason:string}>()
let mapTimer:ReturnType<typeof setTimeout>|undefined
let api:AmapNamespace|undefined,map:JsMap|undefined,parking:JsMarker|undefined,markers:JsMarker[]=[],disposed=false,issued=false
async function configurationStatus(){try{configuration.value=(await httpClient.get('/maps/js/configuration')).data.data}catch{error.value='无法读取真实地图配置；地址搜索仍可使用'}}
function pick(event:JsEvent){if(props.disabled||disposed)return;const point=pointFromEvent(event);if(point){position(point);emit('pick',point)}}
function position(point:MapPoint){if(!map||!api)return;if(!parking){parking=new api.Marker({position:[point.longitude,point.latitude],draggable:!props.disabled,title:'车辆停车位置（待人工核验）'});parking.on('dragend',()=>{if(parking)pick({lnglat:parking.getPosition()})});map.add(parking)}else parking.setPosition([point.longitude,point.latitude])}
function renderShopMarkers(){if(!map||!api)return;map.remove(markers);markers=[];for(const [index,shop] of (props.shops??[]).entries()){if(!shop.point||shop.mock||shop.status!=='READY')continue;const label=document.createElement('button');label.type='button';label.className='amap-shop-marker'+(shop.shopId===props.selectedShopId?' is-selected':'');label.textContent=String(index+1);label.setAttribute('aria-label','选择'+shop.name);label.disabled=Boolean(props.disabled);const marker=new api.Marker({position:[shop.point.longitude,shop.point.latitude],content:label,title:shop.name,bubble:false});marker.on('click',()=>{if(!props.disabled)emit('choose',shop.shopId)});map.add(marker);markers.push(marker)}}
async function load(){
 if(disposed||state.value==='loading'||state.value==='ready'||!configuration.value?.enabled||!host.value)return
 state.value='loading';error.value=''
 try{const config=(await httpClient.post(window.location.origin+'/api/v1/maps/js/bootstrap',{})).data.data as JsBootstrap;issued=true;if(disposed){void revoke();return}api=await loadAmapJs(config);if(disposed)return
  map=new api.Map(host.value,{viewMode:'2D',zoom:props.point?15:8,center:props.point?[props.point.longitude,props.point.latitude]:[113.5,23.5],resizeEnable:true})
  map.on('click',pick);map.on('complete',()=>{if(!disposed){clearTimeout(mapTimer);state.value='ready'}});map.on('error',()=>{clearTimeout(mapTimer);if(!disposed){state.value='failed';error.value='高德底图未能完成加载；请核对配置与网络，未自动重试';map?.destroy();map=undefined}})
  mapTimer=setTimeout(()=>{if(!disposed&&state.value!=='ready'){state.value='failed';error.value='高德底图加载超时；未自动重试，原输入保留';map?.destroy();map=undefined}},15000)
  if(props.point)position(props.point);renderShopMarkers()
 }catch{if(!disposed){state.value='failed';error.value='高德地图未能加载。请核对JS Key、安全代理与域名限制；未自动重试，原输入保留。'}}
}
async function revoke(){if(issued){issued=false;try{await fetch('/api/v1/maps/js-proxy/session',{method:'DELETE',credentials:'same-origin',keepalive:true})}catch{/* No credential or transport details in browser logs. */}}}
watch(()=>props.point,point=>{if(point){position(point);map?.setCenter([point.longitude,point.latitude])}else if(parking){map?.remove(parking);parking=undefined}},{deep:true})
watch(()=>[props.shops,props.selectedShopId,props.disabled],()=>{parking?.setDraggable(!props.disabled);renderShopMarkers()},{deep:true})
onMounted(configurationStatus)
onUnmounted(()=>{disposed=true;clearTimeout(mapTimer);map?.destroy();map=undefined;void revoke()})
</script>
<template><section class="amap-location-map" :aria-busy="state==='loading'"><div ref="host" class="amap-host" aria-label="高德真实地图，点击暂选停车位置"></div><div v-if="state!=='ready'" class="map-loading-cover"><strong>高德地图 · 车辆停放位置</strong><p>{{ configuration?.reason??'正在读取地图配置（不调用高德）…' }}</p><button type="button" :disabled="!configuration?.enabled||disabled||state==='loading'||state==='failed'" @click="load">{{ state==='loading'?'正在加载高德地图…':state==='failed'?'加载失败，请重新打开页面':'加载高德地图' }}</button><small>加载后会请求高德在线资源；不自动解析地址或计算路线，不读取设备定位。</small></div><p v-if="error" role="alert">{{ error }}</p><p class="map-guidance">{{ state==='ready'?'高德真实底图 · 点击地图或拖动标记只暂选坐标，再点击下方核验按钮。':'地图尚未加载，可继续使用下方地址搜索。' }}<span v-if="state==='ready'&&!point"> 初始为广东区域概览，不代表车辆位置。</span></p></section></template>
<style scoped>.amap-location-map{position:relative}.amap-host{height:330px;border:1px solid #dce4df;border-radius:9px;background:#f2f5f1;overflow:hidden}.map-loading-cover{position:absolute;inset:0 0 auto;height:330px;display:flex;align-items:center;justify-content:center;flex-direction:column;gap:12px;padding:25px;background:linear-gradient(145deg,#edf3ee,#f8faf7);text-align:center;border:1px solid #dce4df;border-radius:9px}.map-loading-cover p{font-size:13px;line-height:1.8;color:#6e7e72;margin:0}.map-loading-cover button{padding:10px 18px;border:1px solid #365f4b;border-radius:6px;background:#365f4b;color:white;cursor:pointer}.map-loading-cover button:disabled{background:#e2e9e2;color:#8a9a89;border-color:#d6dfd5;cursor:not-allowed}.map-loading-cover small{font-size:11px;color:#7b897a;line-height:1.8}.map-guidance{font-size:12px;line-height:1.8;color:#6e7e72;margin:9px 0 0}[role=alert]{color:#a04b3e;font-size:12px;line-height:1.8}@media(max-width:600px){.amap-host,.map-loading-cover{height:280px}}</style>
<style>.amap-shop-marker{border:2px solid #fff;border-radius:50%;background:#718578;color:#fff;width:32px;height:32px;font-weight:bold;box-shadow:0 2px 6px #253b3330;cursor:pointer}.amap-shop-marker.is-selected{background:#2c7657;outline:3px solid #88bca1}</style>
