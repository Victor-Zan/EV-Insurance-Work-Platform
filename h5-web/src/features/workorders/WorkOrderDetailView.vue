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

const materialRevision=ref(0),route=useRoute(),router=useRouter(),id=String(route.params.id),api=workOrderApi(httpClient)
const order=ref<WorkOrder>(),history=ref<History[]>([]),historyTotal=ref(0),historyPage=ref(1),historyBusy=ref(false),reason=ref(''),busy=ref(false),loading=ref(false),error=ref('')
// The detail endpoint already exposes workflowNode to both owner and repair-shop roles.
// The richer /workflow endpoint is restricted to customer service and administrators.
const workflowNodes=[{key:'CREATE',label:'建单'},{key:'DISPATCH_ARRIVAL',label:'派单到店'},{key:'SHOP_QUOTE',label:'网点报价'},{key:'QUOTE_REVIEW',label:'报价审核'},{key:'INSURER_ASSESSMENT',label:'保险核损'},{key:'REPAIR',label:'维修中'},{key:'RECEIPT',label:'收车'},{key:'FUNDS',label:'资金流动'},{key:'COMPLETE',label:'完成'}]
const workflowIndex=computed(()=>workflowNodes.findIndex(node=>node.key===order.value?.workflowNode))
function historyStatus(value?:string){return value?(statusLabels[value as WorkOrderStatus]||value):'未记录'}
function historyTime(value:string){return new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false})}
const isShop=computed(()=>currentUser.value?.roles.includes('REPAIR_SHOP')===true)
const assignmentVersion=computed(()=>order.value?.currentAssignment?.assignmentVersion)
const moduleSections=computed(()=>isShop.value
  ? [{key:'quotation',label:'报价授权'},{key:'materials',label:'上传反馈'},{key:'repair',label:'维修进度'},{key:'settlement',label:'结算'},{key:'complaint',label:'投诉'}]
  : [{key:'progress',label:'维修进度'},{key:'photos',label:'维修照片'},{key:'receipt',label:['WAITING_INSURER_PAYMENT','WAITING_SHOP_SETTLEMENT','COMPLETED'].includes(order.value?.status||'')?'评价':'收车确认'}])
const currentTask=computed(()=>actionFor(order.value?.status??'DRAFT',isShop.value?'REPAIR_SHOP':'OWNER'))
const currentTaskSection=computed(()=>currentTask.value.section==='overview'?'task':currentTask.value.section)
const activeSection=computed(()=>{
  const requested=String(route.query.section??'')
  if(requested==='overview'||requested==='data')return 'data'
  if(requested==='history')return 'history'
  if(requested==='complaint')return 'complaint'
  return moduleSections.value.some(item=>item.key===requested)?requested:currentTaskSection.value
})
const activePrimary=computed(()=>'data'===activeSection.value?'data':'history'===activeSection.value?'history':'task')
const activeModuleLabel=computed(()=>activeSection.value===currentTaskSection.value?currentTask.value.label:moduleSections.value.find(item=>item.key===activeSection.value)?.label??(activeSection.value==='complaint'?'投诉':'当前任务'))
const caseFacts=computed(()=>[order.value?.ownerName,order.value?.vehiclePlate||[order.value?.vehicleBrand,order.value?.vehicleModel].filter(Boolean).join(' '),order.value?.insuranceCompany].filter(Boolean).join(' · ')||'案件信息待补充')
function goPrimary(section:'task'|'data'|'history'){
  if(section==='task')void router.replace({path:route.path})
  else void router.replace({path:route.path,query:{section}})
}
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
watch(()=>route.query.section,value=>{
  const section=String(value??'')
  if(section&&section!=='overview'&&section!=='data'&&section!=='history'&&section!=='complaint'&&!moduleSections.value.some(item=>item.key===section))void router.replace({path:route.path})
},{immediate:true})
onMounted(load)
</script>

<template>
  <div class="mobile-page case-detail-page">
    <NavBar title="案件详情" left-arrow @click-left="router.push('/work-orders')"/>
    <Skeleton v-if="loading&&!order" title :row="8" class="detail-skeleton"/>
    <p v-if="error" class="inline-error" role="alert">{{ error }} <Button size="small" @click="load">重试</Button></p>
    <template v-if="order">
      <header class="case-summary-card">
        <div class="case-summary-line">
          <div class="case-identity">
            <button class="back-link" type="button" @click="router.push('/work-orders')">返回案件</button>
            <div><h1>{{ order.businessNo||'案件' }}</h1><p>{{ caseFacts }}</p></div>
          </div>
          <div class="case-tools">
            <Tag plain>{{ statusLabels[order.status] }}</Tag>
            <Button size="small" plain :loading="loading" @click="materialRevision++;load()">刷新</Button>
            <details class="more-menu"><summary>更多</summary><div><button v-for="section in moduleSections" :key="section.key" type="button" @click="goSection(section.key)">{{ section.label }}</button><button v-if="!isShop" type="button" @click="goSection('complaint')">投诉</button></div></details>
          </div>
        </div>
        <ol v-if="workflowIndex>=0" class="mobile-workflow" aria-label="案件工作进度">
          <li v-for="(node,index) in workflowNodes" :key="node.key" :class="index<workflowIndex?'done':index===workflowIndex?'current':'locked'" :aria-current="index===workflowIndex?'step':undefined">
            <span class="node-dot" aria-hidden="true"><svg v-if="index<workflowIndex" viewBox="0 0 20 20"><path d="m4 10 4 4 8-8"/></svg><span v-else>{{ index+1 }}</span></span>
            <strong>{{ node.label }}</strong><small>{{ index<workflowIndex?'已完成':index===workflowIndex?'当前阶段':'尚未到达' }}</small>
          </li>
        </ol>
        <p v-else class="workflow-unavailable">{{ statusLabels[order.status] }} · 进度待核对</p>
      </header>

      <nav class="case-primary-nav" aria-label="案件详情">
        <button :aria-current="activePrimary==='task'?'page':undefined" @click="goPrimary('task')">当前任务</button>
        <button :aria-current="activePrimary==='data'?'page':undefined" @click="goPrimary('data')">案件资料</button>
        <button :aria-current="activePrimary==='history'?'page':undefined" @click="goPrimary('history')">记录</button>
      </nav>

      <template v-if="activePrimary==='task'">
        <section class="task-context-card"><p>当前任务</p><h2>{{ activeModuleLabel }}</h2><span><i aria-hidden="true"></i>{{ activeSection===currentTaskSection?'当前阶段':'查看业务资料' }}</span></section>
        <section v-if="activeSection==='task'" class="case-module-card action-panel">
          <h2>{{ order.status==='PENDING_ACCEPTANCE'?'接单处理':order.status==='PENDING_ARRIVAL'?'车辆到店':order.status==='ARRIVAL_EXCEPTION'?'到店异常处理':'当前阶段' }}</h2>
          <template v-if="isShop&&['PENDING_ACCEPTANCE','PENDING_ARRIVAL','ARRIVAL_EXCEPTION'].includes(order.status)">
            <Field v-model="reason" :label="order.status==='PENDING_ACCEPTANCE'?'拒绝原因':'处理说明'" type="textarea" :placeholder="order.status==='PENDING_ACCEPTANCE'?'拒绝时必填':'异常时填写原因'"/>
            <Button v-if="order.status==='PENDING_ACCEPTANCE'" block plain :loading="busy" @click="action('reject')">拒绝接单</Button><Button v-if="order.status==='PENDING_ACCEPTANCE'" block type="primary" :loading="busy" @click="action('accept')">接受案件</Button>
            <Button v-if="order.status==='PENDING_ARRIVAL'" block plain :loading="busy" @click="action('exception')">记录到店异常</Button><Button v-if="order.status==='ARRIVAL_EXCEPTION'" block plain :loading="busy" @click="action('continue')">继续等待</Button><Button v-if="['PENDING_ARRIVAL','ARRIVAL_EXCEPTION'].includes(order.status)" block type="primary" :loading="busy" @click="action('arrive')">确认车辆到店</Button>
          </template>
          <p v-else>当前没有需要在移动端执行的操作，可查看案件资料或记录。</p>
        </section>
        <QuotationPanel v-if="isShop&&activeSection==='quotation'" :case-id="id" :refresh-token="materialRevision" @changed="materialRevision++;load()"/>
        <MaterialsPanel v-if="activeSection==='materials'||activeSection==='photos'" @changed="materialRevision++;load()" :case-id="id" :status="order.status" :assignment-version="order.currentAssignment?.assignmentVersion"/>
        <RepairPanel :receipt-mode="activeSection==='receipt'" v-if="activeSection==='repair'||activeSection==='progress'||activeSection==='receipt'" :case-id="id" :refresh-token="materialRevision" @changed="materialRevision++;load()"/>
        <FundsPanel v-if="isShop&&activeSection==='settlement'" :case-id="id" :refresh-token="materialRevision"/>
        <ComplaintPanel v-if="activeSection==='complaint'" :case-id="id" :status="order.status" :refresh-token="materialRevision"/>
      </template>

      <section v-if="activeSection==='data'" class="case-module-card case-data-card">
        <div class="section-title"><div><p class="eyebrow">CASE DATA</p><h2>案件资料</h2></div></div>
        <CellGroup><Cell title="当前状态" :value="statusLabels[order.status]"/><Cell v-if="isShop" title="车主" :value="order.ownerName||'未填写'"/><Cell v-if="isShop" title="联系信息" :value="order.ownerPhone||'未提供'"/><Cell title="车辆" :value="[order.vehiclePlate,order.vehicleBrand,order.vehicleModel].filter(Boolean).join(' · ')||'未填写'"/><Cell title="保险公司" :value="order.insuranceCompany||'未填写'"/><Cell title="事故位置" :value="order.accidentAddress||'未填写详细地址'"/><Cell title="事故说明" :label="order.accidentDescription||'未填写'"/></CellGroup>
      </section>

      <section v-if="activeSection==='history'" class="case-module-card history-panel">
        <div class="section-title"><div><p class="eyebrow">CASE RECORD</p><h2>状态记录</h2></div><span>共 {{ historyTotal }} 条</span></div>
        <div v-if="history.length" class="history-list"><article v-for="item in history" :key="item.id" class="history-entry"><div class="history-top"><span class="history-marker" aria-hidden="true"></span><time :datetime="item.occurredAt">{{ historyTime(item.occurredAt) }}</time></div><strong class="history-action">{{ item.action }}</strong><div class="history-transition"><span>{{ historyStatus(item.fromStatus) }}</span><span aria-hidden="true">→</span><span>{{ historyStatus(item.toStatus) }}</span></div><p v-if="item.reason" class="history-reason"><span>原因</span>{{ item.reason }}</p></article></div>
        <p v-else class="history-empty">暂无状态记录</p><div v-if="historyTotal>20" class="history-pages"><Button size="small" plain :disabled="historyBusy||historyPage<=1" @click="loadHistory(historyPage-1)">上一页</Button><span>第 {{ historyPage }} 页</span><Button size="small" plain :disabled="historyBusy||historyPage*20>=historyTotal" @click="loadHistory(historyPage+1)">下一页</Button></div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.detail-skeleton{padding:24px 20px}.case-detail-page{overflow-x:hidden}.case-summary-card,.case-module-card,.task-context-card{margin:12px;padding:18px 16px;border:1px solid var(--case-border);border-radius:15px;background:var(--case-surface);box-shadow:var(--shadow-surface)}
.case-summary-card{color:var(--case-text);background:var(--case-surface-muted)}.case-summary-line{display:flex;align-items:flex-start;justify-content:space-between;gap:14px}.case-identity{display:flex;min-width:0;align-items:flex-start;gap:12px}.back-link{flex:0 0 auto;margin-top:2px;padding:5px 0;border:0;background:transparent;color:var(--case-text-secondary);font-size:12px}.case-identity h1{margin:0;font-size:20px;line-height:1.35;overflow-wrap:anywhere}.case-identity p{margin:5px 0 0;color:var(--case-text-secondary);font-size:12px;line-height:1.5;overflow-wrap:anywhere}.case-tools{display:flex;flex:0 0 auto;align-items:center;gap:7px}.case-tools :deep(.van-button){border-color:#b8c8c5;background:#fff;color:#29423f}.more-menu{position:relative}.more-menu summary{padding:7px 0;color:#29423f;font-size:12px;cursor:pointer;white-space:nowrap}.more-menu>div{position:absolute;z-index:4;top:34px;right:0;display:grid;width:148px;padding:8px;border:1px solid var(--case-border);border-radius:10px;background:#fff;box-shadow:0 14px 34px #35585024}.more-menu button{min-height:38px;padding:8px;border:0;border-radius:7px;background:transparent;color:#29423f;text-align:left}.more-menu button:active{background:#edf3f1}
.mobile-workflow{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:20px 12px;list-style:none;margin:22px 0 2px;padding:0}.mobile-workflow li{position:relative;min-width:0;color:#718084}.mobile-workflow li:not(:nth-child(3n))::after{position:absolute;top:14px;left:37px;width:calc(100% - 28px);height:1px;background:#bcc9c7;content:''}.node-dot{position:relative;z-index:1;display:grid;width:29px;height:29px;place-items:center;border:2px solid #8b999b;border-radius:50%;background:#f7faf9;color:#617174;font-size:12px;font-variant-numeric:tabular-nums}.node-dot svg{width:16px;height:16px;fill:none;stroke:currentColor;stroke-width:2}.mobile-workflow strong{display:block;margin-top:7px;color:#354c50;font-size:12px;line-height:1.35}.mobile-workflow small{display:block;margin-top:3px;font-size:10px;line-height:1.35}.mobile-workflow .done .node-dot{border-color:#536e68;background:#e6efec;color:#355b4b}.mobile-workflow .current .node-dot{border-color:#176346;background:#8ce2ae;color:#173d2d;box-shadow:0 0 0 4px #55de9d24}.mobile-workflow .current strong{color:#176346}.workflow-unavailable{margin:20px 0 0;color:#64777b;font-size:12px}
.case-primary-nav{display:flex;gap:6px;margin:16px 12px}.case-primary-nav button{min-height:42px;padding:8px 14px;border:1px solid transparent;border-radius:8px;background:transparent;color:#566669;font-weight:600}.case-primary-nav button[aria-current=page]{border-color:#b7c5c2;background:#fff;color:#263f3b;box-shadow:var(--shadow-surface)}
.task-context-card{background:var(--case-surface-muted);color:var(--case-text)}.task-context-card>p{margin:0 0 8px;color:var(--case-text-secondary);font-size:12px}.task-context-card h2{margin:0 0 10px;font-size:22px}.task-context-card>span{display:flex;align-items:center;gap:9px;color:var(--case-text-secondary);font-size:13px}.task-context-card i{width:12px;height:12px;border:2px solid var(--case-success);border-radius:50%;background:#8ce2ae}.case-module-card h2{margin:0 0 14px;font-size:20px}.case-module-card>p{color:var(--color-text-secondary);line-height:1.65}.action-panel :deep(.van-button){margin-top:10px;min-height:48px}.section-title{display:flex;align-items:center;justify-content:space-between}.section-title h2{margin:3px 0 0}.section-title>span{color:var(--color-text-secondary);font-size:12px}.case-data-card :deep(.van-cell-group){margin-top:14px}.case-data-card :deep(.van-cell){padding-inline:0;border-bottom:1px solid var(--case-border);background:transparent}.case-data-card :deep(.van-cell:last-child){border-bottom:0}
.history-list{margin-top:16px;border-top:1px solid #dce5e4}.history-entry{padding:16px 0;border-bottom:1px solid #dce5e4}.history-top{display:flex;align-items:center;gap:9px;color:var(--color-text-secondary);font-size:11px}.history-marker{width:8px;height:8px;flex:0 0 auto;border-radius:50%;background:var(--color-success);box-shadow:0 0 0 3px var(--color-success-soft)}.history-top time{font-variant-numeric:tabular-nums}.history-action{display:block;margin:8px 0 10px;overflow-wrap:anywhere;font-size:14px;line-height:1.5}.history-transition{display:flex;align-items:center;flex-wrap:wrap;gap:7px;color:var(--color-text-secondary);font-size:12px}.history-transition span:last-child{padding:4px 8px;border-radius:5px;background:var(--color-success-soft);color:var(--color-success);font-weight:600}.history-reason{display:flex;gap:8px;margin:10px 0 0;color:var(--color-text-secondary);font-size:12px;line-height:1.6;overflow-wrap:anywhere}.history-reason span{flex:0 0 auto;color:var(--color-text)}.history-empty{margin:22px 0;color:var(--color-text-secondary);text-align:center}.history-pages{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:16px;color:var(--color-text-secondary);font-size:12px}
@media(max-width:430px){.case-summary-line{display:block}.case-tools{margin-top:14px}.case-identity{display:block}.back-link{margin-bottom:7px}.mobile-workflow{gap:18px 8px}.mobile-workflow strong{font-size:11px}.mobile-workflow li:not(:nth-child(3n))::after{width:calc(100% - 22px)}.case-primary-nav{justify-content:space-between}.case-primary-nav button{padding-inline:11px}}
</style>
