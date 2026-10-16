<script setup lang="ts">
import {computed,onUnmounted,ref,watch} from 'vue'
import {ElDialog} from 'element-plus'
import {httpClient} from '@/shared/http/client'
import type {QuoteCase} from '../quotation/quotation'
const props=defineProps<{caseId:string;refreshToken?:number}>()
const quote=ref<QuoteCase>(),original=ref(''),preview=ref(''),contentType=ref(''),loading=ref(false),error=ref(''),page=ref(1),pages=ref(1),enlarged=ref(false)
const confirmed=computed(()=>quote.value?.gate.customerServiceConfirmationRecorded&&quote.value?.assessment?.sourceCurrent)
let generation=0
function release(){for(const url of new Set([original.value,preview.value]))if(url)URL.revokeObjectURL(url);original.value='';preview.value=''}
async function load(){
 const epoch=++generation,priorFile=quote.value?.assessment?.lossFileId;loading.value=true;error.value='';quote.value=undefined;release()
 try{
  const next=(await httpClient.get<{data:QuoteCase}>(`/quotations/cases/${props.caseId}`)).data.data
  if(epoch!==generation)return
  if(priorFile!==next.assessment?.lossFileId)page.value=1
  quote.value=next
  if(!next.assessment?.lossFileId){error.value='未关联核损单原件，请从案件记录核对。';return}
  const file=await httpClient.get<Blob>(`/materials/${next.assessment.lossFileId}/download`,{responseType:'blob'})
  const image=file.data.type==='application/pdf'?await httpClient.get<Blob>(`/materials/${next.assessment.lossFileId}/preview?page=${page.value}`,{responseType:'blob'}):undefined
  if(epoch!==generation)return
  original.value=URL.createObjectURL(file.data);preview.value=image?URL.createObjectURL(image.data):original.value;contentType.value=file.data.type;pages.value=Number(image?.headers?.['x-page-count']??1)
 }catch(e){if(epoch===generation)error.value=e instanceof Error?e.message:'核损单加载失败，请重试'}
 finally{if(epoch===generation)loading.value=false}
}
watch(()=>[props.caseId,props.refreshToken],()=>void load(),{immediate:true})
onUnmounted(()=>{generation++;release()})
</script>
<template>
 <section class="funds-assessment" data-reading="核损单与确认金额">
  <header><h3>保险公司核损单</h3><button v-if="preview&&!error" @click="enlarged=true">放大查看</button></header>
  <div class="confirmed-price"><span>最终确认金额</span><strong>{{ confirmed?quote?.assessment?.amount:'未确认' }}<small v-if="confirmed"> 元</small></strong></div>
  <p v-if="quote&&!confirmed" class="source-note">当前记录缺少有效的客服最终确认。</p>
  <div v-if="loading" class="document-loading" aria-busy="true" aria-label="正在加载核损单"/>
  <p v-else-if="error" role="alert" class="document-error">{{ error }} <button @click="load">重新加载</button></p>
  <div v-else-if="preview" class="document-viewport"><img :src="preview" alt="保险公司核损单原件" @error="error='核损单原件无法预览，可下载核对或重新加载。'"/></div>
  <nav v-if="pages>1" aria-label="核损单翻页"><button :disabled="loading||page===1" @click="page--;load()">上一页</button><span>{{ page }} / {{ pages }}</span><button :disabled="loading||page===pages" @click="page++;load()">下一页</button></nav>
  <a v-if="original" :href="original" :download="'保险公司核损单'+(contentType==='application/pdf'?'.pdf':contentType==='image/jpeg'?'.jpg':'.png')">下载核损单</a>
  <ElDialog v-model="enlarged" title="保险公司核损单" width="min(1000px,94vw)" append-to-body><img class="enlarged-document" :src="preview" alt="保险公司核损单放大原件"/><nav v-if="pages>1" aria-label="放大核损单翻页"><button :disabled="loading||page===1" @click="page--;load()">上一页</button><span>{{ page }} / {{ pages }}</span><button :disabled="loading||page===pages" @click="page++;load()">下一页</button></nav></ElDialog>
 </section>
</template>
<style scoped>
.funds-assessment{min-width:0}.funds-assessment header{display:flex;align-items:center;justify-content:space-between;gap:12px}.funds-assessment h3{font-size:16px;margin:0}.funds-assessment button{font:inherit;font-size:12px}.confirmed-price{display:grid;gap:8px;margin:20px 0;padding:16px 0;border-block:1px solid #dce5e4}.confirmed-price span,.source-note{font-size:12px;color:#536b70}.confirmed-price strong{font-size:27px;font-weight:600;color:#275c44;font-variant-numeric:tabular-nums}.confirmed-price small{font-size:12px;font-weight:400}.document-viewport{max-height:480px;overflow:auto;background:#f3f6f5;border:1px solid #dce5e4}.document-viewport img,.enlarged-document{display:block;width:100%;height:auto}.document-loading{height:360px;background:#e8eeee;border-radius:6px}.document-error{font-size:13px;color:#a7313c;line-height:1.7}.funds-assessment nav{display:flex;align-items:center;justify-content:space-between;gap:8px;font-size:12px;margin-top:10px}.funds-assessment a{display:inline-block;margin-top:12px;font-size:12px;color:#365c53;text-underline-offset:3px}
</style>
