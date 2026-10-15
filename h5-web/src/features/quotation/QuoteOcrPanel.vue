<script setup lang="ts">
import {onMounted,onUnmounted,ref} from 'vue'
import {httpClient} from '@/shared/http/client'
import {adopt,isBlank,quoteOcrApi,ocrError,decimalProduct,candidateTotal,type Candidate,type Job,type QuoteOcrDraft} from './quote-ocr'
const props=defineProps<{caseId:string;assignmentVersion:number;lines:QuoteOcrDraft[];locked:boolean}>(),emit=defineEmits<{merge:[lines:QuoteOcrDraft[]]}>(),api=quoteOcrApi(httpClient,props.caseId)
interface Page {job:Job;items:Candidate[];program:NonNullable<Job['candidate']>;image:string;raw:string;pageNo:number;targets:Record<number,string>;ignored:Record<number,boolean>;pending?:{body:string;key:string}}
const pages=ref<Page[]>([]),provider=ref(''),message=ref(''),uploading=ref(false),input=ref<HTMLInputElement>(),category=ref('SHOP_QUOTE'),uploadKey=ref(crypto.randomUUID()),selected=ref<File>(),stopped=ref(false)
let disposed=false,timer:ReturnType<typeof setTimeout>|undefined,polls=0,allowAutoPrefill=true
const stateLabel:Record<string,string>={QUEUED:'排队中',RUNNING:'识别中',SUCCEEDED:'候选已返回，须核对',FAILED:'识别失败'}
async function choose(event:Event){selected.value=(event.target as HTMLInputElement).files?.[0];uploadKey.value=crypto.randomUUID();if(selected.value)await upload()}
async function upload(){if(uploading.value||!selected.value)return;uploading.value=true;message.value='';try{
  const job=await api.upload(selected.value,props.assignmentVersion,pages.value.length+1,category.value,uploadKey.value)
  if(pages.value.some(page=>page.job.id===job.id)){message.value='相同原件已存在，已复用原任务；不会重复识别或添加明细';return}
  const page:Page={job,items:[],program:{},image:'',raw:'',pageNo:job.pageNo??pages.value.length+1,targets:{},ignored:{}};pages.value.push(page)
  stopped.value=false;polls=0;await update(page);schedule()
}catch(e){message.value=e instanceof Error?e.message:'上传失败，原表单保留'}finally{uploading.value=false}}
async function update(page:Page){
  if(page.job.state==='SUCCEEDED'&&page.items.length===0){
    page.program=await api.program(page.job.id);page.items=(page.program.items??[]).map(item=>({...item}));page.image=URL.createObjectURL(await api.image(page.job.id))
    // Compare the latest props, including edits made while the supplier was running.
    if(allowAutoPrefill&&!props.locked&&isBlank(props.lines)&&page.items.length){let next:QuoteOcrDraft[]=[];page.items.forEach((item,index)=>{next=adopt(next,item,{jobId:page.job.id,itemIndex:index})});emit('merge',next);message.value='已预填空白表单，仍须逐项核对；未提交报价'}
    else message.value='已有手填或草稿内容已保留，请逐项选择添加、替换或忽略'
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
    const target=replace?Number(page.targets[index]):undefined
    if(props.lines.some((line,n)=>n!==target&&line.ocr?.jobId===page.job.id&&line.ocr.itemIndex===index))throw Error('此识别行已采用，请修改原行，勿重复计入')
    if(replace&&(page.targets[index]===undefined||page.targets[index]===''))throw Error('请先选择替换目标')
    if(replace){const old=props.lines[target!];if(!old)throw Error('原行已变化，请重新选择');if(!window.confirm(`用候选替换当前第${target!+1}行：${old.description} / ${old.quantity} / ${old.unitPrice}？`))return}
    else if(props.lines.some(line=>line.description.trim()===item.description.trim())&&!window.confirm('当前已有同名项目，可能重复计费。仍明确添加独立一行吗？'))return
    emit('merge',adopt(props.lines,item,{jobId:page.job.id,itemIndex:index},target));message.value='已采用到当前未提交表单；请核对数量、单价及费用重复'
  }catch(e){message.value=e instanceof Error?e.message:'采用失败'}
}
async function retry(page:Page){if(!window.confirm('明确重新识别？真实模式可能再次消耗额度，原表单保留。'))return;try{page.job=await api.retry(page.job.id);stopped.value=false;polls=0;schedule()}catch(e){message.value=e instanceof Error?e.message:'重试失败'}}
async function raw(page:Page){try{page.raw=await api.raw(page.job.id)}catch(e){message.value=e instanceof Error?e.message:'原文读取失败'}}
function ignore(page:Page,index:number){const existing=props.lines.find(line=>line.ocr?.jobId===page.job.id&&line.ocr.itemIndex===index);if(existing&&!window.confirm(`此候选已在表单中，确认移除当前行“${existing.description}”？人工填写内容也会移除。`))return;if(existing)emit('merge',props.lines.filter(line=>!(line.ocr?.jobId===page.job.id&&line.ocr.itemIndex===index)));page.ignored[index]=true;message.value='已忽略，原件与程序历史保留'}
async function prepare(lines:QuoteOcrDraft[]){
  allowAutoPrefill=false
  const next=lines.map(line=>({...line,ocr:line.ocr?{...line.ocr}:undefined}))
  for(const page of pages.value){const selected=next.filter(line=>line.ocr?.jobId===page.job.id);if(!selected.length)continue
    const items=selected.map(line=>({index:line.ocr!.itemIndex,description:line.description.trim(),quantity:line.quantity,unitPrice:line.unitPrice,unit:line.unit??'',lineAmount:line.lineAmount??''})),body=JSON.stringify(items)
    if(!page.pending||page.pending.body!==body)page.pending={body,key:crypto.randomUUID()}
    page.job=await api.apply(page.job.id,page.job.reviewVersion,items,page.pending.key)
    for(const line of selected)line.ocr!.revision=page.job.reviewVersion
  }
  return next
}
defineExpose({prepare})
onMounted(async()=>{try{provider.value=(await api.configuration()).provider}catch(e){message.value=e instanceof Error?e.message:'OCR配置不可用，仍可手填'}})
onUnmounted(()=>{disposed=true;if(timer)clearTimeout(timer);for(const page of pages.value)if(page.image)URL.revokeObjectURL(page.image)})
</script>
<template><section class="quote-ocr"><h4>上传手写清单识别</h4><p>候选结果，须人工核对。当前供应商：{{ provider||'读取中' }}。{{ provider==='MOCK_V1'?'当前为合成演示，不是真实识别。':'识别只提供报价候选，不自动提交。' }}</p>
<label>清单材料类别<select v-model="category" :disabled="uploading||locked"><option value="SHOP_QUOTE">网点报价清单</option><option value="ASSESSMENT">网点定损清单</option></select></label>
<input ref="input" type="file" accept=".jpg,.jpeg,.png" aria-label="手写报价清单图片" :disabled="uploading||locked||pages.length>=20" @change="choose">
<button type="button" :disabled="uploading||locked||!provider||pages.length>=20" @click="input?.click()">{{ uploading?'正在上传…':'上传手写清单识别' }}</button><button v-if="selected" type="button" :disabled="uploading||locked" @click="upload">重新获取本次上传结果（同幂等键）</button>
<p>每张图片独立保留来源和页次，最多20张。平台限10 MiB，百度另有尺寸/编码限制；不裁剪原件。上传即明确发起识别。</p><p role="status">{{ message }}</p>
<button v-if="pages.some(page=>['QUEUED','RUNNING'].includes(page.job.state))" type="button" @click="stopped=true">停止页面等待，继续手填（已发起任务不撤销）</button><button v-if="pages.length" type="button" :disabled="locked" @click="stopped=false;polls=0;refresh()">刷新任务状态（不重新识别）</button>
<article v-for="page in pages" :key="page.job.id"><h4>原件第{{ page.pageNo }}页 · {{ stateLabel[page.job.state]??page.job.state }}</h4><p>{{ page.job.provider==='MOCK_V1'?'Mock合成候选':'百度文字＋程序整理候选' }} · {{ page.job.provider }}</p>
<p v-if="page.job.errorCode" role="alert">{{ ocrError[page.job.errorCode]??'识别未完成，请联系管理员或继续手填' }}（{{ page.job.errorCode }}）</p><button v-if="page.job.retryable" type="button" :disabled="locked" @click="retry(page)">明确重试失败任务</button>
<a v-if="page.image" :href="page.image" target="_blank" rel="noopener"><img :src="page.image" :alt="`第${page.pageNo}页受控原图，点击放大`"><span>放大原图对照</span></a>
<p v-if="page.program.fields?.amountCandidate||page.program.amountCandidate">单据原始合计：{{ page.program.fields?.amountCandidate??page.program.amountCandidate }} 元（不跨图累加、不写入报价总额）</p>
<p v-if="candidateTotal(page.items)">本页全部候选的程序计算合计：{{ candidateTotal(page.items) }} 元。{{ (page.program.fields?.amountCandidate??page.program.amountCandidate)&&candidateTotal(page.items)!==decimalProduct('1',page.program.fields?.amountCandidate??page.program.amountCandidate??'')?'与单据合计不一致，须核对；不要重复计入跨页合计。':'候选合计不等于最终采用的报价合计，请核对。' }}</p>
<ul><li v-for="(warning,index) in page.program.warnings??[]" :key="index">{{ warning }}</li></ul>
<fieldset v-for="(item,index) in page.items" v-show="!page.ignored[index]" :key="index"><legend>第{{ page.pageNo }}页候选{{ index+1 }}（{{ index<(page.program.items?.length??0)?'程序整理':'人工新增' }}，须核对）</legend>
<label>项目名称<input v-model="item.description" :disabled="locked"></label><label>数量（缺失留空）<input v-model="item.quantity" inputmode="numeric" :disabled="locked"></label><label>单位（核对证据）<input v-model="item.unit" :disabled="locked"></label><label>单价（元）<input v-model="item.unitPrice" inputmode="decimal" :disabled="locked"></label><label>单据行金额（核对证据）<input v-model="item.lineAmount" inputmode="decimal" :disabled="locked"></label>
<p>程序计算：{{ decimalProduct(item.quantity,item.unitPrice)||'待补充' }} 元；原识别行金额：{{ page.program.items?.[index]?.lineAmount||'未识别' }}。{{ item.lineAmount&&decimalProduct(item.quantity,item.unitPrice)&&decimalProduct(item.quantity,item.unitPrice)!==decimalProduct('1',item.lineAmount)?'金额不一致，请核对':'请核对是否与已有工时/其他费用重复' }}</p>
<details><summary>识别原文 / 坐标 / 实际置信度证据（只读）</summary><pre>{{ page.program.items?.[index]?.evidence??'Mock合成，无真实文字证据' }}</pre></details>
<button type="button" :disabled="locked" @click="use(page,index)">明确添加此候选</button><label>替换当前表单行<select v-model="page.targets[index]" :disabled="locked"><option value="">请选择</option><option v-for="(line,n) in lines" :key="n" :value="String(n)">第{{ n+1 }}行 {{ line.description||'空白' }}</option></select></label><button type="button" :disabled="locked" @click="use(page,index,true)">核对并替换指定行</button><button type="button" :disabled="locked" @click="ignore(page,index)">忽略/移除此候选</button></fieldset>
<button v-if="page.job.state==='SUCCEEDED'" type="button" :disabled="locked||page.items.length>=200" @click="page.items.push({description:'',quantity:'',unitPrice:'',unit:'',lineAmount:''})">人工增加候选行（非OCR原文）</button><button v-if="Object.keys(page.ignored).length" type="button" @click="page.ignored={}">重新显示忽略候选</button>
<p v-if="page.job.state==='SUCCEEDED'&&!page.items.length">没有可靠的明细映射，请对照以下原文手工增加报价行；不编造项目或数字。</p>
<details><summary>百度文字 / 程序候选与人工修改分开</summary><p v-for="(line,n) in page.program.lines??[]" :key="n">{{ line.text }}</p><button type="button" @click="raw(page)">读取只读原始返回</button><pre v-if="page.raw">{{ page.raw }}</pre></details>
</article><p>采用只作用于下方未提交表单。最后点击原“网点提交报价”，才保存人工确认并按原校验提交新版本。</p></section></template>
<style scoped>.quote-ocr{border:1px solid var(--color-border);border-radius:12px;padding:12px;margin:16px 0}.quote-ocr img{display:block;max-width:100%;max-height:300px;object-fit:contain;margin:auto}.quote-ocr label{display:block;margin:8px 0}.quote-ocr input,.quote-ocr select{width:100%;box-sizing:border-box;min-height:44px}.quote-ocr button{min-height:44px;margin:4px 4px 4px 0}.quote-ocr fieldset{min-width:0;margin:12px 0}.quote-ocr pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px}.quote-ocr article{border-top:1px solid var(--color-border);margin-top:12px}</style>
