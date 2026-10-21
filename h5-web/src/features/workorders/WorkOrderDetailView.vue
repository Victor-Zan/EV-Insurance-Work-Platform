<script setup lang="ts">
import FundsPanel from '../funds/FundsPanel.vue'
import ComplaintPanel from '../complaint/ComplaintPanel.vue'
import RepairPanel from '../repair/RepairPanel.vue'
import QuotationPanel from '../quotation/QuotationPanel.vue'
import MaterialsPanel from '../materials/MaterialsPanel.vue'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Button, Cell, CellGroup, Field, NavBar, Skeleton, showSuccessToast, showToast, Tag } from 'vant'
import { currentUser, httpClient } from '@/shared/http/client'
import { actionFor, statusLabels, workOrderApi, type History, type WorkOrder } from './work-orders'
import shopHero from '@/assets/repair-shop-service-hero.webp'
import ownerHero from '@/assets/owner-progress-hero.webp'

const materialRevision=ref(0),route=useRoute(),router=useRouter(),id=String(route.params.id),api=workOrderApi(httpClient)
const order=ref<WorkOrder>(),history=ref<History[]>([]),historyTotal=ref(0),reason=ref(''),busy=ref(false),loading=ref(false),error=ref('')
const isShop=computed(()=>currentUser.value?.roles.includes('REPAIR_SHOP')===true)
const roleHero=computed(()=>isShop.value?shopHero:ownerHero)
const assignmentVersion=computed(()=>order.value?.currentAssignment?.assignmentVersion)
const sections=computed(()=>isShop.value
  ? [{key:'overview',label:'概览与动作'},{key:'quotation',label:'报价授权'},{key:'materials',label:'上传反馈'},{key:'repair',label:'维修进度'},{key:'settlement',label:'结算'},{key:'complaint',label:'投诉'},{key:'history',label:'历史'}]
  : [{key:'overview',label:'案件进度'},{key:'progress',label:'维修进度'},{key:'photos',label:'维修照片'},{key:'receipt',label:['WAITING_INSURER_PAYMENT','WAITING_SHOP_SETTLEMENT','COMPLETED'].includes(order.value?.status||'')?'评价':'收车确认'},{key:'history',label:'历史'}])
const activeSection=computed(()=>!isShop.value&&route.query.section==='complaint'?'complaint':sections.value.some(item=>item.key===String(route.query.section))?String(route.query.section):'overview')
function goSection(section:string){void router.replace({path:route.path,query:{section}})}
async function load(){loading.value=true;error.value='';try{order.value=await api.detail(id);const result=await api.history(id,1);history.value=result.records;historyTotal.value=result.total}catch(e){error.value=e instanceof Error?e.message:'案件加载失败'}finally{loading.value=false}}
async function action(operation:'accept'|'reject'|'exception'|'continue'|'arrive'){
  if((operation==='reject'||operation==='exception')&&!reason.value.trim())return showToast('请填写原因')
  const version=assignmentVersion.value;if(version===undefined)return showToast('当前派单版本缺失，请刷新后重试')
  busy.value=true
  try{
    if(operation==='accept')order.value=await api.accept(id,version)
    if(operation==='reject')order.value=await api.reject(id,version,reason.value.trim())
    if(operation==='exception')order.value=await api.exception(id,version,reason.value.trim())
    if(operation==='continue')order.value=await api.continueWaiting(id,version,reason.value.trim()||null)
    if(operation==='arrive')order.value=await api.arrive(id,version)
    reason.value='';showSuccessToast('操作成功');await load()
  }catch(e){showToast(e instanceof Error?e.message:'操作失败')}finally{busy.value=false}
}
watch(()=>route.query.section,value=>{if(value&&value!=='complaint'&&!sections.value.some(item=>item.key===String(value)))void router.replace({path:route.path,query:{section:'overview'}})},{immediate:true})
onMounted(load)
</script>

<template><div class="mobile-page"><NavBar :title="order?.businessNo||'案件详情'" left-arrow @click-left="router.push('/work-orders')"/><Skeleton v-if="loading&&!order" title :row="8" class="detail-skeleton"/><p v-if="error" class="inline-error" role="alert">{{ error }} <Button size="small" @click="load">重试</Button></p>
  <template v-if="order"><header class="case-head"><img class="case-role-hero" :src="roleHero" alt="" aria-hidden="true"><div><p class="eyebrow">{{ isShop?'SHOP CASE':'OWNER CASE' }}</p><h1>{{ order.vehicleBrand||'未填写品牌' }} {{ order.vehicleModel }}</h1><p class="mono">{{ order.businessNo||'案件' }}</p></div><Tag plain type="primary" size="large">{{ statusLabels[order.status] }}</Tag><p class="next-action">当前动作：{{ actionFor(order.status,isShop?'REPAIR_SHOP':'OWNER').label }}</p></header>
    <nav class="case-sections" aria-label="案件分区"><button v-for="section in sections" :key="section.key" :aria-current="activeSection===section.key?'page':undefined" @click="goSection(section.key)">{{ section.label }}</button></nav>

    <section v-if="activeSection==='overview'" class="overview-panel"><CellGroup inset><Cell title="当前状态" :value="statusLabels[order.status]"/><Cell v-if="isShop" title="车主" :value="order.ownerName||'未填写'"/><Cell v-if="isShop" title="联系信息" :value="order.ownerPhone||'未提供'"/><Cell title="车辆" :value="`${order.vehicleBrand||''} ${order.vehicleModel||''}`.trim()||'未填写'"/><Cell title="事故位置" :value="order.accidentAddress||'未填写详细地址'"/><Cell title="事故说明" :label="order.accidentDescription||'未填写'"/></CellGroup>
      <section v-if="isShop&&['PENDING_ACCEPTANCE','PENDING_ARRIVAL','ARRIVAL_EXCEPTION'].includes(order.status)" class="surface-section action-panel"><h2>{{ order.status==='PENDING_ACCEPTANCE'?'接单处理':order.status==='PENDING_ARRIVAL'?'车辆到店':'到店异常处理' }}</h2><Field v-model="reason" :label="order.status==='PENDING_ACCEPTANCE'?'拒绝原因':'处理说明'" type="textarea" :placeholder="order.status==='PENDING_ACCEPTANCE'?'拒绝时必填':'异常时填写原因'"/><Button v-if="order.status==='PENDING_ACCEPTANCE'" block plain :loading="busy" @click="action('reject')">拒绝接单</Button><Button v-if="order.status==='PENDING_ACCEPTANCE'" block type="primary" :loading="busy" @click="action('accept')">接受案件</Button><Button v-if="order.status==='PENDING_ARRIVAL'" block plain :loading="busy" @click="action('exception')">记录到店异常</Button><Button v-if="order.status==='ARRIVAL_EXCEPTION'" block plain :loading="busy" @click="action('continue')">继续等待</Button><Button v-if="['PENDING_ARRIVAL','ARRIVAL_EXCEPTION'].includes(order.status)" block type="primary" :loading="busy" @click="action('arrive')">确认车辆到店</Button></section>
      <section v-else class="surface-section"><h2>案件服务</h2><Button v-if="actionFor(order.status,isShop?'REPAIR_SHOP':'OWNER').section!=='overview'" block type="primary" @click="goSection(actionFor(order.status,isShop?'REPAIR_SHOP':'OWNER').section)">{{ actionFor(order.status,isShop?'REPAIR_SHOP':'OWNER').label }}</Button></section>
    </section>

    <QuotationPanel v-if="isShop&&activeSection==='quotation'" :case-id="id" :refresh-token="materialRevision" @changed="materialRevision++;load()"/>
    <MaterialsPanel v-if="activeSection==='materials'||activeSection==='photos'" @changed="materialRevision++;load()" :case-id="id" :status="order.status" :assignment-version="order.currentAssignment?.assignmentVersion"/>
    <RepairPanel v-if="activeSection==='repair'||activeSection==='progress'||activeSection==='receipt'" :case-id="id" :refresh-token="materialRevision" @changed="materialRevision++;load()"/>
    <FundsPanel v-if="isShop&&activeSection==='settlement'" :case-id="id" :refresh-token="materialRevision"/>
    <ComplaintPanel v-if="activeSection==='complaint'" :case-id="id" :status="order.status" :refresh-token="materialRevision"/>
    <section v-if="activeSection==='history'" class="surface-section history-panel"><div class="section-title"><h2>状态历史</h2><span>共 {{ historyTotal }} 条</span></div><CellGroup><Cell v-for="item in history" :key="item.id" :title="statusLabels[item.toStatus as keyof typeof statusLabels]||item.toStatus"><template #label><div class="history-action">{{ item.reason||item.action }}</div><time :datetime="item.occurredAt">{{ new Date(item.occurredAt).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false}) }}</time></template></Cell></CellGroup><p v-if="historyTotal>20">这里只显示最近 20 条，更多记录按服务端分页读取。</p></section>
  </template></div></template>

<style scoped>
.detail-skeleton{padding:24px 20px}.case-head{position:relative;display:grid;grid-template-columns:minmax(0,1fr) auto;gap:10px 12px;min-height:164px;overflow:hidden;margin:10px 12px 0;padding:22px 20px;border:1px solid rgba(255,255,255,.94);border-radius:16px;background:linear-gradient(145deg,rgba(255,255,255,.9),rgba(239,243,243,.8));box-shadow:inset 0 1px 0 #fff,0 16px 38px rgba(82,110,110,.085);backdrop-filter:blur(14px) saturate(.8)}.case-head::before{position:absolute;inset:0;background-image:linear-gradient(rgba(82,110,110,.04) 1px,transparent 1px),linear-gradient(90deg,rgba(82,110,110,.04) 1px,transparent 1px);background-size:20px 20px;content:'';mask-image:linear-gradient(90deg,transparent 35%,#000)}.case-head>*{position:relative}.case-role-hero{position:absolute;right:-22px;bottom:-118px;width:190px;height:285px;object-fit:cover;object-position:center 72%;filter:grayscale(.6) saturate(.4);opacity:.22;mask-image:linear-gradient(90deg,transparent,#000 35%);pointer-events:none}.case-head h1{max-width:11em;margin:5px 0 4px;font-size:22px;line-height:1.3}.case-head .mono{margin:0;color:var(--color-text-secondary);font-size:12px}.next-action{grid-column:1/-1;margin:6px 0 0;padding-top:10px;border-top:1px solid var(--color-border);color:var(--color-accent);font-size:13px;font-weight:600}.overview-panel{padding-top:10px}.action-panel .van-button{margin-top:10px;min-height:48px}.history-panel{background:#fff}.section-title{display:flex;align-items:center;justify-content:space-between}.section-title h2{margin:0}.section-title span{color:var(--color-text-secondary);font-size:12px}.history-action{overflow-wrap:anywhere}time{display:block;margin-top:4px;color:var(--color-text-secondary);font-variant-numeric:tabular-nums}
@media(prefers-reduced-transparency:reduce){.case-head{background:#fff;backdrop-filter:none}}
</style>
