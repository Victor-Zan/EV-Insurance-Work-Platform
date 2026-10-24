<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton, ElDialog, ElInput, ElMessage, ElMessageBox, ElOption, ElSelect, ElTag } from 'element-plus'
import { currentUser, httpClient } from '@/shared/http/client'
import { ApiError } from '@/shared/http/create-client'
import { vMagnetic } from '@/shared/ui/magnetic'
import {nodeNames} from './workflow'
import TextFormIntake from './TextFormIntake.vue'
import ScenePhotoIntake from './ScenePhotoIntake.vue'
import IntakeFields from './IntakeFields.vue'
import {saveIntake,openSavedCase,createManualDraft} from './intake-save'
import {intakeMissing,validateSceneFiles} from './intake'
import CaseSummaryPanel from './CaseSummaryPanel.vue'
import { statusLabels, workOrderApi, type DraftForm, type WorkOrder, type WorkOrderStatus } from './work-orders'

const api=workOrderApi(httpClient),router=useRouter(),route=useRoute(),loading=ref(false),rows=ref<WorkOrder[]>([]),total=ref(0),page=ref(1),error=ref('')
const status=ref(route.query.status==='COMPLETED'?'':String(route.query.status??'')),query=ref(String(route.query.query??'')),selected=computed(()=>String(route.query.selected??'')),dialog=ref(route.query.create==='1'),saving=ref(false)
const excludeCompleted=true
const queueStatusLabels=Object.fromEntries(Object.entries(statusLabels).filter(([key])=>key!=='COMPLETED'))
const ocrPanel=ref<InstanceType<typeof TextFormIntake>>(),ocrBlocked=ref(false),ocrSession=ref(0)
const isAdmin=computed(()=>currentUser.value?.roles.includes('ADMIN')===true)
const isStaff=computed(()=>currentUser.value?.roles.some(role=>role==='ADMIN'||role==='CUSTOMER_SERVICE')===true)
const creationError=ref(''),submitKey=ref(crypto.randomUUID()),saveStage=ref<'DRAFT'|'PHOTOS'|'SUBMIT'>('DRAFT')
const exporting=ref(false),sceneFiles=ref<File[]>([]),photoPanel=ref<InstanceType<typeof ScenePhotoIntake>>(),pendingDraft=ref<WorkOrder>()
function folderPhotos(files:File[]){try{const combined=[...sceneFiles.value,...files];validateSceneFiles(combined);sceneFiles.value=combined}catch(error){ocrBlocked.value=true;ElMessage.error(error instanceof Error?error.message:'照片导入失败')}}
const blank=():DraftForm=>({surveyorName:'',surveyorPhone:'',vehicleColor:'',insuranceCompany:'',claimNo:'',claimReportedAt:'',ownerName:'',ownerPhone:'',policyNo:'',vehicleBrand:'',vehicleModel:'',vehicleVin:'',vehiclePlate:'',vehicleOtherIdentifier:'',accidentAt:'',accidentRegionId:'',accidentAddress:'',accidentDescription:''})
const form=reactive<DraftForm>(blank())
const manualAttempt: {form?:DraftForm}={}
const missingFields=computed(()=>intakeMissing(form,sceneFiles.value.length))
async function load(){loading.value=true;error.value='';try{const result=await api.list(page.value,status.value,query.value.trim(),excludeCompleted);rows.value=result.records;total.value=result.total}catch(cause){error.value=cause instanceof Error?cause.message:'加载失败'}finally{loading.value=false}}
function filter(){page.value=1;void router.replace({path:'/work-orders',query:{excludeCompleted:'true',...(query.value.trim()?{query:query.value.trim()}:{}),...(status.value?{status:status.value}:{})}});void load()}
function reset(){query.value='';status.value='';filter()}
function select(row:WorkOrder){void router.push({path:'/work-orders',query:{...route.query,selected:row.id}})}
function keySelect(event:KeyboardEvent,row:WorkOrder){if(event.key==='Enter'){event.preventDefault();if(event.ctrlKey||event.metaKey)void router.push(`/work-orders/${row.id}`);else select(row)}}
async function create(submitFormal=false){
  if(saving.value)return
  if(ocrBlocked.value){creationError.value='已导入资料尚待核对；请处理冲突，或明确取消文字导入后继续手填。';return}
  if(submitFormal&&missingFields.value.length){creationError.value='请补齐：'+missingFields.value.join('、');ElMessage.warning(creationError.value);return}
  creationError.value='';saving.value=true
  let saved:WorkOrder
  try{
    saved=await saveIntake({api,pending:pendingDraft.value,form:{...form},formal:submitFormal,submitKey:submitKey.value,
      create:async snapshot=>await ocrPanel.value?.createReviewedDraft(snapshot)??await createManualDraft({api,attempt:manualAttempt,form:snapshot,key:submitKey.value,saved:order=>{pendingDraft.value=order}}),
      upload:async id=>{await photoPanel.value?.upload(id)},saved:order=>{pendingDraft.value=order},stage:value=>{saveStage.value=value},
      confirmDuplicate:async()=>{const result=await ElMessageBox.prompt('发现疑似重复案件。请输入继续创建原因：','疑似重复案件',{inputValidator:value=>Boolean(value.trim())||'原因必填'});return result.value.trim()}})
  }catch(error){
    const phase=saveStage.value==='PHOTOS'?'草稿已保存，物损照片上传未完成。':saveStage.value==='SUBMIT'?'草稿与材料已保存，正式提交结果需核对；重试前会先读取案件状态。':'草稿保存未完成或结果尚未确认。'
    creationError.value=error instanceof ApiError&&error.code==='DUPLICATE_CASE'
      ?'草稿与材料已保存。'+error.message+'（'+error.code+'）'
      :phase+(error instanceof Error?error.message:'操作已取消')+(error instanceof ApiError?'（'+error.code+'）':'');ElMessage.error(creationError.value);return
  }finally{saving.value=false}
  ElMessage.success(saved.status==='DRAFT'?'草稿已保存':'案件已正式建单')
  dialog.value=false;pendingDraft.value=undefined;manualAttempt.form=undefined;sceneFiles.value=[];Object.assign(form,blank());ocrBlocked.value=false;ocrSession.value++;submitKey.value=crypto.randomUUID()
  await load()
  try{await openSavedCase(router,saved.id,href=>window.location.assign(href))}catch{creationError.value='案件已保存，详情暂未打开，请从案件队列进入。';ElMessage.warning(creationError.value)}
}
async function configureDuplicates(){try{const current=await api.configuration();const days=await ElMessageBox.prompt('疑似重复的相近/短期天数（1—365）','重复判断配置',{inputValue:String(current.possibleDuplicateDays),inputPattern:/^(?:[1-9]|[1-9]\d|[12]\d\d|3[0-5]\d|36[0-5])$/,inputErrorMessage:'请输入 1—365 的整数'});const reason=await ElMessageBox.prompt('请输入本次修改原因','重复判断配置',{inputValidator:value=>Boolean(value.trim())||'原因必填'});await api.updateConfiguration(Number(days.value),reason.value.trim());ElMessage.success('重复判断时间窗口已更新')}catch(error){if(error instanceof Error&&error.message)ElMessage.error(error.message)}}
async function exportReconciliation(){exporting.value=true;try{const blob=await api.reconciliation(page.value,status.value,query.value.trim());const url=URL.createObjectURL(blob);const link=document.createElement('a');link.href=url;link.download=`work-order-reconciliation-page-${page.value}.xlsx`;link.click();URL.revokeObjectURL(url)}catch(cause){ElMessage.error(cause instanceof Error?cause.message:'对账表导出失败')}finally{exporting.value=false}}
onMounted(load)
watch(()=>route.query.query,value=>{if(String(value??'')!==query.value){query.value=String(value??'');page.value=1;void load()}})
watch(()=>route.query.status,value=>{const next=value==='COMPLETED'?'':String(value??'');if(next!==status.value){status.value=next;page.value=1;void load()}})
</script>

<template>
  <div class="work-page case-workspace"><ElButton v-if="selected" class="mobile-back" text @click="router.push({path:'/work-orders',query:{...(excludeCompleted?{excludeCompleted:'true'}:{}),...(query?{query}:{}),...(status?{status}:{})}})">← 返回案件队列</ElButton><header class="case-header"><div><p class="eyebrow">CASE OPERATIONS / 案件中心</p><h1>案件队列</h1><p>筛选并选择案件，右侧查看当前阶段、授权条件和处理历史。</p></div><div class="actions"><ElButton v-if="isAdmin" @click="configureDuplicates">重复判断配置</ElButton><ElButton v-magnetic type="primary" @click="dialog=true">新建草稿</ElButton></div></header>
    <div class="case-columns" :class="{ 'has-selection': selected }"><section class="case-queue" aria-label="案件队列"><form class="filters" @submit.prevent="filter"><ElInput v-model="query" clearable placeholder="案件号 / 报案号 / 手机号" aria-label="案件关键字"/><ElSelect v-model="status" clearable placeholder="全部状态" aria-label="案件状态"><ElOption v-for="(label,key) in queueStatusLabels" :key="key" :label="label" :value="key"/></ElSelect><ElButton type="primary" native-type="submit">筛选</ElButton><ElButton @click="reset">重置</ElButton></form><p class="queue-count">稳定排序 · 第 {{ page }} 页 / 共 {{ total }} 条 <ElButton v-if="isStaff" :loading="exporting" text @click="exportReconciliation">导出本页资金对账 XLSX</ElButton></p>
      <div v-if="error" class="queue-state" role="alert">{{ error }} <ElButton @click="load">重试</ElButton></div><div v-else-if="loading" class="queue-state" aria-busy="true">正在加载案件…</div><div v-else-if="!rows.length" class="queue-state">当前条件下没有案件。<ElButton text @click="reset">清除筛选</ElButton></div>
      <div v-else class="queue-rows" role="listbox" aria-label="案件结果"><div v-for="row in rows" :key="row.id" class="queue-row" :class="{ 'is-selected': selected===row.id }" role="option" tabindex="0" :aria-selected="selected===row.id" @click="select(row)" @keydown="keySelect($event,row)"><div class="queue-row-top"><strong>{{ row.businessNo || '草稿未编号' }}</strong><ElTag size="small" effect="plain">{{ statusLabels[row.status as WorkOrderStatus] }}</ElTag></div><p>{{ row.vehicleBrand || '未填写品牌' }} {{ row.vehicleModel }} · {{ row.ownerName || row.ownerPhone || '待补手机号' }}</p><small>{{ nodeNames[row.workflowNode??'']||statusLabels[row.status as WorkOrderStatus] }} · {{ row.workflowActor||'待核对' }} · {{ row.claimNo || '暂无报案号' }}</small><ElButton text size="small" @click.stop="router.push(`/work-orders/${row.id}`)">完整详情 ↗</ElButton></div></div>
      <div class="pager"><ElButton :disabled="loading||page<=1" @click="page--;load()">上一页</ElButton><span>{{ page }} / {{ Math.max(1,Math.ceil(total/20)) }}</span><ElButton :disabled="loading||page*20>=total" @click="page++;load()">下一页</ElButton></div></section>
      <Transition name="case-panel" mode="out-in"><CaseSummaryPanel v-if="selected" :key="selected" :case-id="selected" class="selected-summary"/><aside v-else class="case-placeholder"><p>选择一个案件</p><h2>让下一步清晰可见</h2><p>右侧将显示真实状态、四项维修授权条件和最近处理记录。复杂操作仍在完整详情中进行。</p></aside></Transition></div>
  <ElDialog v-model="dialog" title="创建工单 / 新建案件草稿" top="4vh" width="min(1100px, 96vw)" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving"><div class="creation-layout with-ocr"><TextFormIntake :key="ocrSession" ref="ocrPanel" :form="form" :photos="sceneFiles" :locked="saving||Boolean(pendingDraft)||Boolean(manualAttempt.form)" @merge="Object.assign(form,$event)" @blocked="ocrBlocked=$event" @photos="folderPhotos"/><div><IntakeFields :form="form" :disabled="saving" @merge="Object.assign(form,$event)"/><p class="hint">支持纯手工建单，文字导入和OCR均非必经步骤。草稿可缺项；正式建单须填写案件号、车主手机号、车牌或车架号、查勘员姓名和电话，并上传至少1张现场物损照片。</p><p v-if="missingFields.length" class="hint" role="status">正式建单还缺：{{ missingFields.join('、') }}。仍可保存草稿。</p><p v-if="ocrBlocked" role="alert">文字资料尚待提取或核对，请处理冲突；不采用时可在左侧明确取消文字导入，保留手填信息。</p><ScenePhotoIntake ref="photoPanel" v-model="sceneFiles" :locked="saving"/><p v-if="creationError" role="alert">{{ creationError }}</p><p v-if="manualAttempt.form&&!pendingDraft" class="hint" role="status">上次保存响应尚未确认；重试会先核对同一草稿，再保存当前修改，不重复建单。</p><p v-if="pendingDraft?.status==='DRAFT'" class="hint" role="status">草稿已保留。可补齐信息或重试上传，不会重复建单。</p></div></div><template #footer><ElButton :disabled="saving" @click="dialog=false">取消</ElButton><ElButton :loading="saving" :disabled="ocrBlocked" @click="create(false)">保存草稿</ElButton><ElButton type="primary" :loading="saving" :disabled="ocrBlocked" @click="create(true)">提交正式工单</ElButton></template></ElDialog>
  </div>
</template>

<style scoped>.case-header{display:flex;justify-content:space-between;align-items:end;gap:18px;margin:0 0 20px}.case-header h1{margin:2px 0 4px;font-size:26px}.case-header p:last-child{margin:0}.eyebrow{margin:0;color:var(--color-accent);font-size:10px;font-weight:700;letter-spacing:.14em}.case-columns{display:grid;grid-template-columns:minmax(420px,42%) minmax(0,58%);gap:18px;align-items:start}.case-queue{min-width:0;border:1px solid var(--color-border);border-radius:12px;background:#fff;overflow:hidden}.filters{display:flex;flex-wrap:wrap;gap:8px;padding:16px}.filters .el-input{min-width:160px;flex:1}.filters .el-select{width:140px}.filters .el-button{margin:0}.queue-count{margin:0;padding:0 16px 10px;color:var(--color-text-secondary);font-size:12px}.queue-rows{max-height:calc(100vh - 300px);overflow:auto}.queue-row{position:relative;padding:13px 16px;border-top:1px solid var(--color-border);cursor:pointer;transition:background-color 160ms ease,transform 190ms cubic-bezier(.16,1,.3,1)}.queue-row:hover,.queue-row:focus-visible{background:var(--color-canvas);transform:translate3d(3px,0,0)}.queue-row.is-selected{background:var(--color-accent-soft)}.queue-row.is-selected::before{position:absolute;top:0;bottom:0;left:0;width:3px;background:var(--color-accent);content:''}.queue-row-top{display:flex;justify-content:space-between;align-items:center;gap:8px}.queue-row p{margin:5px 0 0;font-size:13px}.queue-row small{display:block;color:var(--color-text-secondary)}.queue-row .el-button{margin:4px 0 0;padding:0}.queue-state{padding:42px 16px;color:var(--color-text-secondary)}.pager{display:flex;justify-content:space-between;align-items:center;gap:8px;padding:12px 16px;border-top:1px solid var(--color-border);font-variant-numeric:tabular-nums}.case-placeholder{padding:68px 40px;border:1px dashed var(--color-border-strong);border-radius:14px;color:var(--color-text-secondary)}.case-placeholder h2{color:var(--color-text)}.case-panel-enter-active,.case-panel-leave-active{transition:opacity 190ms cubic-bezier(.16,1,.3,1),transform 220ms cubic-bezier(.16,1,.3,1)}.case-panel-enter-from{opacity:0;transform:translate3d(12px,0,0)}.case-panel-leave-to{opacity:0;transform:translate3d(-5px,0,0)}.creation-layout.with-ocr{max-height:calc(88dvh - 130px);overflow:auto;display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1.3fr);gap:20px;align-items:start}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 20px}.wide{grid-column:1/-1}.hint{color:var(--color-text-secondary)}
@media(max-width:1439px){.case-columns{grid-template-columns:minmax(340px,360px) minmax(0,1fr)}}@media(max-width:1023px){.creation-layout.with-ocr{grid-template-columns:1fr}.case-columns{grid-template-columns:1fr}.case-columns.has-selection .case-queue{display:none}.queue-rows{max-height:none}.case-header{align-items:start}}@media(max-width:640px){.case-header{display:block}.case-header .actions{padding:12px 0}.form-grid{grid-template-columns:1fr}.wide{grid-column:auto}}
.mobile-back{display:none}@media(max-width:1023px){.mobile-back{display:inline-flex;margin:8px 0 0}}
</style>
