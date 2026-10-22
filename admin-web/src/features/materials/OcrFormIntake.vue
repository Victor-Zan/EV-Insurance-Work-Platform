<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { httpClient } from '@/shared/http/client'
import type { DraftForm } from '../workorders/work-orders'
import { draftLabels, evidenceText, mergeOcrDraft, ocrErrors, type OcrFieldConflict } from './ocr-form'
import { ocrIntakeApi, type IntakeJob, type OcrCandidate } from './ocr-intake'

const props=defineProps<{form:DraftForm;initialJobId?:string;locked:boolean}>()
const emit=defineEmits<{merge:[value:DraftForm];blocked:[value:boolean]}>()
const api=ocrIntakeApi(httpClient),fileInput=ref<HTMLInputElement>(),selected=ref<File>(),job=ref<IntakeJob>(),program=ref<OcrCandidate>({}),provider=ref(''),image=ref(''),message=ref(''),busy=ref(false),sourceError=ref(false),conflicts=ref<OcrFieldConflict[]>([]),decisions=ref<Record<string,string>>({}),uploadKey=ref(crypto.randomUUID())
const programLoaded=ref(false)
const pending=computed(()=>Boolean(job.value&&(job.value.state!=='SUCCEEDED'||!programLoaded.value||!image.value)))
const mapped=computed(()=>Object.entries(program.value.fields??{}).filter(([key])=>key in draftLabels&&key!=='accidentRegionId'))
const independent=computed(()=>Object.entries(program.value.fields??{}).filter(([key])=>!(key in draftLabels)))
let timer:ReturnType<typeof setTimeout>|undefined,disposed=false
function blocked(){emit('blocked',sourceError.value||busy.value||pending.value||conflicts.value.length>0||Boolean(selected.value&&!job.value))}
function fail(error:unknown){message.value=error instanceof Error?error.message:'识别操作失败'}
function apply(){const result=mergeOcrDraft(props.form,program.value.fields??{});emit('merge',result.merged);conflicts.value=result.conflicts;decisions.value={};blocked()}
function resolve(field:keyof DraftForm,incoming:string,useOcr:boolean){
  // Deliberately read the current form here, not an earlier copy from upload time.
  if(useOcr)emit('merge',{...props.form,[field]:incoming})
  decisions.value[field]=useOcr?'USE_OCR':'KEEP_CURRENT'
  conflicts.value=conflicts.value.filter(item=>item.field!==field);blocked()
}
async function load(){
  if(!job.value)return
  const id=job.value.id,next=await api.job(id);if(disposed||job.value?.id!==id)return
  const firstSuccess=!programLoaded.value&&next.state==='SUCCEEDED'
  job.value=next
  if(firstSuccess){const result=await api.program(next.id);if(disposed||job.value?.id!==id||programLoaded.value)return;program.value=result;programLoaded.value=true;apply();message.value='已填入空白字段；冲突字段请选择。请对照原图核对当前表单。'}
  if(next.state==='FAILED')message.value=ocrErrors[next.errorCode??'']??'识别失败，可查看原任务；不会自动重新调用百度'
  clearTimeout(timer);if(['QUEUED','RUNNING'].includes(next.state))timer=setTimeout(()=>{void refresh()},2000)
  blocked()
}
async function refresh(){try{await load();if(job.value&&!image.value)await showImage(job.value.id)}catch(error){fail(error)}finally{blocked()}}
async function showImage(id:string){const blob=await api.image(id);if(disposed||job.value?.id!==id)return;if(image.value)URL.revokeObjectURL(image.value);image.value=URL.createObjectURL(blob)}
async function upload(){
  if(!selected.value||busy.value||job.value)return
  busy.value=true;message.value='';blocked()
  try{
    const next=await api.upload(selected.value,uploadKey.value);if(disposed)return
    job.value={...next,state:'QUEUED'};await showImage(next.id);await load()
  }catch(error){fail(error)}finally{busy.value=false;blocked()}
}
function choose(event:Event){
  selected.value=(event.target as HTMLInputElement).files?.[0];uploadKey.value=crypto.randomUUID();blocked()
  if(selected.value&&selected.value.size>10*1024*1024){message.value='图片超过10 MiB，请重新选择或解除关联后手工建单';return}
  if(selected.value)void upload()
}
function detach(){
  if(!window.confirm('保留当前表单内容并解除本次识别关联？原任务和证据不会删除；已发起的任务可能仍在处理，不会自动重新调用。'))return
  clearTimeout(timer);job.value=undefined;selected.value=undefined;sourceError.value=false;programLoaded.value=false;program.value={};conflicts.value=[];decisions.value={};message.value='已保留当前表单，可继续手工填写';if(image.value)URL.revokeObjectURL(image.value);image.value='';if(fileInput.value)fileInput.value.value='';blocked()
}
async function createReviewedDraft(form:DraftForm){
  if(!job.value)return undefined
  if(conflicts.value.length||pending.value||busy.value)throw new Error('请等待识别完成并处理字段冲突')
  // The parent freezes the current form and this panel during this explicit save/submit.
  return api.createReviewedDraft(job.value,form,decisions.value)
}
defineExpose({createReviewedDraft})
onMounted(async()=>{
  try{
    provider.value=(await httpClient.get<{data:{provider:string}}>('/ocr/configuration')).data.data.provider
    if(props.initialJobId){
      busy.value=true;blocked();const next=await api.job(props.initialJobId);if(disposed)return
      if(!next.intakeId)throw new Error('只能继续当前账号有权访问的建单前任务')
      job.value={...next,state:'QUEUED'};await showImage(next.id);await load()
    }
  }catch(error){fail(error);if(props.initialJobId&&!job.value)sourceError.value=true}finally{busy.value=false;blocked()}
})
onBeforeUnmount(()=>{disposed=true;clearTimeout(timer);if(image.value)URL.revokeObjectURL(image.value)})
</script>

<template><section class="form-intake" aria-label="建单前单据识别">
  <h3>上传单据识别</h3><p>候选结果，须人工核对。无需先创建案件，识别只填当前空白字段；已有内容由你选择。</p>
  <p>当前供应商：{{ job?.provider || provider || '正在读取配置' }}。{{ (job?.provider||provider)==='MOCK_V1'?'Mock 合成演示，不识别真实图片。':'百度模式会发送所选单据并消耗识别额度。' }}</p>
  <FileDropZone hint="一次1张JPG/JPEG/PNG，≤10 MiB。与原按钮相同：选择或拖入即发起识别，真实模式可能消耗额度。" :max-bytes="10485760" :feedback="message" :disabled="locked||busy||Boolean(job)" :busy="busy"><input ref="fileInput" type="file" accept=".jpg,.jpeg,.png" aria-label="上传手写单据" hidden :disabled="locked||busy||Boolean(job)" @change="choose"></FileDropZone>
  <button type="button" :disabled="locked||busy||Boolean(job)||!provider" @click="fileInput?.click()">上传单据识别</button>
  <button v-if="selected&&!job" type="button" :disabled="busy||locked" @click="upload">继续上传（沿用幂等键）</button>
  <button v-if="job||selected||sourceError" type="button" :disabled="locked||busy" @click="detach">解除识别关联，保留当前表单</button>
  <p>JPG/JPEG/PNG，最多10 MiB；百度另有尺寸及编码大小限制。不会自动重试或扫描其他图片。</p>
  <p v-if="message" role="alert">{{ message }}</p>
  <template v-if="job"><p>任务状态：{{ job.state }} · {{ job.provider }} / {{ job.providerApi }}</p>
    <button type="button" :disabled="locked||busy" @click="refresh">刷新任务（不重新识别）</button>
    <div class="intake-columns"><div><h4>原单据（私有受控查看）</h4><img v-if="image" :src="image" alt="本次上传的原单据"><a v-if="image" :href="image" target="_blank" rel="noopener">放大查看原图</a></div>
      <div><h4>字段冲突：请选择后保存/提交</h4><p v-if="!conflicts.length">没有待处理冲突。请在下方原建单表单修正和补充。</p>
        <fieldset v-for="conflict in conflicts" :key="conflict.field" :disabled="locked"><legend>{{ draftLabels[conflict.field] }}</legend><p>当前表单：{{ form[conflict.field] }}</p><p>识别候选：{{ conflict.incoming }}</p><p>{{ evidenceText(program.evidence?.[conflict.field]) }}</p><button type="button" @click="resolve(conflict.field,conflict.incoming,false)">保留当前{{ draftLabels[conflict.field] }}</button><button type="button" @click="resolve(conflict.field,conflict.incoming,true)">采用识别{{ draftLabels[conflict.field] }}</button></fieldset>
        <ul><li v-for="warning in program.warnings" :key="warning">{{ warning }}</li></ul>
        <details v-if="job.state==='SUCCEEDED'"><summary>程序提取 / 来源证据 / 人工修订对照</summary><p v-for="([key,value]) in mapped" :key="key"><strong>{{ draftLabels[key as keyof DraftForm] }}</strong> · 程序提取：{{ value }} · 当前表单：{{ form[key as keyof DraftForm] }} · {{ form[key as keyof DraftForm]===value?'与候选一致':'人工保留/修订' }}<small>{{ evidenceText(program.evidence?.[key]) }}</small></p><p v-for="([key,value]) in independent" :key="key">独立候选 {{ key }}：{{ value }}（不自动写入基础字段）</p></details>
      </div></div>
    <details v-if="program.lines"><summary>百度原文（保持不变）</summary><ol><li v-for="line in program.lines" :key="line.line">{{ line.text }} · 坐标 {{ line.location??'未返回' }}</li></ol></details>
    <details v-if="program.items?.length"><summary>维修明细候选（不生成正式报价）</summary><div class="table-scroll"><table><thead><tr><th>配件</th><th>数量</th><th>单价</th><th>单据金额</th><th>来源证据</th></tr></thead><tbody><tr v-for="(item,index) in program.items" :key="index"><td>{{ item.description }}</td><td>{{ item.quantity }}</td><td>{{ item.unitPrice }}</td><td>{{ item.lineAmount }}</td><td>{{ evidenceText(item.evidence) }}</td></tr></tbody></table></div></details>
  </template>
  <p>识别不到的内容保持空白；维修清单编号不是报案号，单据日期不是事故时间。事故区域须人工选择。点击下方保存/提交即确认当前核对表单，保留人工快照和来源；不自动生成报价或派单。</p>
</section></template>

<style scoped>.form-intake{margin:0 0 20px;padding:16px;border:1px solid var(--color-border);border-radius:8px}.form-intake p{overflow-wrap:anywhere;font-size:13px}.form-intake button{margin:4px 8px 4px 0;padding:6px}.intake-columns{display:grid;grid-template-columns:1fr;gap:16px}.intake-columns img{width:100%;max-height:420px;object-fit:contain}.form-intake fieldset{margin:8px 0}.form-intake small{display:block;color:var(--color-text-secondary)}.table-scroll{overflow:auto}.table-scroll td,.table-scroll th{padding:6px;border-bottom:1px solid var(--color-border);text-align:left}.form-intake details{margin-top:12px}</style>
