<script setup lang="ts">
import {Rate} from 'vant'
import {quotationApi,type QuoteCase} from '../quotation/quotation'
import CompletionEvidence from './CompletionEvidence.vue'
import PhotoPicker from '@/shared/ui/PhotoPicker.vue'
import RecordList from '@/shared/ui/RecordList.vue'
import {label,localTime} from '@/shared/ui/presentation'
import {computed,onMounted,ref,watch} from 'vue'
import {currentUser,httpClient} from '@/shared/http/client'
import {repairApi,type RepairCase,type RepairEntry} from './repair'
const props=defineProps<{caseId:string;refreshToken?:number;receiptMode?:boolean}>(),emit=defineEmits<{changed:[]}>(),api=repairApi(httpClient,props.caseId)
const data=ref<RepairCase>(),history=ref<RepairEntry[]>([]),page=ref(1),total=ref(0),busy=ref(false),message=ref(''),note=ref(''),selectedPhotos=ref<string[]>([]),photoCategory=ref<'PROGRESS_PHOTO'|'COMPLETION_PHOTO'>('COMPLETION_PHOTO')
const editable=computed(()=>currentUser.value?.roles.some(role=>role==='REPAIR_SHOP'||role==='CUSTOMER_SERVICE')&&data.value?.status==='REPAIRING')
const pending=ref<{body:string;key:string}>()
const shop=computed(()=>currentUser.value?.roles.includes('REPAIR_SHOP')===true),completionOpen=ref(false),authorization=ref<QuoteCase>(),startKey=ref(crypto.randomUUID())
const canFinish=computed(()=>editable.value||Boolean(shop.value&&data.value?.status==='WAITING_REPAIR_AUTHORIZATION'&&authorization.value?.authorized&&authorization.value.gate.allowed))
const cs=computed(()=>currentUser.value?.roles.includes('CUSTOMER_SERVICE')),owner=computed(()=>currentUser.value?.roles.includes('OWNER'))
const reason=ref(''),reviewText=ref(''),score=ref(0),reviewHistory=ref(''),reviewPage=ref(1),reviewTotal=ref(0)
async function mutate(operation:'receipt/withdraw'|'review'|'review/corrections'){
  if(!data.value)return
  const body={expectedVersion:data.value.version,...(operation==='receipt/withdraw'?{reason:reason.value}:{text:reviewText.value,score:score.value===0?null:score.value,...(operation==='review/corrections'?{reason:reason.value}:{})})}
  const serialized=JSON.stringify({operation,body});if(pending.value?.body!==serialized)pending.value={body:serialized,key:crypto.randomUUID()}
  data.value=await api.post(operation,body,pending.value.key);pending.value=undefined;message.value='已保存，原记录保留';emit('changed');await load()
}
async function loadReviewHistory(){const result=(await httpClient.get<{data:{records:unknown[];total:number}}>(`/repairs/cases/${props.caseId}/review/history`,{params:{page:reviewPage.value,size:20}})).data.data;reviewHistory.value=JSON.stringify(result.records,null,2);reviewTotal.value=result.total}
const canReceive=computed(()=>data.value?.status==='WAITING_OWNER_CONFIRMATION'&&currentUser.value?.roles.some(role=>role==='OWNER'||role==='CUSTOMER_SERVICE'))
async function receive(){
  if(!data.value)return
  const body={expectedVersion:data.value.version},serialized=JSON.stringify({operation:'receipt',body})
  if(pending.value?.body!==serialized)pending.value={body:serialized,key:crypto.randomUUID()}
  data.value=await api.post('receipt',body,pending.value.key);pending.value=undefined;message.value='已确认收车，可选择评价';emit('changed');await load()
}
async function load(){data.value=await api.get();if(shop.value&&data.value.status==='WAITING_REPAIR_AUTHORIZATION')authorization.value=await quotationApi(httpClient,props.caseId).get();const result=await api.history(page.value);history.value=result.records;total.value=result.total}
async function run(action:()=>Promise<void>){if(busy.value)return;busy.value=true;message.value='';try{await action()}catch(e){message.value=e instanceof Error?e.message:'操作失败'}finally{busy.value=false}}
async function openCompletion(){
 if(!canFinish.value)return
 if(data.value?.status==='WAITING_REPAIR_AUTHORIZATION'){
  const context=authorization.value;if(!context)return
  await quotationApi(httpClient,props.caseId).post('start-repair',{expectedVersion:context.version,assignmentVersion:context.assignmentVersion},startKey.value)
  await load();emit('changed')
 }
 completionOpen.value=true;photoCategory.value='COMPLETION_PHOTO'
}
async function submit(operation:'progress'|'completion'){
  if(!data.value)return
  if(operation==='progress'&&!note.value.trim())throw Error('请填写进度说明后再追加进度')
  if(operation==='progress'&&selectedPhotos.value.length&&photoCategory.value!=='PROGRESS_PHOTO')throw Error('追加进度只能选择进度照；请切换照片用途或清除当前选择')
  if(operation==='completion'&&(photoCategory.value!=='COMPLETION_PHOTO'||!selectedPhotos.value.length))throw Error('提交完工前，请选择“完工照”并上传或勾选至少一张照片')
  const photoIds=selectedPhotos.value
  const body={expectedVersion:data.value.version,assignmentVersion:data.value.assignmentVersion,photoIds,...(operation==='progress'?{note:note.value}:{})}
  const serialized=JSON.stringify({operation,body})
  if(pending.value?.body!==serialized)pending.value={body:serialized,key:crypto.randomUUID()}
  data.value=await api.post(operation,body,pending.value.key);pending.value=undefined;message.value=operation==='completion'?'完工已提交，照片版本已冻结':'进度已追加'
  note.value='';selectedPhotos.value=[];await load();emit('changed')
}
onMounted(()=>run(load));watch(()=>props.refreshToken,()=>run(load))
</script>
<template><section class="repair"><header class="repair-heading"><h2>{{ shop&&['REPAIRING','WAITING_REPAIR_AUTHORIZATION'].includes(data?.status||'')?(completionOpen?'上传完工照':'维修中'):receiptMode&&data?.status==='WAITING_OWNER_CONFIRMATION'?'收车确认':owner&&['WAITING_INSURER_PAYMENT','WAITING_SHOP_SETTLEMENT','COMPLETED'].includes(data?.status||'')?'服务评价':'维修与收车' }}</h2><button v-if="!shop" :disabled="busy" @click="run(load)">刷新</button></header><p v-if="message&&(!receiptMode||!data)" role="status">{{ message }}</p><template v-if="data"><p v-if="!owner&&(!shop||!completionOpen)">{{ shop&&data.status==='WAITING_REPAIR_AUTHORIZATION'&&canFinish?'已授权，等待维修完成':label(data.status) }}</p>
<button v-if="shop&&canFinish&&!completionOpen" type="button" :disabled="busy" @click="run(openCompletion)">维修完成</button><p v-if="shop&&data.status==='WAITING_REPAIR_AUTHORIZATION'&&!canFinish" role="status">等待客服完成维修授权</p><div v-if="editable&&(!shop||completionOpen)"><label v-if="!shop">进度说明<textarea v-model="note" maxlength="2000" rows="3"/></label><label v-if="!shop">照片用途<select v-model="photoCategory"><option value="PROGRESS_PHOTO">进度照（追加进度时使用）</option><option value="COMPLETION_PHOTO">完工照（提交完工时必传）</option></select></label><PhotoPicker v-model="selectedPhotos" :case-id="caseId" :categories="[photoCategory]" :upload-category="photoCategory" :assignment-version="data.assignmentVersion" :refresh-token="refreshToken"/><p>请上传至少一张完工照片。</p><button v-if="!shop" :disabled="busy" @click="run(()=>submit('progress'))">追加进度</button><button :disabled="busy" @click="run(()=>submit('completion'))">确认维修完成</button><button v-if="shop" type="button" :disabled="busy" @click="completionOpen=false">返回</button></div>
<CompletionEvidence v-if="data.completion" :completion="data.completion" :compact="owner"/>
<p v-if="canReceive&&receiptMode" class="receipt-note">核对完工照片后确认收车。评价与评分可完全跳过。</p><p v-if="data.receipt">收车确认：{{ data.receipt.confirmedBy==='OWNER'?'车主':'客服代录' }}，{{ localTime(data.receipt.createdAt) }}；{{ data.receipt.withdrawn?'已撤回':'有效' }}</p>
<div v-if="cs&&['WAITING_INSURER_PAYMENT','WAITING_SHOP_SETTLEMENT','COMPLETED'].includes(data.status)"><label>客服撤回/纠正原因<input v-model="reason" maxlength="2000"/></label><button :disabled="busy" @click="run(()=>mutate('receipt/withdraw'))">撤回收车至待确认</button></div>
<section v-if="owner&&data.review" class="owner-review"><h3>我的评价</h3><Rate :model-value="data.review.current.score??0" readonly aria-label="已提交评分"/><p v-if="data.review.current.text">{{ data.review.current.text }}</p><p class="review-submitted">评价已提交</p></section><section v-else-if="data.review"><h3>车主原评价</h3><p>{{ data.review.original.text||'未填写文字' }}；评分 {{ data.review.original.score??'未评分' }}</p><p>当前版本来源：{{ label(data.review.current.source) }}；{{ data.review.current.text }}；评分 {{ data.review.current.score??'未评分' }}；{{ data.review.eligibleForCurrentRating?'计入当前评分':'历史评价，不计当前评分' }}</p><button :disabled="busy" @click="run(loadReviewHistory)">查询纠正历史</button><RecordList :value="reviewHistory"/><button :disabled="busy||reviewPage<=1" @click="reviewPage--;run(loadReviewHistory)">上一页</button><button :disabled="busy||reviewPage*20>=reviewTotal" @click="reviewPage++;run(loadReviewHistory)">下一页</button></section>
<div v-if="['WAITING_INSURER_PAYMENT','WAITING_SHOP_SETTLEMENT','COMPLETED'].includes(data.status)&&((owner&&!data.review)||(cs&&data.review))"><h3>{{ cs?'客服追加评价纠正版本':'可选评价与评分' }}</h3><label>文字评价<textarea v-model="reviewText" maxlength="2000"/></label><div class="review-stars"><span>评分（选填）</span><Rate v-model="score" :count="5" clearable :allow-half="false" :disabled="busy" aria-label="服务评分（最多5星）"/></div><p>评价和评分可选填，提交后无法修改。</p><button :disabled="busy" @click="run(()=>mutate(cs?'review/corrections':'review'))">{{ cs?'追加纠正版本':'提交评价' }}</button></div>
<details v-if="!owner&&(!shop||!['REPAIRING','WAITING_REPAIR_AUTHORIZATION'].includes(data.status))" class="repair-history" :open="!receiptMode"><summary>历史维修记录（{{ total }} 条）</summary><article v-for="item in history" :key="item.id"><p>{{ localTime(item.createdAt) }} — {{ item.note }}</p><ul><li v-for="photo in item.photos" :key="photo.id">{{ photo.id }}，版本 {{ photo.version }}</li></ul></article><button :disabled="busy||page<=1" @click="page--;run(load)">上一页</button><span>{{ page }}</span><button :disabled="busy||page*20>=total" @click="page++;run(load)">下一页</button></details><footer v-if="receiptMode" class="receipt-action"><div><p v-if="message" role="alert">{{ message }}</p><span v-else>{{ canReceive?'核对照片后确认收车':data.review?'已提交评价':data.receipt?'已收车，可选择填写评价':'等待完工后确认收车' }}</span></div><button v-if="canReceive" type="button" :disabled="busy||!data.completion" @click="run(receive)">{{ busy?'处理中…':'确认已收车' }}</button></footer></template></section></template>
<style scoped>.review-stars{display:grid;gap:10px;margin:16px 0}.review-stars>span{font-size:14px}.owner-review{margin:20px 0}.review-submitted{font-size:12px;color:var(--color-text-secondary)}.repair-heading{display:flex;justify-content:space-between;align-items:center;gap:12px}.repair-heading h2{margin:0;font-size:22px}.repair-heading button{flex-shrink:0}.receipt-note{padding:12px;border-radius:8px;background:var(--color-success-soft);color:var(--color-success);font-size:13px;line-height:1.7}.repair-history{margin-top:24px;border-top:1px solid var(--color-border);padding-top:16px}.repair-history summary{font-size:14px;color:var(--color-text-secondary);cursor:pointer}.receipt-action{position:sticky;bottom:calc(72px + env(safe-area-inset-bottom));display:flex;align-items:center;justify-content:space-between;gap:12px;background:#f2f7f5;border:1px solid var(--color-border);border-radius:10px;padding:12px;margin-top:24px;z-index:2}.receipt-action span,.receipt-action p{font-size:12px;margin:0}.receipt-action button{flex-shrink:0;margin:0;background:var(--color-success)!important;color:#fff!important}.repair article p{font-size:13px;line-height:1.7}label{display:block;margin:10px 0}input,textarea,select{width:100%;box-sizing:border-box}li{overflow-wrap:anywhere}article{border-top:1px solid var(--color-border)}</style>
