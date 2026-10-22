<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { currentUser, httpClient } from '@/shared/http/client'
import OcrReviewPanel from './OcrReviewPanel.vue'
import { ocrIntakeApi } from './ocr-intake'
const route=useRoute(),router=useRouter(),selected=ref<File>(),busy=ref(false),message=ref(''),provider=ref(''),uploadKey=ref(crypto.randomUUID())
const cs=computed(()=>currentUser.value?.roles.includes('CUSTOMER_SERVICE')===true),jobId=computed(()=>String(route.query.job??''))
function choose(event:Event){selected.value=(event.target as HTMLInputElement).files?.[0];uploadKey.value=crypto.randomUUID()}
const intakeApi=ocrIntakeApi(httpClient)
async function upload(){if(!selected.value){message.value='请选择手写单据';return}busy.value=true;message.value='';try{const result=await intakeApi.upload(selected.value,uploadKey.value);await router.replace({path:'/ocr/intake',query:{job:result.id}})}catch(error){message.value=error instanceof Error?error.message:'上传失败'}finally{busy.value=false}}
onMounted(async()=>{try{provider.value=(await httpClient.get<{data:{provider:string}}>('/ocr/configuration')).data.data.provider}catch(error){message.value=error instanceof Error?error.message:'配置读取失败'}})
</script>
<template><main><h1>手写单据建单</h1><p>尚无案件也可上传。当前供应商：{{ provider }}。{{ provider==='MOCK_V1'?'当前仅合成演示，未调用真实识别。':'将向百度发送此单据进行文字识别，可能消耗额度。' }}</p><p>一张单据一个独立任务；只预填待核对表单，人工确认后再保存草稿并通过原流程正式提交。维修明细不生成正式报价。聊天整包图片不会在此批量识别。</p><p role="alert">{{ message }}</p><div v-if="cs"><FileDropZone hint="一次1张JPG/JPEG/PNG，≤10 MiB；选择后点击“上传并发起识别”，可能消耗额度。" :max-bytes="10485760" :feedback="message" :disabled="busy" :busy="busy"><input type="file" accept=".jpg,.jpeg,.png" :disabled="busy" aria-label="手写单据图片" @change="choose"></FileDropZone><button :disabled="busy||!selected" @click="upload">{{ busy?'正在上传…':'上传并发起识别' }}</button><p>JPEG/PNG，单文件不超过10 MiB；真实 OCR 另有尺寸及编码限制，超限会明确失败。</p></div><p v-if="cs&&jobId"><RouterLink :to="{path:'/work-orders',query:{create:'1',ocrJob:jobId}}">在原创建工单表单中继续（不再次识别）</RouterLink></p><p v-if="cs&&jobId"><RouterLink :to="{path:'/work-orders',query:{create:'1',ocrJob:jobId}}">在原创建工单表单中继续（不再次识别）</RouterLink></p><OcrReviewPanel v-if="jobId" :key="jobId" :job-id="jobId"/><RouterLink to="/work-orders">返回案件列表</RouterLink></main></template>
