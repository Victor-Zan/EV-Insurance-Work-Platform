<script setup lang="ts">
import {computed,provide,ref} from 'vue'
import axios,{AxiosError} from 'axios'
import ParkingLocationPanel from '../features/materials/ParkingLocationPanel.vue'
import {mapPreviewClientKey} from '../features/materials/map-preview-client'
const selected=ref<{shopId:number;queryId:string;name:string}>(),positionConfirmed=ref(false),submitted=ref(false),scenario=ref('normal'),revision=ref(0)
let geocodes=0,routes=0,token=0
const cached=new Map<string,unknown>()
const syntheticRows=()=>[
 {shopId:101,name:'【合成】维修网点甲',address:'合成服务路1号（不存在的测试地址）',metres:1800,seconds:420},
 {shopId:102,name:'【合成】维修网点乙',address:'合成服务路2号（不存在的测试地址）',metres:4200,seconds:720},
 {shopId:103,name:'【合成】维修网点丙',address:'合成服务路3号（不存在的测试地址）',metres:7600,seconds:1080},
].map(row=>({...row,status:'READY',qualification:'合成服务与车型配置，仅用于预览',calculatedAt:new Date().toISOString(),mock:true}))
// Every operation is handled locally by this adapter; it has no HTTP/network fallback.
const previewClient=axios.create({adapter:async config=>{
 const path=config.url??'';const body=typeof config.data==='string'?JSON.parse(config.data):config.data
 let data:unknown
 if(path.endsWith('/budget'))data={callsEnabled:true,reason:'合成预览额度，不关联真实台账',operations:[{operation:'GEOCODE',total:10,used:geocodes,reserved:0,remaining:10-geocodes},{operation:'REVERSE',total:5,used:0,reserved:0,remaining:5},{operation:'DRIVING',total:20,used:routes,reserved:0,remaining:20-routes}]}
 else if(path.endsWith('/regions')){const isDistrict=Boolean(config.params?.cityId);data={total:1,records:isDistrict?[{id:3,name:'天河区',adcode:'440106'}]:[{id:2,name:'广州市',adcode:'440100'}]}}
 else if(path.endsWith('/recommendation-context'))data={accidentRegion:'广州市 / 天河区（合成案件）',vehicleBrand:'合成品牌',vehicleModel:'合成车型'}
 else if(path.endsWith('/resolve')){geocodes++;data=[{token:'preview-location-'+(++token),candidate:{address:'广东省广州市天河区'+body.detail,province:'广东省',city:'广州市',district:'天河区',adcode:'440106',level:'合成网格点，非实际经纬度',status:'REQUIRES_CONFIRMATION',mock:true,point:{longitude:113.1,latitude:23.1}}}]}
 else if(path.endsWith('/confirm'))data=null
 else if(path.endsWith('/recommendation-estimate'))data={candidates:scenario.value==='empty'?0:3,maximumRouteCalls:scenario.value==='empty'?0:3,routesRemaining:20-routes,fits:20-routes>=3}
 else if(path.endsWith('/recommendations')){
  if(scenario.value==='failure')throw new AxiosError('合成网络失败：输入保留，可再次明确查询；没有真实外呼','PREVIEW_FAILURE',config)
  const key=String(config.headers?.get('Idempotency-Key')??'')
  if(!cached.has(key)){routes+=3;cached.set(key,{queryId:'preview-query-'+cached.size,provider:'MOCK_UI_PREVIEW',mock:true,scope:'仅本页3个合成网点，不代表真实合作范围',complete:true,expiresAt:new Date(Date.now()+120000).toISOString(),shops:{page:1,size:20,total:3,records:syntheticRows()}})}
  data=cached.get(key)
 }
 else if(path.includes('recommendation-exclusions'))data={total:0,records:[]}
 else throw new AxiosError('预览不支持此操作，未访问后端','PREVIEW_UNSUPPORTED',config)
 return {status:200,statusText:'OK',headers:{},config,data:{code:'SUCCESS',data,traceId:'synthetic-preview'}}
}})
provide(mapPreviewClientKey,previewClient)
const canSimulateSubmit=computed(()=>positionConfirmed.value&&Boolean(selected.value)&&!submitted.value)
function choose(value:typeof selected.value){selected.value=value;submitted.value=false}
// Explicit position confirmation follows choose(undefined) from the shared controller.
function confirmed(){positionConfirmed.value=true}
function submit(){if(canSimulateSubmit.value)submitted.value=true}
function reset(){revision.value++;selected.value=undefined;positionConfirmed.value=false;submitted.value=false;geocodes=0;routes=0;cached.clear()}
</script>
<template><main class="preview-shell"><header class="preview-header"><div><span class="eyebrow">派单交互预览</span><h1>确认位置，选择网点，人工派单</h1><p>合成界面验收 · 不读取客户案件 · 无真实地图或OCR调用</p></div><a href="/work-orders">返回案件列表</a></header><div class="preview-layout"><section class="workspace"><section class="accident-summary"><strong>事故服务区县</strong><span>广东省 / 广州市 / 天河区（合成设定）</span><small>独立于停车位置，不随地图选点改变。</small></section><ParkingLocationPanel :key="revision" case-id="preview-only-synthetic" preview-only :selected-shop-id="selected?.shopId" @choose="choose" @confirmed="confirmed"/><section class="submit-card"><div><strong>03 · 人工确认</strong><p>{{ selected?'已选择：'+selected.name:'请先确认位置并选择一个候选网点' }}</p></div><button type="button" :disabled="!canSimulateSubmit" @click="submit">{{ submitted?'模拟已提交':'确认模拟派单' }}</button></section><p v-if="submitted" class="success-note" role="status">模拟操作完成：所选{{ selected?.name }}，模拟状态为待接单。本页没有创建案件、正式派单、审计或通知。</p></section><aside class="preview-guide"><h2>预览操作</h2><ol><li>选择广州市、天河区。</li><li>填写合成地址，或在网格选点。</li><li>确认模拟停放位置。</li><li>查询网点并确认模拟计算。</li><li>选择另一家网点，再确认模拟派单。</li></ol><label>候选场景<select v-model="scenario"><option value="normal">正常候选</option><option value="empty">无合格网点</option><option value="failure">模拟查询失败</option></select></label><button type="button" @click="reset">重置合成预览</button><p>地图为本地绘制的合成示意图，不是高德底图；数字仅验证排序与选择，不能用于实际距离判断。</p><p>正式页面继续使用既有地址确认、资格筛选、道路服务和原派单接口。</p><div class="zero-calls">真实供应商请求：0<br>正式业务写入：0</div></aside></div></main></template>
<style>
*{box-sizing:border-box}body{margin:0;background:#f3f5f2;color:#304437;font-family:"Microsoft YaHei","PingFang SC",sans-serif}button,input,select{font:inherit}button{cursor:pointer}button:disabled{cursor:not-allowed}a{color:#365f4b}.preview-shell{max-width:1240px;margin:auto;padding:32px 28px 50px}.preview-header{display:flex;justify-content:space-between;align-items:center;gap:20px;margin-bottom:25px}.eyebrow{font-size:11px;letter-spacing:2px;color:#809080}.preview-header h1{font-size:27px;margin:9px 0}.preview-header p{font-size:13px;color:#7b8b7e;margin:0}.preview-header a{font-size:12px;padding:10px 15px;border:1px solid #cfdacf;border-radius:6px;text-decoration:none;background:#fff;white-space:nowrap}.preview-layout{display:grid;grid-template-columns:minmax(0,1fr) 240px;gap:24px;align-items:start}.workspace{min-width:0}.accident-summary{padding:16px 20px;border:1px solid #dce5dc;border-radius:10px;background:#edf3ec;display:flex;align-items:center;gap:10px;flex-wrap:wrap;font-size:13px}.accident-summary strong{font-size:14px}.accident-summary small{flex-basis:100%;color:#758473}.preview-guide{position:sticky;top:22px;background:#fff;border:1px solid #e1e6df;border-radius:10px;padding:20px;font-size:12px;line-height:1.9}.preview-guide h2{font-size:16px;margin:0 0 12px}.preview-guide ol{padding-left:18px;color:#6d7b6c}.preview-guide label{display:flex;flex-direction:column;gap:5px}.preview-guide select,.preview-guide button{border:1px solid #ccd8cc;padding:8px;border-radius:5px;background:#fff;color:#3c5740;width:100%;margin-bottom:12px}.preview-guide p{color:#7b8979}.zero-calls{padding:12px;background:#f0f6ef;border-radius:5px;color:#4e714a}.submit-card{display:flex;justify-content:space-between;align-items:center;border:1px solid #dbe5db;border-radius:10px;background:#fff;padding:20px;gap:15px}.submit-card strong{font-size:14px}.submit-card p{font-size:12px;color:#73816f}.submit-card button{border:0;background:#365f4b;color:#fff;border-radius:6px;padding:12px 20px;white-space:nowrap}.submit-card button:disabled{background:#e5ebe3;color:#8b9b84}.success-note{padding:16px;background:#e8f3e5;border:1px solid #c9dfc1;border-radius:7px;font-size:13px;line-height:1.8}@media(max-width:800px){.preview-layout{grid-template-columns:1fr}.preview-guide{position:static}.preview-shell{padding:20px 14px}.preview-header{align-items:flex-start}.preview-header h1{font-size:21px}.submit-card{flex-wrap:wrap}}
</style>
