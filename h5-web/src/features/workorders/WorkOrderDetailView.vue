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
import { actionFor, statusLabels, workOrderApi, type History, type WorkOrder, type WorkOrderStatus } from './work-orders'
import shopHero from '@/assets/repair-shop-service-hero.webp'
import ownerHero from '@/assets/owner-progress-hero.webp'

const materialRevision=ref(0),route=useRoute(),router=useRouter(),id=String(route.params.id),api=workOrderApi(httpClient)
const order=ref<WorkOrder>(),history=ref<History[]>([]),historyTotal=ref(0),historyPage=ref(1),historyBusy=ref(false),timeline=ref<HTMLOListElement>(),reason=ref(''),busy=ref(false),loading=ref(false),error=ref('')
// The detail endpoint already exposes workflowNode to both owner and repair-shop roles.
// The richer /workflow endpoint is restricted to customer service and administrators.
const workflowNodes=[{key:'CREATE',label:'建单'},{key:'DISPATCH_ARRIVAL',label:'派单到店'},{key:'SHOP_QUOTE',label:'网点报价'},{key:'QUOTE_REVIEW',label:'报价审核'},{key:'INSURER_ASSESSMENT',label:'保险核损'},{key:'REPAIR',label:'维修中'},{key:'RECEIPT',label:'收车'},{key:'FUNDS',label:'资金流动'},{key:'COMPLETE',label:'完成'}]
const workflowIndex=computed(()=>workflowNodes.findIndex(node=>node.key===order.value?.workflowNode))
function historyStatus(value?:string){return value?(statusLabels[value as WorkOrderStatus]||value):'未记录'}
function historyTime(value:string){return new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false})}
const isShop=computed(()=>currentUser.value?.roles.includes('REPAIR_SHOP')===true)
const roleHero=computed(()=>isShop.value?shopHero:ownerHero)
const assignmentVersion=computed(()=>order.value?.currentAssignment?.assignmentVersion)
const sections=computed(()=>isShop.value
  ? [{key:'overview',label:'概览与动作'},{key:'quotation',label:'报价授权'},{key:'materials',label:'上传反馈'},{key:'repair',label:'维修进度'},{key:'settlement',label:'结算'},{key:'complaint',label:'投诉'},{key:'history',label:'历史'}]
  : [{key:'overview',label:'案件进度'},{key:'progress',label:'维修进度'},{key:'photos',label:'维修照片'},{key:'receipt',label:['WAITING_INSURER_PAYMENT','WAITING_SHOP_SETTLEMENT','COMPLETED'].includes(order.value?.status||'')?'评价':'收车确认'},{key:'history',label:'历史'}])
const activeSection=computed(()=>!isShop.value&&route.query.section==='complaint'?'complaint':sections.value.some(item=>item.key===String(route.query.section))?String(route.query.section):'overview')
watch([workflowIndex,activeSection],([index,section])=>{if(index>1&&section==='overview')timeline.value?.scrollTo({left:(index-1)*104})},{flush:'post'})
function goSection(section:string){void router.replace({path:route.path,query:{section}})}
async function load(){loading.value=true;error.value='';try{order.value=await api.detail(id);const result=await api.history(id,1);history.value=result.records;historyTotal.value=result.total;historyPage.value=1}catch(e){error.value=e instanceof Error?e.message:'案件加载失败'}finally{loading.value=false}}
async function loadHistory(page:number){if(historyBusy.value)return;historyBusy.value=true;try{const result=await api.history(id,page);history.value=result.records;historyTotal.value=result.total;historyPage.value=page}catch(e){showToast(e instanceof Error?e.message:'状态记录加载失败')}finally{historyBusy.value=false}}
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

    <section v-if="activeSection==='overview'" class="overview-panel"><div class="workflow-card"><div class="section-title"><div><p class="eyebrow">CASE FLOW</p><h2>案件进度</h2></div><span v-if="workflowIndex>=0">{{ workflowIndex+1 }} / {{ workflowNodes.length }}</span></div><p class="workflow-summary">{{ workflowIndex>=0?`当前：${workflowNodes[workflowIndex]?.label} · ${statusLabels[order.status]}`:`${statusLabels[order.status]} · 进度待核对` }}</p><ol v-if="workflowIndex>=0" ref="timeline" class="mobile-workflow" aria-label="案件工作进度"><li v-for="(node,index) in workflowNodes" :key="node.key" :class="index<workflowIndex?'done':index===workflowIndex?'current':'locked'" :aria-current="index===workflowIndex?'step':undefined"><span class="node-dot" aria-hidden="true"><svg v-if="index<workflowIndex" viewBox="0 0 20 20"><path d="m4 10 4 4 8-8"/></svg><span v-else>{{ index+1 }}</span></span><strong>{{ node.label }}</strong><small>{{ index<workflowIndex?'已经过':index===workflowIndex?'当前阶段':'尚未到达' }}</small></li></ol><p v-if="workflowIndex>=0" class="scroll-hint">左右滑动查看全部阶段</p></div><CellGroup inset><Cell title="当前状态" :value="statusLabels[order.status]"/><Cell v-if="isShop" title="车主" :value="order.ownerName||'未填写'"/><Cell v-if="isShop" title="联系信息" :value="order.ownerPhone||'未提供'"/><Cell title="车辆" :value="`${order.vehicleBrand||''} ${order.vehicleModel||''}`.trim()||'未填写'"/><Cell title="事故位置" :value="order.accidentAddress||'未填写详细地址'"/><Cell title="事故说明" :label="order.accidentDescription||'未填写'"/></CellGroup>
      <section v-if="isShop&&['PENDING_ACCEPTANCE','PENDING_ARRIVAL','ARRIVAL_EXCEPTION'].includes(order.status)" class="surface-section action-panel"><h2>{{ order.status==='PENDING_ACCEPTANCE'?'接单处理':order.status==='PENDING_ARRIVAL'?'车辆到店':'到店异常处理' }}</h2><Field v-model="reason" :label="order.status==='PENDING_ACCEPTANCE'?'拒绝原因':'处理说明'" type="textarea" :placeholder="order.status==='PENDING_ACCEPTANCE'?'拒绝时必填':'异常时填写原因'"/><Button v-if="order.status==='PENDING_ACCEPTANCE'" block plain :loading="busy" @click="action('reject')">拒绝接单</Button><Button v-if="order.status==='PENDING_ACCEPTANCE'" block type="primary" :loading="busy" @click="action('accept')">接受案件</Button><Button v-if="order.status==='PENDING_ARRIVAL'" block plain :loading="busy" @click="action('exception')">记录到店异常</Button><Button v-if="order.status==='ARRIVAL_EXCEPTION'" block plain :loading="busy" @click="action('continue')">继续等待</Button><Button v-if="['PENDING_ARRIVAL','ARRIVAL_EXCEPTION'].includes(order.status)" block type="primary" :loading="busy" @click="action('arrive')">确认车辆到店</Button></section>
      <section v-else class="surface-section"><h2>案件服务</h2><Button v-if="actionFor(order.status,isShop?'REPAIR_SHOP':'OWNER').section!=='overview'" block type="primary" @click="goSection(actionFor(order.status,isShop?'REPAIR_SHOP':'OWNER').section)">{{ actionFor(order.status,isShop?'REPAIR_SHOP':'OWNER').label }}</Button></section>
    </section>

    <QuotationPanel v-if="isShop&&activeSection==='quotation'" :case-id="id" :refresh-token="materialRevision" @changed="materialRevision++;load()"/>
    <MaterialsPanel v-if="activeSection==='materials'||activeSection==='photos'" @changed="materialRevision++;load()" :case-id="id" :status="order.status" :assignment-version="order.currentAssignment?.assignmentVersion"/>
    <RepairPanel v-if="activeSection==='repair'||activeSection==='progress'||activeSection==='receipt'" :case-id="id" :refresh-token="materialRevision" @changed="materialRevision++;load()"/>
    <FundsPanel v-if="isShop&&activeSection==='settlement'" :case-id="id" :refresh-token="materialRevision"/>
    <ComplaintPanel v-if="activeSection==='complaint'" :case-id="id" :status="order.status" :refresh-token="materialRevision"/>
    <section v-if="activeSection==='history'" class="surface-section history-panel"><div class="section-title"><div><p class="eyebrow">CASE RECORD</p><h2>状态记录</h2></div><span>共 {{ historyTotal }} 条</span></div><div v-if="history.length" class="history-list"><article v-for="item in history" :key="item.id" class="history-entry"><div class="history-top"><span class="history-marker" aria-hidden="true"></span><time :datetime="item.occurredAt">{{ historyTime(item.occurredAt) }}</time></div><strong class="history-action">{{ item.action }}</strong><div class="history-transition"><span>{{ historyStatus(item.fromStatus) }}</span><span aria-hidden="true">→</span><span>{{ historyStatus(item.toStatus) }}</span></div><p v-if="item.reason" class="history-reason"><span>原因</span>{{ item.reason }}</p></article></div><p v-else class="history-empty">暂无状态记录</p><div v-if="historyTotal>20" class="history-pages"><Button size="small" plain :disabled="historyBusy||historyPage<=1" @click="loadHistory(historyPage-1)">上一页</Button><span>第 {{ historyPage }} 页</span><Button size="small" plain :disabled="historyBusy||historyPage*20>=historyTotal" @click="loadHistory(historyPage+1)">下一页</Button></div></section>
  </template></div></template>

<style scoped>
.detail-skeleton{padding:24px 20px}.case-head{position:relative;display:grid;grid-template-columns:minmax(0,1fr) auto;gap:10px 12px;min-height:164px;overflow:hidden;margin:10px 12px 0;padding:22px 20px;border:1px solid rgba(255,255,255,.94);border-radius:16px;background:linear-gradient(145deg,rgba(255,255,255,.9),rgba(238,235,229,.8));box-shadow:inset 0 1px 0 #fff,0 16px 38px rgba(65,55,38,.085);backdrop-filter:blur(14px) saturate(.8)}.case-head::before{position:absolute;inset:0;background-image:linear-gradient(rgba(65,55,38,.04) 1px,transparent 1px),linear-gradient(90deg,rgba(65,55,38,.04) 1px,transparent 1px);background-size:20px 20px;content:'';mask-image:linear-gradient(90deg,transparent 35%,#000)}.case-head>*{position:relative}.case-role-hero{position:absolute;right:-22px;bottom:-118px;width:190px;height:285px;object-fit:cover;object-position:center 72%;filter:grayscale(.7) sepia(.05) contrast(.9);opacity:.22;mask-image:linear-gradient(90deg,transparent,#000 35%);pointer-events:none}.case-head h1{max-width:11em;margin:5px 0 4px;font-size:22px;line-height:1.3}.case-head .mono{margin:0;color:var(--color-text-secondary);font-size:12px}.next-action{grid-column:1/-1;margin:6px 0 0;padding-top:10px;border-top:1px solid var(--color-border);color:var(--color-accent);font-size:13px;font-weight:600}.overview-panel{padding-top:10px}.action-panel .van-button{margin-top:10px;min-height:48px}.section-title{display:flex;align-items:center;justify-content:space-between}.section-title h2{margin:0}.section-title span{color:var(--color-text-secondary);font-size:12px}
.workflow-card{margin:0 12px 12px;padding:18px 16px 12px;border:1px solid var(--color-border);border-radius:14px;background:#fff;box-shadow:var(--shadow-surface)}
.workflow-card .section-title h2,.history-panel .section-title h2{margin:3px 0 0;font-size:20px;line-height:1.35;letter-spacing:-.02em}
.workflow-summary{margin:12px 0;color:var(--color-text-secondary);font-size:13px;line-height:1.5}
.mobile-workflow{display:flex;gap:0;overflow-x:auto;overscroll-behavior-x:contain;scroll-snap-type:x proximity;scrollbar-width:thin;list-style:none;margin:0 -4px;padding:7px 4px 12px}
.mobile-workflow li{position:relative;flex:0 0 104px;min-width:0;scroll-snap-align:start;color:var(--color-text-secondary)}
.mobile-workflow li:not(:last-child)::before{position:absolute;top:14px;left:38px;right:0;height:1px;background:var(--color-border-strong);content:''}
.node-dot{position:relative;z-index:1;display:grid;width:29px;height:29px;place-items:center;border:2px solid #8b918e;border-radius:50%;background:#f4f4f0;color:#616b67;font-size:12px;font-variant-numeric:tabular-nums}
.node-dot svg{width:16px;height:16px;fill:none;stroke:currentColor;stroke-width:2}
.mobile-workflow strong{display:block;margin-top:8px;color:var(--color-text);font-size:12px;font-weight:600;white-space:nowrap}
.mobile-workflow small{display:block;margin-top:4px;font-size:10px;line-height:1.4}
.mobile-workflow .done .node-dot{border-color:#47665f;background:#e2ede9;color:#355b4b}
.mobile-workflow .current .node-dot{border-color:#176346;background:#55de9d;color:#163a2c;box-shadow:0 0 0 4px #55de9d28}
.mobile-workflow .current strong{color:#176346}
.scroll-hint{margin:0;color:var(--color-text-secondary);font-size:11px;text-align:right}
.history-panel{padding:18px 16px;border:1px solid var(--color-border);border-radius:14px;background:#fff;box-shadow:var(--shadow-surface)}
.history-list{margin-top:16px;border-top:1px solid var(--color-border)}
.history-entry{padding:16px 0;border-bottom:1px solid var(--color-border)}
.history-top{display:flex;align-items:center;gap:9px;color:var(--color-text-secondary);font-size:11px}
.history-marker{width:8px;height:8px;flex:0 0 auto;border-radius:50%;background:var(--color-success);box-shadow:0 0 0 3px var(--color-success-soft)}
.history-top time{font-variant-numeric:tabular-nums}
.history-action{display:block;margin:8px 0 10px;overflow-wrap:anywhere;font-size:14px;line-height:1.5}
.history-transition{display:flex;align-items:center;flex-wrap:wrap;gap:7px;color:var(--color-text-secondary);font-size:12px}
.history-transition span:last-child{padding:4px 8px;border-radius:5px;background:var(--color-success-soft);color:var(--color-success);font-weight:600}
.history-reason{display:flex;gap:8px;margin:10px 0 0;color:var(--color-text-secondary);font-size:12px;line-height:1.6;overflow-wrap:anywhere}
.history-reason span{flex:0 0 auto;color:var(--color-text)}
.history-empty{margin:22px 0;color:var(--color-text-secondary);text-align:center}
.history-pages{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:16px;color:var(--color-text-secondary);font-size:12px}
@media(prefers-reduced-transparency:reduce){.case-head{background:#fff;backdrop-filter:none}}
</style>
