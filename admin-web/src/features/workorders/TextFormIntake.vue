<script setup lang="ts">
import { ref } from 'vue'
import {readChatFiles,validateSceneFiles} from './intake'
import { httpClient } from '@/shared/http/client'
import type { DraftForm,WorkOrder } from './work-orders'
import { draftLabels,ocrDraftPayload,prefillOcrDraft,type OcrFieldConflict } from '../materials/ocr-form'
import {mergeIntakeDraft} from './intake-values'
const props=defineProps<{form:DraftForm;locked:boolean;photos:File[]}>(),emit=defineEmits<{merge:[value:DraftForm];blocked:[value:boolean];photos:[files:File[]]}>()
const ambiguities=ref<{field:keyof DraftForm;values:string[]}[]>([])
const text=ref(''),source=ref(''),busy=ref(false),confirmed=ref(false),message=ref(''),conflicts=ref<OcrFieldConflict[]>([]),candidate=ref<Record<string,unknown>>({}),key=ref(crypto.randomUUID())
const importStatus=ref(''),photoError=ref('')
async function importText(event:Event){
  const input=event.target as HTMLInputElement,files=Array.from(input.files??[])
  if(!files.length||props.locked||busy.value)return
  busy.value=true;emit('blocked',true);message.value=''
  try{
    const imported=await readChatFiles(files)
    validateSceneFiles([...props.photos,...imported.photos])
    text.value=imported.text;changed()
    if(imported.photos.length)emit('photos',imported.photos)
    importStatus.value=`已导入${input.hasAttribute('webkitdirectory')?'聊天记录文件夹':files[0]!.name}，含${imported.photos.length}张图片`
    await extract()
  }catch(error){message.value=error instanceof TypeError?'TXT编码无法读取，请转为UTF-8后导入':error instanceof Error?error.message:'导入失败'}
  finally{input.value='';busy.value=false;emit('blocked',Boolean(text.value.trim())&&(!confirmed.value||source.value!==text.value||Boolean(conflicts.value.length)||Boolean(ambiguities.value.length)))}
}
function importPhotos(event:Event){
  const input=event.target as HTMLInputElement,files=Array.from(input.files??[])
  if(!files.length||props.locked||busy.value)return
  try{validateSceneFiles([...props.photos,...files]);emit('photos',files);photoError.value=''}
  catch(error){photoError.value=error instanceof Error?error.message:'图片导入失败'}
  finally{input.value=''}
}
function changed(){confirmed.value=false;source.value='';conflicts.value=[];ambiguities.value=[];key.value=crypto.randomUUID();emit('blocked',Boolean(text.value.trim()))}
async function extract(){busy.value=true;message.value='';confirmed.value=false;emit('blocked',true);try{candidate.value=(await httpClient.post<{data:Record<string,unknown>}>('/work-orders/text/extract',{text:text.value})).data.data;source.value=text.value;const result=mergeIntakeDraft(props.form,(candidate.value.fields??{}) as Record<string,unknown>);emit('merge',result.merged);conflicts.value=result.conflicts;const evidence=(candidate.value.evidence??{}) as Record<string,{value?:string;requiresConfirmation?:boolean}[]>;ambiguities.value=Object.entries(evidence).filter(([field,rows])=>field in draftLabels&&field!=='accidentRegionId'&&field!=='surveyorName'&&(new Set(rows.map(row=>row.value).filter(Boolean)).size>1||rows.some(row=>row.requiresConfirmation))).map(([field,rows])=>({field:field as keyof DraftForm,values:[...new Set(rows.map(row=>row.value).filter((value):value is string=>typeof value==='string'))]}))}catch(error){message.value=error instanceof Error?error.message:'提取失败'}finally{busy.value=false}}
function resolve(item:OcrFieldConflict,useSource:boolean){if(useSource)emit('merge',{...props.form,...(item.field==='vehiclePlate'?{vehicleVin:''}:item.field==='vehicleVin'?{vehiclePlate:''}:{}),[item.field]:item.incoming});conflicts.value=conflicts.value.filter(c=>c.field!==item.field)}
async function selectAmbiguity(field:keyof DraftForm,value?:string){if(value!==undefined){busy.value=true;try{const name=field==='accidentAt'?'事故时间':field==='claimReportedAt'?'报案时间':draftLabels[field];const result=(await httpClient.post<{data:{fields:Record<string,string>}}>('/work-orders/text/extract',{text:name+'：'+value})).data.data;const adopted=prefillOcrDraft(result.fields)[field];if(!adopted)throw Error('该候选无法直接采用，请人工填写后选“保留当前输入”');emit('merge',{...props.form,...(field==='vehiclePlate'?{vehicleVin:''}:field==='vehicleVin'?{vehiclePlate:''}:{}),[field]:adopted})}catch(e){message.value=e instanceof Error?e.message:'核对失败';return}finally{busy.value=false}}ambiguities.value=ambiguities.value.filter(item=>item.field!==field)}
async function createReviewedDraft(form:DraftForm):Promise<WorkOrder|undefined>{if(busy.value)throw Error('文字读取或提取中，请稍后');if(!text.value.trim())return undefined;if(!confirmed.value||source.value!==text.value||conflicts.value.length||ambiguities.value.length)throw Error('请提取文字并核对冲突与采用信息');return (await httpClient.post<{data:WorkOrder}>('/work-orders/text/draft',{text:source.value,confirmed:true,draft:ocrDraftPayload(form)},{headers:{'Idempotency-Key':key.value}})).data.data}
defineExpose({createReviewedDraft})
</script>
<template><aside class="text-intake"><h2>导入案件资料</h2><p>文字提取信息，照片仅作物损材料。</p>
  <section class="import-group chat-group" aria-label="聊天记录整包导入">
    <h3>聊天记录整包导入</h3><p>选择导出的文件夹，或将整个文件夹打包为 ZIP。</p>
    <div class="upload-row">
      <FileDropZone folder hint="UTF-8 TXT 与 JPG/PNG；保留子目录路径。文字合计≤200000字节、50000字符、1000行；照片≤20张，每张≤10 MiB。" :feedback="importStatus" :error="message" :disabled="locked||busy" :busy="busy"><label class="upload-choice" :class="{disabled:locked||busy}">上传文件夹<input type="file" webkitdirectory multiple :disabled="locked||busy" @change="importText"/></label></FileDropZone>
      <FileDropZone hint="一次1个ZIP；解压内容仅UTF-8 TXT和JPG/PNG。文字合计≤200000字节、50000字符、1000行；照片≤20张，每张≤10 MiB。" :feedback="importStatus" :error="message" :disabled="locked||busy" :busy="busy"><label class="upload-choice" :class="{disabled:locked||busy}">上传 ZIP<input type="file" accept=".zip,application/zip,application/x-zip-compressed" :disabled="locked||busy" @change="importText"/></label></FileDropZone>
    </div><p class="upload-help">包含 UTF-8 TXT 与 JPG / PNG，支持子文件夹。</p>
  </section>
  <section class="import-group separate-group" aria-label="文字和图片分别上传">
    <h3>文字 / 图片分别上传</h3><p>上传 TXT 或在下方粘贴文字，再添加物损图片。</p>
    <div class="upload-row">
      <FileDropZone hint="一次1个UTF-8 TXT；≤200000字节、50000字符、1000行。" :max-bytes="200000" :feedback="importStatus" :error="message" :disabled="locked||busy" :busy="busy"><label class="upload-choice" :class="{disabled:locked||busy}">上传文字<input type="file" accept=".txt,text/plain" :disabled="locked||busy" @change="importText"/></label></FileDropZone>
      <FileDropZone :files="photos" hint="JPG/JPEG/PNG；支持批量，每张≤10 MiB，所有照片合计≤20张。" :max-bytes="10485760" :error="photoError" :disabled="locked||busy" :busy="busy"><label class="upload-choice" :class="{disabled:locked||busy}">上传图片<input type="file" accept=".jpg,.jpeg,.png,image/jpeg,image/png" multiple :disabled="locked||busy" @change="importPhotos"/></label></FileDropZone>
    </div><p class="upload-help">图片每张不超过 10 MiB，合计最多 20 张。</p>
    <p class="photo-count" role="status">已选 {{ photos.length }} / 20 张图片，可在照片预览区查看或移除。</p>
    <p v-if="photoError" class="import-error" role="alert">{{ photoError }}</p>
  </section>
  <p v-if="importStatus" role="status">{{ importStatus }}</p><label>原始文字 / 粘贴文字<textarea v-model="text" :disabled="locked||busy" maxlength="50000" rows="12" placeholder="案件号：…&#10;三者电话：…&#10;查勘员姓名：…" @input="changed"/></label><button :disabled="locked||busy||!text.trim()" @click="extract">{{ busy?'正在提取…':'提取信息' }}</button><p v-if="message" role="alert">{{ message }}</p><article v-for="item in ambiguities" :key="item.field"><strong>{{ draftLabels[item.field] }}待确认</strong><button v-for="value in item.values" :key="value" :disabled="locked||busy" @click="selectAmbiguity(item.field,value)">采用 {{ value }}</button><button :disabled="locked||busy" @click="selectAmbiguity(item.field)">保留当前输入 / 手工填写</button></article><article v-for="item in conflicts" :key="item.field"><strong>{{ draftLabels[item.field] }}有不同值</strong><p>当前：{{ ['vehiclePlate','vehicleVin'].includes(item.field)?form.vehiclePlate||form.vehicleVin:form[item.field] }}<br/>原文：{{ item.incoming }}</p><button @click="resolve(item,false)">保留当前</button><button @click="resolve(item,true)">采用原文</button></article><label v-if="source&&!conflicts.length&&!ambiguities.length"><input v-model="confirmed" type="checkbox" :disabled="locked||busy" @change="emit('blocked',!confirmed)"/>已核对原文与采用信息</label><details v-if="source"><summary>提取依据</summary><pre>{{ JSON.stringify(candidate,null,2) }}</pre></details></aside></template>
<style scoped>.text-intake{padding:20px;background:#fff;border:1px solid #dce5e4;border-radius:16px}.text-intake h2{margin:0;font-size:20px}.text-intake p{font-size:13px;color:#566a70}.text-intake label{display:block;margin:16px 0}.text-intake textarea{display:block;margin-top:8px;width:100%;box-sizing:border-box;resize:vertical;padding:12px;border:1px solid #b9c9c9;border-radius:8px;font:inherit}.text-intake button{margin:4px}.text-intake article{border-top:1px solid #cbd9d7;padding-top:16px}.text-intake pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px}
.import-group{margin-top:16px;padding:16px;border:1px solid;border-left-width:3px;border-radius:10px}
.chat-group{background:#eef4f8;border-color:#bdcfdc;border-left-color:#50748c}
.separate-group{background:#f6f2e9;border-color:#dfd2b9;border-left-color:#967342}
.import-group h3{margin:0;font-size:15px;color:#303e47}
.import-group p{margin:8px 0 12px;line-height:1.6;color:#53616a}
.upload-row{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}
.text-intake .upload-choice{position:relative;display:flex;align-items:center;justify-content:center;margin:0;padding:10px 6px;border:1px solid #a9b7bd;border-radius:7px;background:#fff;color:#33434c;font-size:13px;font-weight:600;cursor:pointer}
.upload-choice input{position:absolute;inset:0;width:100%;height:100%;opacity:0;cursor:pointer}
.upload-choice:focus-within{outline:2px solid #50748c;outline-offset:3px}
.upload-choice:hover{border-color:#50748c}
.upload-choice.disabled{opacity:.55;cursor:not-allowed}
.upload-choice.disabled input{cursor:not-allowed}
.import-group .upload-help,.import-group .photo-count{margin:10px 0 0;font-size:12px}
.import-group .import-error{color:#a04c47}
@media(max-width:480px){.text-intake{padding:14px}.import-group{padding:12px}}
</style>
