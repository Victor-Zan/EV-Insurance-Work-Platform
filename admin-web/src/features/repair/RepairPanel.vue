<script setup lang="ts">
import PhotoPicker from '@/shared/ui/PhotoPicker.vue'
import RecordList from '@/shared/ui/RecordList.vue'
import {label,localTime} from '@/shared/ui/presentation'
import {computed,onMounted,ref,watch} from 'vue'
import {currentUser,httpClient} from '@/shared/http/client'
import {repairApi,type RepairCase,type RepairEntry} from './repair'
const props=defineProps<{caseId:string;refreshToken?:number}>(),emit=defineEmits<{changed:[]}>(),api=repairApi(httpClient,props.caseId)
const data=ref<RepairCase>(),history=ref<RepairEntry[]>([]),page=ref(1),total=ref(0),busy=ref(false),message=ref(''),note=ref(''),selectedPhotos=ref<string[]>([])
const editable=computed(()=>currentUser.value?.roles.some(role=>role==='REPAIR_SHOP'||role==='CUSTOMER_SERVICE')&&data.value?.status==='REPAIRING')
const pending=ref<{body:string;key:string}>()
const cs=computed(()=>currentUser.value?.roles.includes('CUSTOMER_SERVICE')),owner=computed(()=>currentUser.value?.roles.includes('OWNER'))
const reason=ref(''),reviewText=ref(''),score=ref(''),reviewHistory=ref(''),reviewPage=ref(1),reviewTotal=ref(0)
async function mutate(operation:'receipt/withdraw'|'review'|'review/corrections'){
  if(!data.value)return
  const body={expectedVersion:data.value.version,...(operation==='receipt/withdraw'?{reason:reason.value}:{text:reviewText.value,score:score.value===''?null:Number(score.value),...(operation==='review/corrections'?{reason:reason.value}:{})})}
  const serialized=JSON.stringify({operation,body});if(pending.value?.body!==serialized)pending.value={body:serialized,key:crypto.randomUUID()}
  data.value=await api.post(operation,body,pending.value.key);pending.value=undefined;message.value='已保存，原记录保留';emit('changed');await load()
}
async function loadReviewHistory(){const result=(await httpClient.get<{data:{records:unknown[];total:number}}>(`/repairs/cases/${props.caseId}/review/history`,{params:{page:reviewPage.value,size:20}})).data.data;reviewHistory.value=JSON.stringify(result.records,null,2);reviewTotal.value=result.total}
const canReceive=computed(()=>data.value?.status==='WAITING_OWNER_CONFIRMATION'&&currentUser.value?.roles.some(role=>role==='OWNER'||role==='CUSTOMER_SERVICE'))
async function receive(){
  if(!data.value)return
  const body={expectedVersion:data.value.version},serialized=JSON.stringify({operation:'receipt',body})
  if(pending.value?.body!==serialized)pending.value={body:serialized,key:crypto.randomUUID()}
  data.value=await api.post('receipt',body,pending.value.key);pending.value=undefined;message.value='已确认收车，工单完成';emit('changed');await load()
}
async function load(){data.value=await api.get();const result=await api.history(page.value);history.value=result.records;total.value=result.total}
async function run(action:()=>Promise<void>){busy.value=true;message.value='';try{await action()}catch(e){message.value=e instanceof Error?e.message:'操作失败'}finally{busy.value=false}}
async function submit(operation:'progress'|'completion'){
  if(!data.value)return
  const photoIds=selectedPhotos.value
  const body={expectedVersion:data.value.version,assignmentVersion:data.value.assignmentVersion,photoIds,...(operation==='progress'?{note:note.value}:{})}
  const serialized=JSON.stringify({operation,body})
  if(pending.value?.body!==serialized)pending.value={body:serialized,key:crypto.randomUUID()}
  data.value=await api.post(operation,body,pending.value.key);pending.value=undefined;message.value=operation==='completion'?'完工已提交，照片版本已冻结':'进度已追加'
  note.value='';selectedPhotos.value=[];await load();emit('changed')
}
onMounted(()=>run(load));watch(()=>props.refreshToken,()=>run(load))
</script>
<template><section class="repair"><h2>维修进度与完工</h2><button :disabled="busy" @click="run(load)">刷新</button><p role="status">{{ message }}</p><template v-if="data"><p>状态 {{ label(data.status) }}；维修记录版本 {{ data.version }}</p>
<div v-if="editable"><label>进度说明<textarea v-model="note" maxlength="2000" rows="3"/></label><PhotoPicker v-model="selectedPhotos" :case-id="caseId" :categories="['PROGRESS_PHOTO','COMPLETION_PHOTO']" :refresh-token="refreshToken"/><p>进度照片可选；完工需至少一张本网点本次派单上传的有效 JPG/PNG 完工照，提交后证据不可替换或作废。</p><button :disabled="busy" @click="run(()=>submit('progress'))">追加进度</button><button :disabled="busy" @click="run(()=>submit('completion'))">提交完工</button></div>
<div v-if="data.completion"><h3>完工证据快照</h3><p>{{ localTime(data.completion.createdAt) }}</p><ul><li v-for="photo in data.completion.photos" :key="photo.id">{{ photo.id }}，版本 {{ photo.version }}</li></ul></div>
<div v-if="canReceive"><p>确认已收车后工单完成，评价和评分均非必填。</p><button :disabled="busy" @click="run(receive)">确认已收车</button></div><p v-if="data.receipt">收车确认：{{ data.receipt.confirmedBy==='OWNER'?'车主':'客服代录' }}，{{ localTime(data.receipt.createdAt) }}；{{ data.receipt.withdrawn?'已撤回':'有效' }}</p>
<div v-if="cs&&data.status==='COMPLETED'"><label>客服撤回/纠正原因<input v-model="reason" maxlength="2000"/></label><button :disabled="busy" @click="run(()=>mutate('receipt/withdraw'))">撤回收车至待确认</button></div>
<section v-if="data.review"><h3>车主原评价</h3><p>{{ data.review.original.text||'未填写文字' }}；评分 {{ data.review.original.score??'未评分' }}</p><p>当前版本来源：{{ label(data.review.current.source) }}；{{ data.review.current.text }}；评分 {{ data.review.current.score??'未评分' }}；{{ data.review.eligibleForCurrentRating?'计入当前评分':'历史评价，不计当前评分' }}</p><button :disabled="busy" @click="run(loadReviewHistory)">查询纠正历史</button><RecordList :value="reviewHistory"/><button :disabled="busy||reviewPage<=1" @click="reviewPage--;run(loadReviewHistory)">上一页</button><button :disabled="busy||reviewPage*20>=reviewTotal" @click="reviewPage++;run(loadReviewHistory)">下一页</button></section>
<div v-if="data.status==='COMPLETED'&&((owner&&!data.review)||(cs&&data.review))"><h3>{{ cs?'客服追加评价纠正版本':'可选评价与评分' }}</h3><label>文字评价<textarea v-model="reviewText" maxlength="2000"/></label><label>评分（可不填）<input v-model="score" type="number" min="1" max="5" step="1"/></label><p>评价与评分可完全跳过；提交时至少填写一项。车主提交后不能修改。</p><button :disabled="busy" @click="run(()=>mutate(cs?'review/corrections':'review'))">{{ cs?'追加纠正版本':'提交评价' }}</button></div>
<h3>历史进度（共 {{ total }} 条）</h3><article v-for="item in history" :key="item.id"><p>{{ localTime(item.createdAt) }} — {{ item.note }}</p><ul><li v-for="photo in item.photos" :key="photo.id">{{ photo.id }}，版本 {{ photo.version }}</li></ul></article><button :disabled="busy||page<=1" @click="page--;run(load)">上一页</button><span>{{ page }}</span><button :disabled="busy||page*20>=total" @click="page++;run(load)">下一页</button></template></section></template>
<style scoped>.repair{padding:20px;margin:20px 0;border:1px solid #ddd}label{display:block;margin:10px 0}input,textarea{width:100%;box-sizing:border-box}button{margin:6px}li{overflow-wrap:anywhere}article{border-top:1px solid #eee}</style>
