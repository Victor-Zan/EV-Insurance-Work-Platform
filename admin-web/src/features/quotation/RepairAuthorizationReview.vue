<script setup lang="ts">
import {onUnmounted,ref,watch} from 'vue'
import {httpClient} from '@/shared/http/client'
import type {QuoteCase} from './quotation'
import {moneyCents,yuan} from './quote-review'
const props=defineProps<{caseId:string;quote:QuoteCase;readonly?:boolean}>()
const pdf=ref(''),pdfOriginal=ref(''),loss=ref(''),lossImage=ref(''),lossType=ref(''),loading=ref(false),error=ref(''),amount=ref(''),page=ref(1),pages=ref(1),lossPage=ref(1),lossPages=ref(1)
let generation=0
function release(){for(const url of new Set([pdf.value,pdfOriginal.value,loss.value,lossImage.value]))if(url)URL.revokeObjectURL(url);pdf.value='';pdfOriginal.value='';loss.value='';lossImage.value=''}
async function load(){
 const request=++generation;release();amount.value='';error.value=''
 const formal=props.quote.formalQuote,assessment=props.quote.assessment
 if(!formal||!assessment?.sourceCurrent){loading.value=false;error.value='请先修正核损，保存当前最终核损金额。';return}
 loading.value=true
 try{
  const [quoteFile,lossFile]=await Promise.all([httpClient.get<Blob>(`/quotations/cases/${props.caseId}/formal-quotes/${formal.id}/preview?page=${page.value}`,{responseType:'blob'}),assessment.lossFileId?httpClient.get<Blob>(`/materials/${assessment.lossFileId}/download`,{responseType:'blob'}):Promise.resolve(undefined)])
  const lossPreview=lossFile?.data.type==='application/pdf'?await httpClient.get<Blob>(`/materials/${assessment.lossFileId}/preview?page=${lossPage.value}`,{responseType:'blob'}):undefined
  if(request!==generation)return
  pdf.value=URL.createObjectURL(quoteFile.data);loss.value=lossFile?URL.createObjectURL(lossFile.data):'';lossImage.value=lossPreview?URL.createObjectURL(lossPreview.data):loss.value
  lossType.value=lossFile?.data.type??'';pages.value=Number(quoteFile.headers?.['x-page-count']??1);lossPages.value=Number(lossPreview?.headers?.['x-page-count']??1)
 }catch(e){if(request===generation)error.value=e instanceof Error?e.message:'单据加载失败，请重试'}
 finally{if(request===generation)loading.value=false}
}
async function downloadQuote(){
 const request=generation
 try{if(!pdfOriginal.value){const response=await httpClient.get<Blob>(`/quotations/cases/${props.caseId}/formal-quotes/${props.quote.formalQuote!.id}/pdf`,{responseType:'blob'});if(request!==generation)return;pdfOriginal.value=URL.createObjectURL(response.data)}const link=document.createElement('a');link.href=pdfOriginal.value;link.download='正式报价单.pdf';link.click()}
 catch(e){if(request===generation)error.value=e instanceof Error?e.message:'PDF下载失败'}
}
function confirmation(){
 const assessment=props.quote.assessment
 if(loading.value||error.value||!pdf.value||!assessment?.sourceCurrent||(assessment.lossFileId&&!lossImage.value))throw Error('请先加载并核对当前正式报价单和最终核损金额')

 let value:bigint;try{value=moneyCents(amount.value)}catch{throw Error('请填写有效确认金额，最多2位小数')}
 if(value!==moneyCents(assessment.amount))throw Error('确认金额必须与最终核损金额一致')
 return {assessmentId:assessment.id,...(assessment.lossFileId?{lossFileId:assessment.lossFileId}:{}),amount:yuan(value),originalReviewed:true}
}
defineExpose({confirmation})
watch(()=>[props.caseId,props.quote.formalQuote?.id,props.quote.assessment?.id,props.quote.assessment?.lossFileId,props.quote.assessment?.sourceCurrent].join('|'),()=>{page.value=1;lossPage.value=1;void load()},{immediate:true})
onUnmounted(()=>{generation++;release()})
</script>
<template><section class="authorization-review" data-reading="最终价格核对"><h3>最终价格核对</h3><p v-if="error" role="alert" class="review-error">{{ error }} <button @click="load">重新加载单据</button></p><div v-if="loading" class="document-loading" aria-busy="true" aria-label="正在加载正式报价单"><span/><span/></div><div v-else-if="pdf" class="document-grid" :class="{'single-document':!loss}"><section><header><h4>正式报价单</h4><span>{{ quote.formalQuote?.total }} 元</span></header><div class="loss-viewport"><img :src="pdf" alt="正式报价单原件" @error="error='正式报价单预览加载失败，请重新加载'"/></div><nav v-if="pages>1" aria-label="正式报价单翻页"><button :disabled="page===1" @click="page--;load()">上一页</button><span>{{ page }}/{{ pages }}</span><button :disabled="page===pages" @click="page++;load()">下一页</button></nav><button class="download-link" @click="downloadQuote">下载正式报价单</button></section><section v-if="loss"><header><h4>历史核损单</h4><span>{{ quote.assessment?.amount }} 元</span></header><div class="loss-viewport"><img :src="lossImage" alt="最终核损单原件" @error="error='核损单原件无法显示，请重新加载或核对原文件'"/></div><nav v-if="lossPages>1" aria-label="核损单翻页"><button :disabled="lossPage===1" @click="lossPage--;load()">上一页</button><span>{{ lossPage }}/{{ lossPages }}</span><button :disabled="lossPage===lossPages" @click="lossPage++;load()">下一页</button></nav><a :href="loss" :download="'最终核损单'+(lossType==='image/png'?'.png':lossType==='image/jpeg'?'.jpg':'.pdf')">下载最终核损单</a></section></div><div class="price-check"><div><span>最终核损金额</span><strong>{{ quote.assessment?.amount??'—' }} <small>元</small></strong></div><label>确认授权金额（元）<input v-model="amount" aria-label="确认授权金额（元）" :disabled="readonly||loading||Boolean(error)" inputmode="decimal" autocomplete="off"/></label></div><p class="price-note">授权金额以最终核损金额为准，点击客服确认即确认已核对正式报价和最终金额。</p></section></template>
<style scoped>
.authorization-review{border-top:1px solid #dce5e4;padding:20px 0}.authorization-review h3{font-size:17px;margin:0 0 16px}.document-grid,.document-loading{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px}.document-grid.single-document{grid-template-columns:1fr}.document-grid section{min-width:0}.document-grid header{display:flex;justify-content:space-between;align-items:center;gap:8px;padding:12px 0;border-bottom:1px solid #dce5e4;margin-bottom:12px}.document-grid h4{margin:0;font-size:14px}.document-grid header span{font-size:12px;font-variant-numeric:tabular-nums}.loss-viewport{height:480px;overflow:auto}.loss-viewport img{width:100%;display:block}.document-grid nav{display:flex;justify-content:space-between;align-items:center;font-size:12px}.document-grid .download-link{border:0!important;background:none!important;padding:0!important;text-decoration:underline}.document-grid a,.document-grid .download-link{display:inline-block;margin-top:10px;font-size:12px;color:#365c53;text-underline-offset:3px}.document-loading span{height:480px;background:#e8eeee;border-radius:6px}.price-check{display:grid;grid-template-columns:1fr 1fr;gap:20px;align-items:end;border-top:1px solid #dce5e4;padding-top:20px;margin-top:24px}.price-check>div{display:grid;gap:12px}.price-check span,.price-check label{font-size:12px;color:#536a6b}.price-check strong{font-size:24px;color:#275c44;font-variant-numeric:tabular-nums}.price-check small{font-size:12px;font-weight:400}.price-check label{display:grid;gap:8px}.price-check input{padding:12px;border:1px solid #b7c9c0;border-radius:6px;font:inherit;color:#20383c;min-width:0}.price-note{color:#536a6b;font-size:12px}.review-error{color:#a7313c;font-size:13px}@media(max-width:1000px){.document-grid,.document-loading{grid-template-columns:1fr}}@media(max-width:600px){.price-check{grid-template-columns:1fr}}
</style>
