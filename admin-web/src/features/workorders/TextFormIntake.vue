<script setup lang="ts">
import { ref } from 'vue'
import {readChatFiles,validateSceneFiles} from './intake'
import { httpClient } from '@/shared/http/client'
import type { DraftForm,WorkOrder } from './work-orders'
import { draftLabels,ocrDraftPayload,prefillOcrDraft,type OcrFieldConflict } from '../materials/ocr-form'
import {mergeIntakeDraft} from './intake-values'
const props=defineProps<{form:DraftForm;locked:boolean;photos:File[]}>(),emit=defineEmits<{merge:[value:DraftForm];blocked:[value:boolean];photos:[files:File[]]}>()
const ambiguities=ref<{field:keyof DraftForm;values:string[]}[]>([])
const text=ref(''),source=ref(''),busy=ref(false),message=ref(''),conflicts=ref<OcrFieldConflict[]>([]),candidate=ref<Record<string,unknown>>({}),key=ref(crypto.randomUUID())
const importStatus=ref(''),cancelNotice=ref('')
let editVersion=0
function syncBlocked(){emit('blocked',busy.value||(Boolean(text.value.trim())&&(source.value!==text.value||Boolean(conflicts.value.length)||Boolean(ambiguities.value.length))))}
async function importText(event:Event){
  const input=event.target as HTMLInputElement,files=Array.from(input.files??[])
  if(!files.length||props.locked||busy.value)return
  let version=editVersion
  busy.value=true;emit('blocked',true);message.value=''
  try{
    const imported=await readChatFiles(files)
    if(version!==editVersion)return
    validateSceneFiles([...props.photos,...imported.photos])
    text.value=imported.text;changed();version=editVersion
    if(imported.photos.length)emit('photos',imported.photos)
    importStatus.value=`已导入${input.hasAttribute('webkitdirectory')?'聊天记录文件夹':files[0]!.name}，含${imported.photos.length}张图片`
    busy.value=false;await extract()
  }catch(error){if(version===editVersion)message.value=error instanceof TypeError?'TXT编码无法读取，请转为UTF-8后导入':error instanceof Error?error.message:'导入失败'}
  finally{input.value='';if(version===editVersion){busy.value=false;syncBlocked()}}
}
function changed(){editVersion++;cancelNotice.value='';source.value='';candidate.value={};conflicts.value=[];ambiguities.value=[];key.value=crypto.randomUUID();if(!text.value.trim()){importStatus.value='';message.value=''}syncBlocked()}
function cancelText(){if(props.locked)return;text.value='';busy.value=false;changed();cancelNotice.value='已取消文字导入，手填信息和照片已保留。'}
async function extract(){if(props.locked||busy.value||!text.value.trim())return;const version=editVersion,original=text.value;busy.value=true;message.value='';syncBlocked();try{const extracted=(await httpClient.post<{data:Record<string,unknown>}>('/work-orders/text/extract',{text:original})).data.data;if(version!==editVersion)return;candidate.value=extracted;source.value=original;const result=mergeIntakeDraft(props.form,(candidate.value.fields??{}) as Record<string,unknown>);emit('merge',result.merged);conflicts.value=result.conflicts;const evidence=(candidate.value.evidence??{}) as Record<string,{value?:string;requiresConfirmation?:boolean}[]>;ambiguities.value=Object.entries(evidence).filter(([field,rows])=>field in draftLabels&&field!=='accidentRegionId'&&field!=='surveyorName'&&(new Set(rows.map(row=>row.value).filter(Boolean)).size>1||rows.some(row=>row.requiresConfirmation))).map(([field,rows])=>({field:field as keyof DraftForm,values:[...new Set(rows.map(row=>row.value).filter((value):value is string=>typeof value==='string'))]}))}catch(error){if(version===editVersion)message.value=error instanceof Error?error.message:'提取失败'}finally{if(version===editVersion){busy.value=false;syncBlocked()}}}
function resolve(item:OcrFieldConflict,useSource:boolean){if(useSource)emit('merge',{...props.form,...(item.field==='vehiclePlate'?{vehicleVin:''}:item.field==='vehicleVin'?{vehiclePlate:''}:{}),[item.field]:item.incoming});conflicts.value=conflicts.value.filter(c=>c.field!==item.field);syncBlocked()}
async function selectAmbiguity(field:keyof DraftForm,value?:string){
  const version=editVersion
  if(value!==undefined){busy.value=true;syncBlocked();try{
    const name=field==='accidentAt'?'事故时间':field==='claimReportedAt'?'报案时间':draftLabels[field]
    const result=(await httpClient.post<{data:{fields:Record<string,string>}}>('/work-orders/text/extract',{text:name+'：'+value})).data.data
    if(version!==editVersion)return
    const adopted=prefillOcrDraft(result.fields)[field]
    if(!adopted)throw Error('该候选无法直接采用，请人工填写后选“保留当前输入”')
    emit('merge',{...props.form,...(field==='vehiclePlate'?{vehicleVin:''}:field==='vehicleVin'?{vehiclePlate:''}:{}),[field]:adopted})
  }catch(e){if(version===editVersion)message.value=e instanceof Error?e.message:'核对失败';return}finally{if(version===editVersion){busy.value=false;syncBlocked()}}}
  if(version===editVersion){ambiguities.value=ambiguities.value.filter(item=>item.field!==field);syncBlocked()}
}
async function createReviewedDraft(form:DraftForm):Promise<WorkOrder|undefined>{if(busy.value)throw Error('文字读取或提取中，请稍后');if(!text.value.trim())return undefined;if(source.value!==text.value||conflicts.value.length||ambiguities.value.length)throw Error('请提取文字并核对冲突与采用信息');return (await httpClient.post<{data:WorkOrder}>('/work-orders/text/draft',{text:source.value,confirmed:true,draft:ocrDraftPayload(form)},{headers:{'Idempotency-Key':key.value}})).data.data}
defineExpose({createReviewedDraft})
</script>
<template><aside class="text-intake"><h2>导入案件资料</h2>
  <section class="import-group chat-group" aria-label="聊天记录整包导入">
    <h3>聊天记录整包导入</h3>
    <FileDropZone class="bundle-upload" hint="选择文件夹或 ZIP，支持拖入 ZIP。内容：UTF-8 TXT、JPG / PNG。" :feedback="importStatus" :disabled="locked||busy" :busy="busy">
      <div class="upload-row">
        <label class="upload-choice" :class="{disabled:locked||busy}">选择 ZIP<input type="file" accept=".zip,application/zip,application/x-zip-compressed" :disabled="locked||busy" @change="importText"/></label>
        <label class="upload-choice" :class="{disabled:locked||busy}">选择文件夹<input type="file" webkitdirectory multiple :disabled="locked||busy" @change="importText"/></label>
      </div>
    </FileDropZone>
  </section>
  <section class="import-group intake-input-panel" aria-label="文字资料">
    <h3>文字资料</h3>
    <label class="text-label">输入或粘贴文字<textarea v-model="text" :disabled="locked||busy" maxlength="50000" rows="9" placeholder="在此输入或粘贴聊天记录、案件信息…" @input="changed"/></label>
    <div class="text-actions"><button :disabled="locked||busy||!text.trim()" @click="extract">{{ busy?'正在提取…':'提取信息' }}</button><button v-if="text.trim()||busy||source" type="button" :disabled="locked" @click="cancelText">取消文字导入</button></div>
    <p v-if="cancelNotice" role="status">{{ cancelNotice }}</p>
  </section>
  <p v-if="message" role="alert">{{ message }}</p><article v-for="item in ambiguities" :key="item.field"><strong>{{ draftLabels[item.field] }}待确认</strong><button v-for="value in item.values" :key="value" :disabled="locked||busy" @click="selectAmbiguity(item.field,value)">采用 {{ value }}</button><button :disabled="locked||busy" @click="selectAmbiguity(item.field)">保留当前输入 / 手工填写</button></article><article v-for="item in conflicts" :key="item.field"><strong>{{ draftLabels[item.field] }}有不同值</strong><p>当前：{{ ['vehiclePlate','vehicleVin'].includes(item.field)?form.vehiclePlate||form.vehicleVin:form[item.field] }}<br/>原文：{{ item.incoming }}</p><button @click="resolve(item,false)">保留当前</button><button @click="resolve(item,true)">采用原文</button></article><details v-if="source"><summary>提取依据</summary><pre>{{ JSON.stringify(candidate,null,2) }}</pre></details></aside></template>
<style scoped>.text-intake{padding:20px;background:#fff;border:1px solid #dce5e4;border-radius:16px}.text-intake h2{margin:0;font-size:20px}.text-intake p{font-size:13px;color:#566a70}.text-intake label{display:block;margin:16px 0}.text-intake textarea{display:block;margin-top:8px;width:100%;box-sizing:border-box;resize:vertical;padding:12px;border:1px solid #b9c9c9;border-radius:8px;font:inherit}.text-intake button{margin:4px}.text-intake article{border-top:1px solid #cbd9d7;padding-top:16px}.text-intake pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px}
.import-group{margin-top:16px;padding:16px;border:1px solid;border-left-width:3px;border-radius:10px}
.chat-group{background:#eef4f8;border-color:#bdcfdc;border-left-color:#50748c}
.import-group h3{margin:0;font-size:15px;color:#303e47}
.import-group p{margin:8px 0 12px;line-height:1.6;color:#53616a}
.upload-row{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}
.bundle-upload :deep(.drop-prompt),.bundle-upload :deep(.drop-picker),.bundle-upload :deep(.drop-files){display:none}
.text-intake .text-label{font-size:13px;color:#53616a;margin:12px 0}
.text-actions{display:flex;flex-wrap:wrap;gap:8px}
.text-actions button{min-height:36px;margin:0;padding:7px 12px;border:1px solid #a9b7bd;border-radius:7px;background:#fff;color:#33434c;cursor:pointer}
.text-actions button:disabled{opacity:.55;cursor:not-allowed}
.text-intake .upload-choice{position:relative;display:flex;align-items:center;justify-content:center;margin:0;padding:10px 6px;border:1px solid #a9b7bd;border-radius:7px;background:#fff;color:#33434c;font-size:13px;font-weight:600;cursor:pointer}
.upload-choice input{position:absolute;inset:0;width:100%;height:100%;opacity:0;cursor:pointer}
.upload-choice:focus-within{outline:2px solid #50748c;outline-offset:3px}
.upload-choice:hover{border-color:#50748c}
.upload-choice.disabled{opacity:.55;cursor:not-allowed}
.upload-choice.disabled input{cursor:not-allowed}
@media(max-width:480px){.text-intake{padding:14px}.import-group{padding:12px}}
</style>
