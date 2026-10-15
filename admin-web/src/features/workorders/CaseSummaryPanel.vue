<script setup lang="ts">
import WorkflowTimeline from './WorkflowTimeline.vue'
import type {Workflow} from './workflow'
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElButton, ElTag } from 'element-plus'
import { currentUser, httpClient } from '@/shared/http/client'
import { localTime } from '@/shared/ui/presentation'
import { quotationApi, type QuoteCase } from '@/features/quotation/quotation'
import { statusLabels, workOrderApi, type History, type WorkOrder } from './work-orders'

const props = defineProps<{caseId: string}>(), router = useRouter(), api = workOrderApi(httpClient)
const order = ref<WorkOrder>(), history = ref<History[]>([]), quote = ref<QuoteCase>(), loading = ref(false), error = ref('')
const workflow=ref<Workflow>()
let request = 0
async function load() {
  const current = ++request; loading.value = true; error.value = ''; order.value = undefined; quote.value = undefined; workflow.value=undefined; history.value = []
  try {
    const [detail, timeline] = await Promise.all([api.detail(props.caseId), api.history(props.caseId)])
    if (current !== request) return
    order.value = detail; history.value = timeline.records.slice(0, 4)
    const flow=(await httpClient.get<{data:Workflow}>('/work-orders/'+props.caseId+'/workflow')).data.data;if(current!==request)return;workflow.value=flow
    if (!['DRAFT','CANCELLED','CLOSED'].includes(detail.status)) {
      try { const result = await quotationApi(httpClient, props.caseId).get(); if (current === request) quote.value = result }
      catch { /* 案件摘要仍可用；报价详情由完整页面处理。 */ }
    }
  } catch (cause) { if (current === request) error.value = cause instanceof Error ? cause.message : '读取案件失败' }
  finally { if (current === request) loading.value = false }
}
watch(() => props.caseId, () => { void load() }, {immediate:true})
const gateItems: {key:keyof QuoteCase['gate'];label:string}[] = [
  {key:'lossAssessmentFileUploaded',label:'核损单已上传'},
  {key:'finalAssessmentAmountRecorded',label:'最终核损金额已录入'},
  {key:'insurerConfirmationRecorded',label:'保险公司已确认'},
  {key:'customerServiceConfirmationRecorded',label:'客服确认'},
]
const historyActions:Record<string,string>={SUBMIT:'提交正式案件',DISPATCH:'派单',REASSIGN:'改派',CANCEL_ASSIGNMENT:'取消派单',ACCEPT:'网点接单',REJECT:'网点拒单',ARRIVAL_EXCEPTION:'到店异常',CONTINUE_WAITING:'继续等待',ARRIVE:'车辆到店'}
</script>

<template>
  <aside class="case-summary" aria-label="选中案件工作面板" :aria-busy="loading">
    <div v-if="loading" class="summary-state">正在读取案件摘要…</div>
    <div v-else-if="error" class="summary-state" role="alert"><p>{{ error }}</p><ElButton @click="load">刷新案件</ElButton></div>
    <template v-else-if="order"><header class="summary-head"><p class="eyebrow">SELECTED CASE / 选中案件</p><h2>{{ order.businessNo || '案件草稿' }}</h2><ElTag effect="plain">{{ statusLabels[order.status] }}</ElTag></header>
      <WorkflowTimeline v-if="workflow" :workflow="workflow"/><dl class="summary-facts"><div><dt>车辆</dt><dd>{{ order.vehicleBrand || '未填写' }} {{ order.vehicleModel }}</dd></div><div><dt>车主</dt><dd>{{ order.ownerName || '未填写' }} · {{ order.ownerPhone || '未填写' }}</dd></div><div><dt>保险公司</dt><dd>{{ order.insuranceCompany || '未填写' }}</dd></div><div><dt>报案号</dt><dd class="mono">{{ order.claimNo || '未填写' }}</dd></div></dl>
      <section class="summary-block"><h3>当前阶段</h3><p class="phase-label">{{ statusLabels[order.status] }}</p><p class="summary-muted">当前派单版本：{{ order.currentAssignment?.assignmentVersion ?? '无当前派单' }}</p></section>
      <section v-if="quote" class="summary-block"><h3>维修授权 · 四项条件</h3><ul class="gate-list"><li v-for="item in gateItems" :key="item.key"><span :class="quote.gate[item.key] ? 'gate-yes' : 'gate-no'">{{ quote.gate[item.key] ? '✓' : '—' }}</span>{{ item.label }}<strong>{{ quote.gate[item.key] ? '已满足' : '未满足' }}</strong></li></ul><p v-if="!quote.gate.allowed" class="gate-note">条件未齐，网点不能开始维修。请在完整详情中处理可执行项；最终由服务端复核。</p><p v-else class="gate-note success">四项条件均已满足；开修动作仍由当前网点执行。</p></section>
      <section class="summary-block"><h3>最近处理历史</h3><p v-if="!history.length" class="summary-muted">暂无状态记录。</p><ol v-else class="history-list"><li v-for="entry in history" :key="entry.id"><strong>{{ historyActions[entry.action] ?? entry.action }}</strong><span>{{ localTime(entry.occurredAt) }}</span><small v-if="entry.reason">{{ entry.reason }}</small></li></ol></section>
      <div class="summary-actions"><ElButton type="primary" @click="router.push(`/work-orders/${order.id}`)">打开完整详情</ElButton><ElButton v-if="currentUser?.roles.includes('CUSTOMER_SERVICE') && quote && !quote.gate.allowed" @click="router.push(`/work-orders/${order.id}?section=quotation`)">查看报价核损</ElButton></div>
    </template><div v-else class="summary-state">选择左侧案件，查看摘要和下一步。</div>
  </aside>
</template>

<style scoped>
.case-summary :deep(.workflow-timeline){grid-template-columns:repeat(3,minmax(0,1fr));gap:16px}.case-summary :deep(.workflow-timeline li:nth-child(3n):before){display:none}.case-summary{position:relative;min-width:0;overflow:hidden;padding:26px 30px;border:1px solid rgba(255,255,255,.94);border-radius:16px;background:linear-gradient(145deg,rgba(255,255,255,.91),rgba(242,245,245,.82));box-shadow:inset 0 1px 0 #fff,inset 1px 0 0 rgba(255,255,255,.72),0 20px 48px rgba(82,110,110,.105);backdrop-filter:blur(14px) saturate(.82)}.case-summary::before{position:absolute;inset:0;background:linear-gradient(115deg,rgba(255,255,255,.38),transparent 26%,transparent 72%,rgba(255,255,255,.14));content:'';pointer-events:none}.case-summary>*{position:relative}.summary-head h2{margin:6px 0 12px;font-size:23px;overflow-wrap:anywhere}.eyebrow{margin:0;color:var(--color-accent);font-size:10px;font-weight:700;letter-spacing:.14em}.summary-facts{display:grid;grid-template-columns:1fr 1fr;gap:14px 24px;margin:22px 0}.summary-facts div{min-width:0}.summary-facts dt{color:var(--color-text-secondary);font-size:12px}.summary-facts dd{margin:3px 0 0;overflow-wrap:anywhere}.mono{font-variant-numeric:tabular-nums}.summary-block{padding:20px 0;border-top:1px solid var(--color-border)}.summary-block h3{margin:0 0 10px;font-size:15px}.summary-muted{color:var(--color-text-secondary);font-size:13px}.phase-label{display:inline-block;margin:0;padding:6px 10px;border:1px solid rgba(255,255,255,.72);border-radius:6px;background:rgba(228,234,234,.8);box-shadow:inset 0 1px 0 rgba(255,255,255,.9);color:var(--color-accent)}.gate-list{display:grid;gap:9px;margin:0;padding:0;list-style:none}.gate-list li{display:flex;align-items:center;gap:8px;font-size:13px}.gate-list strong{margin-left:auto;font-size:12px;font-weight:500;color:var(--color-text-secondary)}.gate-yes,.gate-no{display:grid;place-items:center;width:18px;height:18px;border-radius:50%}.gate-yes{background:#e7f0ed;color:#3f6d62}.gate-no{background:#f2ebdd;color:#8a5c20}.gate-note{margin:12px 0 0;padding:10px;border-radius:6px;background:#f2ebdd;color:#72501f;font-size:12px}.gate-note.success{background:#e7f0ed;color:#3f6d62}.history-list{display:grid;gap:10px;margin:0;padding-left:18px}.history-list li{font-size:13px}.history-list span{float:right;color:var(--color-text-secondary);font-size:12px}.history-list small{display:block;color:var(--color-text-secondary)}.summary-actions{display:flex;flex-wrap:wrap;gap:8px}.summary-actions .el-button{margin:0}.summary-state{padding:40px 8px;color:var(--color-text-secondary)}
@media(max-width:1100px){.summary-facts{grid-template-columns:1fr}}@media(prefers-reduced-transparency:reduce){.case-summary{background:#fff;backdrop-filter:none}}
</style>
