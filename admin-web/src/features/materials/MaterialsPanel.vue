<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { currentUser, httpClient } from '@/shared/http/client'
const emit=defineEmits<{changed:[]}>()
const props=defineProps<{caseId:string;status:string;assignmentVersion?:number;allowedCategories:string[]}>()
interface Attachment {id:string;category:string;name:string;state:string;version:number;size:number}
const labels:Record<string,string>={SCENE_PHOTO:'现场物损照片',NOTICE:'历史通知单',SHOP_QUOTE:'网点报价/明细',ASSESSMENT:'定损单',LOSS_ASSESSMENT:'核损单',ARRIVAL_PHOTO:'到店照',PROGRESS_PHOTO:'进度照',COMPLETION_PHOTO:'完工照'}
const cs=computed(()=>currentUser.value?.roles.includes('CUSTOMER_SERVICE'))
const staff=computed(()=>cs.value||currentUser.value?.roles.includes('ADMIN'))
const shop=computed(()=>currentUser.value?.roles.includes('REPAIR_SHOP'))
const writable=computed(()=>!['COMPLETED','CANCELLED','CLOSED'].includes(props.status))
const categories=computed(()=>props.allowedCategories.filter(x=>cs.value||(x==='SCENE_PHOTO'&&staff.value)||(shop.value&&(x==='SHOP_QUOTE'||x==='ASSESSMENT'||x.endsWith('_PHOTO')))))
const rows=ref<Attachment[]>([]),page=ref(1),total=ref(0),category=ref(props.allowedCategories[0]??'NOTICE'),selected=ref<File>(),replacement=ref(''),message=ref(''),busy=ref(false),reason=ref(''),notice=ref<{noticePresent:boolean;missingReason:string}>()
async function call<T>(method:'get'|'post',url:string,data?:unknown,headers?:Record<string,string>){return (await httpClient.request<{data:T}>({method,url,data,headers})).data.data}
async function load(){const data=await call<{records:Attachment[];total:number}>('get',`/materials/cases/${props.caseId}?page=${page.value}&size=20`);rows.value=data.records;total.value=data.total;if(staff.value)notice.value=await call('get',`/materials/cases/${props.caseId}/notice-status`)}
async function run(action:()=>Promise<void>){busy.value=true;message.value='';try{await action();await load()}catch(e){message.value=e instanceof Error?e.message:'操作失败'}finally{busy.value=false}}
function choose(event:Event){selected.value=(event.target as HTMLInputElement).files?.[0]}
async function upload(){if(!selected.value)throw Error('请选择文件');const body=new FormData();body.append('file',selected.value);body.append('category',category.value);if(replacement.value)body.append('replace',replacement.value);if(props.assignmentVersion)body.append('assignmentVersion',String(props.assignmentVersion));await httpClient.post(`/materials/cases/${props.caseId}`,body,{headers:{'Content-Type':undefined,'Idempotency-Key':crypto.randomUUID()}});replacement.value='';selected.value=undefined;emit('changed');message.value='已真实上传并保存元数据'}
async function download(file:Attachment){const response=await httpClient.get(`/materials/${file.id}/download`,{responseType:'blob'});const url=URL.createObjectURL(response.data);const a=document.createElement('a');a.href=url;a.download=file.name;a.click();URL.revokeObjectURL(url)}
onMounted(()=>run(load))
</script>
<template><section class="materials"><h2>案件附件</h2><p>PDF/JPG/PNG；单文件 10 MiB；每类最多 20 个有效附件。完成或取消后只读。</p><p role="status">{{ message }}</p><p v-if="notice?.noticePresent||notice?.missingReason">历史通知单：{{ notice.noticePresent?'已补齐':'缺失' }}；历史缺失原因：{{ notice.missingReason||'无' }}</p><div v-if="writable&&cs&&allowedCategories.includes('NOTICE')"><input v-model="reason" placeholder="通知单缺失原因"><button :disabled="busy" @click="run(async()=>{notice=await call('post',`/materials/cases/${caseId}/missing-notice`,{reason})})">记录缺失</button></div>
<div v-if="writable&&categories.length&&(staff||shop)"><fieldset v-for="c in categories" :key="c"><legend>{{ labels[c] }}</legend><input type="file" accept=".pdf,.jpg,.jpeg,.png" :disabled="busy" @change="category=c;choose($event)"/><span v-if="replacement&&category===c">替换已选原件，旧版保留</span><button :disabled="busy||!selected||category!==c" @click="run(upload)">上传{{ labels[c] }}</button><button v-if="replacement&&category===c" @click="replacement=''">取消替换</button></fieldset></div>
<ul><li v-for="file in rows" :key="file.id">{{ labels[file.category] }} · {{ file.name }} · v{{ file.version }} · {{ file.state }} · {{ file.size }} B <button @click="run(()=>download(file))">受控下载</button><template v-if="(cs||(staff&&file.category==='SCENE_PHOTO'))&&writable&&file.state==='ACTIVE'&&allowedCategories.includes(file.category)"><button @click="replacement=file.id;category=file.category">替换</button><button @click="run(async()=>{await call('post',`/materials/${file.id}/void`);emit('changed')})">作废</button></template></li></ul>
<button :disabled="page===1||busy" @click="page--;run(load)">上一页</button><span>第 {{ page }} 页 / 共 {{ total }} 条</span><button :disabled="page*20>=total||busy" @click="page++;run(load)">下一页</button>
</section></template>
<style scoped>.materials{padding:20px;margin:20px 0;border:1px solid #ddd}input,select,button{margin:6px;max-width:100%}li{margin:12px 0;overflow-wrap:anywhere}textarea{width:100%;box-sizing:border-box}pre{white-space:pre-wrap}</style>
