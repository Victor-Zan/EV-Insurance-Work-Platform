<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { currentUser, httpClient } from '@/shared/http/client'
import { workOrderApi, type DraftForm, type RegionOption } from '../workorders/work-orders'
import { blankOcrDraft, draftLabels, evidenceText, ocrDraftPayload, ocrErrors, prefillOcrDraft } from './ocr-form'

interface Item { description?:string; quantity?:string; unitPrice?:string; lineAmount?:string; [key:string]:unknown }
interface Candidate { fields?:Record<string,string>; items?:Item[]; warnings?:string[]; lines?:{line:number;text:string;location?:Record<string,number>;probability?:unknown}[]; evidence?:Record<string,unknown>; draft?:unknown; [key:string]:unknown }
interface Job {id:string;provider:string;providerApi:string;providerVersion:string;state:string;attempts:number;errorCode?:string;candidate?:Candidate;reviewVersion:number;confirmed:boolean;intakeId?:string;retryable:boolean}
const props=withDefaults(defineProps<{jobId:string;writable?:boolean}>(),{writable:true})
const router=useRouter(),job=ref<Job>(),candidate=ref<Candidate>({}),program=ref<Candidate>({}),draft=ref<DraftForm>(blankOcrDraft()),regions=ref<RegionOption[]>([]),history=ref<unknown>(),historyPage=ref(1),historyTotal=ref(0),image=ref(''),raw=ref(''),message=ref(''),busy=ref(false),dirty=ref(false)
const cs=computed(()=>currentUser.value?.roles.includes('CUSTOMER_SERVICE')===true),editable=computed(()=>cs.value&&props.writable&&job.value?.state==='SUCCEEDED')
let timer:ReturnType<typeof setTimeout>|undefined
async function data<T>(path:string){return (await httpClient.get<{data:T}>(path)).data.data}
async function run(action:()=>Promise<void>){busy.value=true;message.value='';try{await action()}catch(e){message.value=e instanceof Error?e.message:'操作失败'}finally{busy.value=false}}
async function loadHistory(){const result=await data<{records:unknown[];total:number}>(`/ocr/${props.jobId}/history?page=${historyPage.value}&size=10`);history.value=result.records;historyTotal.value=result.total}
async function load(){
  const next=await data<Job>(`/ocr/${props.jobId}`)
  if(!job.value||job.value.id!==next.id||job.value.reviewVersion!==next.reviewVersion||job.value.state!==next.state){
    candidate.value=structuredClone(next.candidate??{});draft.value=prefillOcrDraft(candidate.value.fields??{})
    if(candidate.value.draft){const saved=candidate.value.draft as Record<string,unknown>;draft.value={...prefillOcrDraft(saved),accidentRegionId:typeof saved.accidentRegionId==='number'?String(saved.accidentRegionId):''}}
    dirty.value=false;program.value=await data<Candidate>(`/ocr/${props.jobId}/program`);await loadHistory()
  }
  job.value=next;clearTimeout(timer)
  if(['QUEUED','RUNNING'].includes(next.state))timer=setTimeout(()=>{void run(load)},2000)
}
async function init(){clearTimeout(timer);job.value=undefined;raw.value='';if(image.value)URL.revokeObjectURL(image.value);image.value='';if(!props.jobId)return;await load();const response=await httpClient.get(`/ocr/${props.jobId}/image`,{responseType:'blob'});image.value=URL.createObjectURL(response.data);regions.value=(await workOrderApi(httpClient).regions()).records}
async function save(){if(!job.value)return;const value={...candidate.value,origin:'HUMAN_REVISION',...(job.value.intakeId?{draft:ocrDraftPayload(draft.value)}:{})};await httpClient.post(`/ocr/${props.jobId}/review`,{expectedVersion:job.value.reviewVersion,candidate:value},{headers:{'Idempotency-Key':crypto.randomUUID()}});await load();message.value='人工修正版本已保存；尚未确认或正式提交'}
async function confirm(){if(!job.value)return;await httpClient.post(`/ocr/${props.jobId}/confirm`,{expectedVersion:job.value.reviewVersion});await load();message.value='当前快照已人工确认；未写入正式业务'}
async function createDraft(){if(!job.value)return;const response=await httpClient.post<{data:{id:string}}>(`/ocr/${props.jobId}/draft`,{expectedVersion:job.value.reviewVersion,draft:ocrDraftPayload(draft.value)});await router.push(`/work-orders/${response.data.data.id}`)}
async function retry(){if(!window.confirm('将重新调用原供应商；上次请求可能已消耗额度，重试可能再次计费。确认重试？'))return;await httpClient.post(`/ocr/${props.jobId}/retry`);await load()}
async function reextract(){if(!job.value)return;await httpClient.post(`/ocr/${props.jobId}/reextract`,{expectedVersion:job.value.reviewVersion},{headers:{'Idempotency-Key':crypto.randomUUID()}});await load()}
watch(()=>props.jobId,()=>{void run(init)},{immediate:true})
onBeforeUnmount(()=>{clearTimeout(timer);if(image.value)URL.revokeObjectURL(image.value)})
</script>
<template><section v-if="jobId" class="ocr-review"><h3>候选结果，须人工核对</h3><p role="alert">{{ message }}</p><button :disabled="busy" @click="run(load)">刷新任务</button>
<template v-if="job"><p>{{ job.provider==='MOCK_V1'?'Mock 合成结果，未识别真实图片':'百度真实 OCR' }} · {{ job.provider }} / {{ job.providerApi }} / {{ job.providerVersion }} · {{ job.state }} · 尝试 {{ job.attempts }} · 人工版本 {{ job.reviewVersion }} · {{ job.confirmed?'已确认快照':'未确认' }}</p><p v-if="job.errorCode" role="alert">{{ ocrErrors[job.errorCode]??'识别失败，请联系管理员' }}（{{ job.errorCode }}）</p><button v-if="cs&&writable&&job.retryable" :disabled="busy" @click="run(retry)">明确重试失败任务</button>
<div class="review-columns"><div><h4>原附件（受控查看）</h4><object v-if="image" :data="image" class="original" aria-label="原单据"><a :href="image" target="_blank" rel="noopener">查看原附件</a></object><h4>百度原文 / Mock 说明</h4><ol><li v-for="line in program.lines" :key="line.line">{{ line.text }}<small>行 {{ line.line+1 }} · 坐标 {{ line.location??'未返回' }} · 供应商实际置信信息 {{ line.probability??'未返回' }}</small></li></ol><p v-if="!program.lines">{{ program.otherReadableInformation }}</p></div>
<div><ul><li v-for="warning in candidate.warnings" :key="warning">{{ warning }}</li></ul><p>程序提取与人工修正分层保存在历史中。维修单号和单据日期不替代保险报案号或事故时间。</p>
<template v-if="job.intakeId"><h4>自动预填草稿表单 / 人工修正</h4><label v-for="(label,key) in draftLabels" :key="key">{{ label }}<select v-if="key==='accidentRegionId'" v-model="draft[key]" :disabled="!editable" @change="dirty=true"><option value="">待选择</option><option v-for="region in regions" :key="region.id" :value="String(region.id)">{{ region.name }}（{{ region.code }}）</option></select><input v-else v-model="draft[key]" :type="key==='accidentAt'||key==='claimReportedAt'?'datetime-local':'text'" :readonly="!editable" @input="dirty=true"><small>来源证据：{{ evidenceText(program.evidence?.[key]) }}</small></label></template>
<h4>独立字段候选{{ job.intakeId?'（不自动写入基础字段）':'' }}</h4><label v-for="(value,key) in candidate.fields" v-show="!job.intakeId||!(key in draftLabels)" :key="key">{{ key }}<input v-if="candidate.fields" v-model="candidate.fields[key]" :readonly="!editable" @input="dirty=true"><small>当前修订：{{ value }} · 最新程序候选值：{{ program.fields?.[key]??'未识别' }} · 来源 {{ evidenceText(program.evidence?.[key]) }}</small></label>
<h4>维修明细候选（不生成正式报价）</h4><div class="item-scroll"><table><thead><tr><th>配件名称</th><th>数量（缺失留空）</th><th>单价</th><th>单据金额</th><th>来源 / 程序计算值</th></tr></thead><tbody><tr v-for="(item,index) in candidate.items" :key="index"><td v-for="field in ['description','quantity','unitPrice','lineAmount'] as const" :key="field"><input v-model="item[field]" :readonly="!editable" @input="dirty=true"></td><td>{{ evidenceText(item.evidence) }}；程序初始计算值 {{ item.calculatedAmount??'未计算' }}</td></tr></tbody></table></div>
<div v-if="editable"><button v-if="job.provider!=='MOCK_V1'" :disabled="busy||dirty" @click="run(reextract)">重新整理已有原文（不调用百度）</button><button :disabled="busy" @click="run(save)">保存人工修正 / 当前表单</button><button :disabled="busy||dirty" @click="run(confirm)">确认当前候选快照</button><button v-if="job.intakeId" :disabled="busy||dirty||!job.confirmed||!candidate.draft" @click="run(createDraft)">用已确认表单创建草稿</button></div><p v-if="job.intakeId">缺少必填信息可以保留草稿；创建后在原案件页面完善并正式提交，执行原有校验、重复检测与审计。</p></div></div>
<details><summary>只读高级信息：原始返回 / 程序证据</summary><button v-if="job.provider!=='MOCK_V1'&&job.state==='SUCCEEDED'" :disabled="busy" @click="run(async()=>{raw=(await data<{text:string}>(`/ocr/${jobId}/raw`)).text})">授权读取百度原始返回</button><pre>{{ raw }}</pre><pre>{{ candidate }}</pre></details>
<details><summary>人工修正与确认历史</summary><pre>{{ history }}</pre><button :disabled="busy||historyPage===1" @click="historyPage--;run(loadHistory)">上一页</button><span>{{ historyPage }} / {{ historyTotal }} 条</span><button :disabled="busy||historyPage*10>=historyTotal" @click="historyPage++;run(loadHistory)">下一页</button></details></template></section></template>
<style scoped>.ocr-review{padding:16px;border:1px solid #ddd;margin-top:16px}.review-columns{display:grid;grid-template-columns:1fr 1fr;gap:20px}.original{width:100%;height:65vh;object-fit:contain}label{display:block;margin:12px 0}label input,label select{display:block;width:100%;box-sizing:border-box}small{display:block;color:#666;overflow-wrap:anywhere}button{margin:6px}pre{white-space:pre-wrap;overflow-wrap:anywhere}.item-scroll{overflow:auto}td input{width:100px}td{max-width:200px;overflow-wrap:anywhere}@media(max-width:900px){.review-columns{grid-template-columns:1fr}}</style>
