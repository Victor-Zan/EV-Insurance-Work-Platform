<script setup lang="ts">
import {onMounted,onUnmounted,ref} from 'vue'
import {httpClient} from '@/shared/http/client'
import {adopt,isBlank,quoteOcrApi,ocrError,decimalProduct,candidateTotal,historicalMock,taskSource,type Candidate,type Job,type QuoteOcrDraft} from './quote-ocr'
const props=defineProps<{caseId:string;assignmentVersion:number;lines:QuoteOcrDraft[];locked:boolean}>(),emit=defineEmits<{merge:[lines:QuoteOcrDraft[]]}>(),api=quoteOcrApi(httpClient,props.caseId)
interface Page {job:Job;items:Candidate[];program:NonNullable<Job['candidate']>;image:string;raw:string;pageNo:number;targets:Record<number,string>;ignored:Record<number,boolean>;pending?:{body:string;key:string};rerun?:{version:number;key:string};rerunning?:boolean;loadedVersion?:number}
const pages=ref<Page[]>([]),provider=ref(''),message=ref(''),uploading=ref(false),input=ref<HTMLInputElement>(),category=ref('SHOP_QUOTE'),uploadKey=ref(crypto.randomUUID()),selected=ref<File>(),stopped=ref(false)
let disposed=false,timer:ReturnType<typeof setTimeout>|undefined,polls=0,allowAutoPrefill=true
const stateLabel:Record<string,string>={QUEUED:'排队中',RUNNING:'识别中',SUCCEEDED:'候选已返回，须核对',FAILED:'识别失败'}
async function choose(event:Event){if(uploading.value||props.locked)return;selected.value=(event.target as HTMLInputElement).files?.[0];uploadKey.value=crypto.randomUUID();if(selected.value)await upload()}
async function upload(){if(uploading.value||!selected.value)return;uploading.value=true;message.value='';try{
  provider.value=(await api.configuration()).provider
  const job=await api.upload(selected.value,props.assignmentVersion,pages.value.length+1,category.value,uploadKey.value)
  selected.value=undefined;if(input.value)input.value.value=''
  const existing=pages.value.find(page=>page.job.id===job.id)
  if(existing){existing.job=job;stopped.value=false;polls=0;await update(existing);schedule();return}
  const page:Page={job,items:[],program:{},image:'',raw:'',pageNo:job.pageNo??pages.value.length+1,targets:{},ignored:{}};pages.value.push(page)
  stopped.value=false;polls=0;await update(page);schedule()
}catch(e){message.value=e instanceof Error?e.message:'上传失败，原表单保留'}finally{uploading.value=false}}
async function update(page:Page){
  if(page.job.state==='SUCCEEDED'&&page.loadedVersion!==page.job.reviewVersion){
    page.program=await api.program(page.job.id);page.items=(page.program.items??[]).map(item=>({...item}));if(!page.image)page.image=URL.createObjectURL(await api.image(page.job.id));page.loadedVersion=page.job.reviewVersion
    // Compare the latest props, including edits made while the supplier was running.
    if(allowAutoPrefill&&!props.locked&&!historicalMock(page.job.provider,provider.value)&&isBlank(props.lines)&&page.items.length){let next:QuoteOcrDraft[]=[];page.items.forEach((item,index)=>{next=adopt(next,item,{jobId:page.job.id,itemIndex:index})});emit('merge',next);message.value='识别完成，请核对下方清单'}
    else message.value=historicalMock(page.job.provider,provider.value)?'此任务是历史模拟结果，未自动填入清单；请使用当前供应商重新上传识别。':'识别完成，表格内容已保留'
  }
}
function schedule(){if(timer)clearTimeout(timer);if(disposed||stopped.value||!pages.value.some(page=>['QUEUED','RUNNING'].includes(page.job.state)))return
  if(polls++>=120){message.value='等待较久，已停止页面轮询；可手工录入，刷新任务不会重复发起识别';return}
  timer=setTimeout(()=>void refresh(),1500)
}
async function refresh(){try{for(const page of pages.value)if(['QUEUED','RUNNING'].includes(page.job.state)){page.job=await api.get(page.job.id);await update(page)}}catch(e){message.value=e instanceof Error?e.message:'任务查询失败，原表单保留';stopped.value=true}finally{schedule()}}
function use(page:Page,index:number,replace=false){
  const item=page.items[index];if(!item)return
  try{
    if(historicalMock(page.job.provider,provider.value))throw Error('历史模拟候选不能作为当前真实识别结果采用，请使用当前供应商重新上传识别')
    const target=replace?Number(page.targets[index]):undefined
    if(props.lines.some((line,n)=>n!==target&&line.ocr?.jobId===page.job.id&&line.ocr.itemIndex===index))throw Error('此识别行已采用，请修改原行，勿重复计入')
    if(replace&&(page.targets[index]===undefined||page.targets[index]===''))throw Error('请先选择替换目标')
    if(replace){const old=props.lines[target!];if(!old)throw Error('原行已变化，请重新选择');if(!window.confirm(`用候选替换当前第${target!+1}行：${old.description} / ${old.quantity} / ${old.unitPrice}？`))return}
    else if(props.lines.some(line=>line.description.trim()===item.description.trim())&&!window.confirm('当前已有同名项目，可能重复计费。仍明确添加独立一行吗？'))return
    emit('merge',adopt(props.lines,item,{jobId:page.job.id,itemIndex:index},target));message.value='已采用到当前未提交表单；请核对数量、单价及费用重复'
  }catch(e){message.value=e instanceof Error?e.message:'采用失败'}
}
async function retry(page:Page){
  if(props.locked||page.rerunning||(!page.rerun&&((page.job.rerunsUsed??0)>=3||['QUEUED','RUNNING'].includes(page.job.state))))return
  page.rerun??={version:page.job.reviewVersion,key:crypto.randomUUID()}
  page.rerunning=true;message.value=''
  try{
    page.job=await api.retry(page.job.id,page.rerun.version,page.rerun.key)
    page.rerun=undefined;page.pending=undefined;page.items=[];page.program={};page.raw='';page.targets={};page.ignored={};page.loadedVersion=undefined
    // Retain typed values, but never silently attach old draft rows to new OCR evidence.
    if(props.lines.some(line=>line.ocr?.jobId===page.job.id))emit('merge',props.lines.map(line=>{
      if(line.ocr?.jobId!==page.job.id)return line
      const manual={...line};delete manual.ocr;return manual
    }))
    stopped.value=false;polls=0;await update(page);schedule()
  }catch(e){
    message.value=e instanceof Error?e.message:'重新识别失败，请重试'
    if(e instanceof Error&&'status' in e&&typeof e.status==='number'&&e.status>=400&&e.status<500){
      page.rerun=undefined
      try{page.job=await api.get(page.job.id)}catch{/* Keep the original rejection visible. */}
    }
  }finally{page.rerunning=false}
}
async function raw(page:Page){try{page.raw=await api.raw(page.job.id)}catch(e){message.value=e instanceof Error?e.message:'原文读取失败'}}
function ignore(page:Page,index:number){const existing=props.lines.find(line=>line.ocr?.jobId===page.job.id&&line.ocr.itemIndex===index);if(existing&&!window.confirm(`此候选已在表单中，确认移除当前行“${existing.description}”？人工填写内容也会移除。`))return;if(existing)emit('merge',props.lines.filter(line=>!(line.ocr?.jobId===page.job.id&&line.ocr.itemIndex===index)));page.ignored[index]=true;message.value='已忽略，原件与程序历史保留'}
async function prepare(lines:QuoteOcrDraft[]){
  provider.value=(await api.configuration()).provider
  allowAutoPrefill=false
  const next=lines.map(line=>({...line,ocr:line.ocr?{...line.ocr}:undefined}))
  for(const page of pages.value){const selected=next.filter(line=>line.ocr?.jobId===page.job.id);if(!selected.length)continue
    if(historicalMock(page.job.provider,provider.value))throw Error('清单仍包含历史模拟候选，请移除这些行并采用当前供应商的新识别结果后提交')
    const items=selected.map(line=>({index:line.ocr!.itemIndex,description:line.description.trim(),quantity:line.quantity,unitPrice:line.unitPrice,unit:line.unit??'',lineAmount:line.lineAmount??''})),body=JSON.stringify(items)
    if(!page.pending||page.pending.body!==body)page.pending={body,key:crypto.randomUUID()}
    page.job=await api.apply(page.job.id,page.job.reviewVersion,items,page.pending.key)
    for(const line of selected)line.ocr!.revision=page.job.reviewVersion
  }
  return next
}
defineExpose({prepare})
onMounted(async()=>{uploading.value=true;try{
  provider.value=(await api.configuration()).provider
  const jobs=await api.list(props.assignmentVersion)
  for(const job of jobs){
    if(pages.value.some(page=>page.job.id===job.id))continue
    const page:Page={job,items:[],program:{},image:'',raw:'',pageNo:job.pageNo??pages.value.length+1,targets:{},ignored:{}}
    pages.value.push(page);await update(page)
  }
  schedule()
}catch(e){message.value=e instanceof Error?e.message:'识别记录读取失败，仍可手填'}finally{uploading.value=false}})
onUnmounted(()=>{disposed=true;if(timer)clearTimeout(timer);for(const page of pages.value)if(page.image)URL.revokeObjectURL(page.image)})
</script>
<template><section class="quote-ocr"><h4>清单图片识别</h4><p v-if="provider==='MOCK_V1'" role="status">当前为模拟识别。</p>
<label>清单材料类别<select v-model="category" :disabled="uploading||locked"><option value="SHOP_QUOTE">网点报价清单</option><option value="ASSESSMENT">网点定损清单</option></select></label>
<FileDropZone hint="JPG/JPEG/PNG，每张不超过10 MiB，最多20张。上传后自动识别。" :max-bytes="10485760" :status="selected?'pending':'success'" :disabled="uploading||locked||pages.length>=20" :busy="uploading"><input ref="input" class="ocr-file-input" type="file" accept=".jpg,.jpeg,.png" aria-label="手写报价清单图片" :disabled="uploading||locked||pages.length>=20" @change="choose"></FileDropZone>
<button v-if="selected" type="button" :disabled="uploading||locked" @click="upload">重新获取本次上传结果</button>
<p v-if="message" role="status">{{ message }}</p><ul v-if="pages.length" class="task-sources" aria-label="识别任务实际来源"><li v-for="page in pages" :key="page.job.id" :class="{historical:historicalMock(page.job.provider,provider)}"><strong>第 {{ page.pageNo }} 页 · {{ taskSource(page.job.provider,provider) }}</strong><span>{{ stateLabel[page.job.state]??page.job.state }}</span><button type="button" :disabled="locked||page.rerunning||(!page.rerun&&(['QUEUED','RUNNING'].includes(page.job.state)||(page.job.rerunsUsed??0)>=3))" @click="retry(page)">{{ page.rerunning?'正在发起…':page.rerun?'重新获取识别结果':(page.job.rerunsUsed??0)>=3?'已达重新识别上限':'重新识别' }}</button><span>已重新识别 {{ page.job.rerunsUsed??0 }}/3 次</span><p v-if="historicalMock(page.job.provider,provider)" role="alert">此结果保留自模拟任务，不能当作百度识别采用；旧原件与审计保留。请重新上传原图发起当前供应商的新任务。</p></li></ul>
<button v-if="pages.some(page=>['QUEUED','RUNNING'].includes(page.job.state))" type="button" @click="stopped=true">停止等待，继续手填</button><button v-if="stopped&&pages.some(page=>['QUEUED','RUNNING'].includes(page.job.state))" type="button" :disabled="locked" @click="stopped=false;polls=0;refresh()">继续等待结果</button>
<details v-if="pages.length" class="source-details"><summary>原件与识别依据 · {{ pages.length }} 页（展开对照）</summary><article v-for="page in pages" :key="page.job.id"><h4>原件第{{ page.pageNo }}页 · {{ stateLabel[page.job.state]??page.job.state }}</h4><p>任务实际来源：{{ taskSource(page.job.provider,provider) }} · {{ page.job.provider }}</p>
<p v-if="page.job.errorCode" role="alert">{{ ocrError[page.job.errorCode]??'识别未完成，请联系管理员或继续手填' }}（{{ page.job.errorCode }}）</p>
<a v-if="page.image" :href="page.image" target="_blank" rel="noopener"><img :src="page.image" :alt="`第${page.pageNo}页受控原图，点击放大`"><span>放大原图对照</span></a>
<p v-if="page.program.fields?.amountCandidate||page.program.amountCandidate">单据原始合计：{{ page.program.fields?.amountCandidate??page.program.amountCandidate }} 元（不跨图累加、不写入报价总额）</p>
<p v-if="candidateTotal(page.items)">本页全部候选的程序计算合计：{{ candidateTotal(page.items) }} 元。{{ (page.program.fields?.amountCandidate??page.program.amountCandidate)&&candidateTotal(page.items)!==decimalProduct('1',page.program.fields?.amountCandidate??page.program.amountCandidate??'')?'与单据合计不一致，须核对；不要重复计入跨页合计。':'候选合计不等于最终采用的报价合计，请核对。' }}</p>
<ul><li v-for="(warning,index) in page.program.warnings??[]" :key="index">{{ warning }}</li></ul>
<details v-if="page.items.length" class="candidate-details"><summary>原始候选 {{ page.items.length }} 行 · 添加、替换或忽略</summary><p>此处保留识别原始依据，已采用内容统一在下方定损清单表中修改。</p><ol class="candidate-list"><template v-for="(item,index) in page.items" :key="index"><li v-if="!page.ignored[index]"><strong>{{ item.description||'未识别项目' }}</strong><p>数量 {{ item.quantity||'未识别' }} {{ item.unit||'' }} · 单价 {{ item.unitPrice||'未识别' }} 元 · 原行金额 {{ item.lineAmount||'未识别' }} 元</p><details><summary>原文 / 坐标 / 置信度</summary><pre>{{ page.program.items?.[index]?.evidence??'Mock合成，无真实文字证据' }}</pre></details><button type="button" :disabled="locked||lines.some(line=>line.ocr?.jobId===page.job.id&&line.ocr.itemIndex===index)" @click="use(page,index)">{{ lines.some(line=>line.ocr?.jobId===page.job.id&&line.ocr.itemIndex===index)?'已在清单表中':'添加到清单表' }}</button><label>替换清单表行<select v-model="page.targets[index]" :disabled="locked"><option value="">请选择</option><option v-for="(line,n) in lines" :key="n" :value="String(n)">第{{ n+1 }}行 {{ line.description||'空白' }}</option></select></label><button type="button" :disabled="locked" @click="use(page,index,true)">核对并替换</button><button type="button" :disabled="locked" @click="ignore(page,index)">忽略/移除此候选</button></li></template></ol></details><button v-if="Object.keys(page.ignored).length" type="button" :disabled="locked" @click="page.ignored={}">重新显示忽略候选</button>
<p v-if="page.job.state==='SUCCEEDED'&&!page.items.length">没有可靠的明细映射，请对照以下原文手工增加报价行；不编造项目或数字。</p>
<details><summary>百度文字 / 程序候选与人工修改分开</summary><p v-for="(line,n) in page.program.lines??[]" :key="n">{{ line.text }}</p><button type="button" @click="raw(page)">读取只读原始返回</button><pre v-if="page.raw">{{ page.raw }}</pre></details>
</article></details></section></template>
<style scoped>.ocr-file-input{position:absolute;width:1px!important;height:1px;min-height:0!important;opacity:0;overflow:hidden;clip-path:inset(50%)}.task-sources{padding:0!important;list-style:none}.task-sources li{display:grid;gap:5px;padding:12px!important;border:1px solid var(--color-border);border-radius:8px;margin:8px 0}.task-sources span{font-size:12px;color:var(--color-text-secondary)}.task-sources .historical{border-color:var(--color-warning);background:var(--color-warning-soft)}.task-sources .historical p{margin:4px 0;font-size:13px;line-height:1.6;color:var(--color-warning)}.quote-ocr{border:1px solid var(--color-border);border-radius:12px;padding:12px;margin:16px 0}.quote-ocr img{display:block;max-width:100%;max-height:300px;object-fit:contain;margin:auto}.quote-ocr label{display:block;margin:8px 0}.quote-ocr input,.quote-ocr select{width:100%;box-sizing:border-box;min-height:44px}.quote-ocr button{min-height:44px;margin:4px 4px 4px 0}.source-details>summary,.candidate-details>summary{cursor:pointer;font-weight:600;min-height:44px;padding:10px 0}.candidate-list{padding-left:22px}.candidate-list>li{padding:12px 0;border-bottom:1px solid var(--color-border)}.quote-ocr pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px}.quote-ocr article{border-top:1px solid var(--color-border);margin-top:12px}</style>
