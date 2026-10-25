<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElDialog } from 'element-plus'
import { currentUser, httpClient } from '@/shared/http/client'
const emit=defineEmits<{changed:[]}>()
const props=defineProps<{caseId:string;status:string;assignmentVersion?:number;allowedCategories:string[];compact?:boolean}>()
interface Attachment {id:string;category:string;name:string;state:string;version:number;size:number;contentType:string}
const labels:Record<string,string>={SCENE_PHOTO:'现场物损照片',NOTICE:'历史通知单',SHOP_QUOTE:'网点报价/明细',ASSESSMENT:'定损单',LOSS_ASSESSMENT:'核损单',ARRIVAL_PHOTO:'到店照',PROGRESS_PHOTO:'进度照',COMPLETION_PHOTO:'完工照'}
const cs=computed(()=>currentUser.value?.roles.includes('CUSTOMER_SERVICE'))
const staff=computed(()=>cs.value||currentUser.value?.roles.includes('ADMIN'))
const shop=computed(()=>currentUser.value?.roles.includes('REPAIR_SHOP'))
const writable=computed(()=>!['COMPLETED','CANCELLED','CLOSED'].includes(props.status))
const categories=computed(()=>props.allowedCategories.filter(x=>cs.value||(x==='SCENE_PHOTO'&&staff.value)||(shop.value&&(x==='SHOP_QUOTE'||x==='ASSESSMENT'||x.endsWith('_PHOTO')))))
const rows=ref<Attachment[]>([]),page=ref(1),total=ref(0),category=ref(props.allowedCategories[0]??'NOTICE'),selected=ref<File>(),replacement=ref(''),message=ref(''),busy=ref(false),reason=ref(''),notice=ref<{noticePresent:boolean;missingReason:string}>()
const expandedCategory=ref(''),previewOpen=ref(false),previewUrl=ref(''),previewType=ref(''),previewName=ref('')
let disposed=false
async function call<T>(method:'get'|'post',url:string,data?:unknown,headers?:Record<string,string>){return (await httpClient.request<{data:T}>({method,url,data,headers})).data.data}
async function load(){const data=await call<{records:Attachment[];total:number}>('get',`/materials/cases/${props.caseId}?page=${page.value}&size=20`);rows.value=data.records;total.value=data.total;if(staff.value)notice.value=await call('get',`/materials/cases/${props.caseId}/notice-status`)}
async function run(action:()=>Promise<void>){busy.value=true;message.value='';try{await action();await load()}catch(e){message.value=e instanceof Error?e.message:'操作失败'}finally{busy.value=false}}
function choose(event:Event){selected.value=(event.target as HTMLInputElement).files?.[0]}
async function upload(){if(!selected.value)throw Error('请选择文件');const body=new FormData();body.append('file',selected.value);body.append('category',category.value);if(replacement.value)body.append('replace',replacement.value);if(props.assignmentVersion)body.append('assignmentVersion',String(props.assignmentVersion));await httpClient.post(`/materials/cases/${props.caseId}`,body,{headers:{'Content-Type':undefined,'Idempotency-Key':crypto.randomUUID()}});replacement.value='';selected.value=undefined;emit('changed');message.value='已真实上传并保存元数据'}
async function download(file:Attachment){const response=await httpClient.get(`/materials/${file.id}/download`,{responseType:'blob'});const url=URL.createObjectURL(response.data);const a=document.createElement('a');a.href=url;a.download=file.name;a.click();URL.revokeObjectURL(url)}
async function preview(file:Attachment){const response=await httpClient.get<Blob>(`/materials/${file.id}/download`,{responseType:'blob'});if(disposed)return;if(!['image/png','image/jpeg','application/pdf'].includes(response.data.type))throw Error('该文件格式不支持预览，请下载查看');if(previewUrl.value)URL.revokeObjectURL(previewUrl.value);previewUrl.value=URL.createObjectURL(response.data);previewType.value=response.data.type;previewName.value=file.name;previewOpen.value=true}
function selectReplacement(file:Attachment){replacement.value=file.id;category.value=file.category;expandedCategory.value=file.category}
onMounted(()=>run(load))
onUnmounted(()=>{disposed=true;if(previewUrl.value)URL.revokeObjectURL(previewUrl.value)})
</script>
<template>
<section class="materials" :class="{compact}">
  <template v-if="!compact"><h2>案件附件</h2><p>PDF/JPG/PNG；单文件 10 MiB；每类最多 20 个有效附件。完成或取消后只读。</p></template>
  <p v-if="message" role="status">{{ message }}</p>
  <p v-if="notice?.noticePresent||notice?.missingReason">历史通知单：{{ notice.noticePresent?'已补齐':'缺失' }}；历史缺失原因：{{ notice.missingReason||'无' }}</p>
  <div v-if="writable&&cs&&allowedCategories.includes('NOTICE')"><input v-model="reason" placeholder="通知单缺失原因"><button :disabled="busy" @click="run(async()=>{notice=await call('post',`/materials/cases/${caseId}/missing-notice`,{reason})})">记录缺失</button></div>
  <div class="material-content">
    <div v-if="writable&&categories.length&&(staff||shop)" class="upload-groups">
      <h3 v-if="compact">按需补传</h3>
      <fieldset v-for="c in categories" :key="c"><legend>{{ labels[c] }}</legend>
        <button v-if="compact" type="button" class="toggle-upload" :aria-expanded="expandedCategory===c" @click="expandedCategory=expandedCategory===c?'':c">{{ expandedCategory===c?'收起':'补传'+labels[c] }}</button>
        <template v-if="!compact||expandedCategory===c">
          <FileDropZone hint="一次1个PDF/JPG/JPEG/PNG，≤10 MiB；每类最多20个有效附件。选择后点击原上传按钮。" :max-bytes="10485760" :feedback="message" :disabled="busy" :busy="busy"><input type="file" accept=".pdf,.jpg,.jpeg,.png" :disabled="busy" @change="category=c;choose($event)"/></FileDropZone>
          <span v-if="replacement&&category===c">替换已选原件，旧版保留</span><button :disabled="busy||!selected||category!==c" @click="run(upload)">上传{{ labels[c] }}</button><button v-if="replacement&&category===c" @click="replacement=''">取消替换</button>
        </template>
      </fieldset>
    </div>
    <div class="attachment-files">
      <h3 v-if="compact">已上传资料</h3>
      <p v-if="compact&&busy&&!rows.length" role="status">正在加载资料…</p><p v-else-if="compact&&!rows.length">暂无附件</p>
      <ul><li v-for="file in rows" :key="file.id">
        <div class="file-description"><span v-if="compact" class="file-category">{{ labels[file.category] }} · {{ file.state==='ACTIVE'?'有效':file.state==='SUPERSEDED'?'历史版本':'已作废' }}<span v-if="!allowedCategories.includes(file.category)||!writable"> · 仅查看</span></span><span v-else>{{ labels[file.category] }} · </span><span class="file-name">{{ file.name }}</span><small> · v{{ file.version }} · {{ compact?'':file.state+' · ' }}{{ file.size }} B</small></div>
        <div class="file-actions"><button v-if="compact" :disabled="busy" @click="run(()=>preview(file))">查看</button><button :disabled="busy" @click="run(()=>download(file))">{{ compact?'下载':'受控下载' }}</button><template v-if="(cs||(staff&&file.category==='SCENE_PHOTO'))&&writable&&file.state==='ACTIVE'&&allowedCategories.includes(file.category)"><button :disabled="busy" @click="selectReplacement(file)">替换</button><button :disabled="busy" @click="run(async()=>{await call('post',`/materials/${file.id}/void`);emit('changed')})">作废</button></template></div>
      </li></ul>
      <div class="file-pages"><button :disabled="page===1||busy" @click="page--;run(load)">上一页</button><span>第 {{ page }} 页 / 共 {{ total }} 条</span><button :disabled="page*20>=total||busy" @click="page++;run(load)">下一页</button></div>
    </div>
  </div>
  <ElDialog v-model="previewOpen" :title="previewName" width="min(1000px, 96vw)" :append-to-body="true"><img v-if="previewType.startsWith('image/')" class="attachment-preview" :src="previewUrl" :alt="previewName"/><iframe v-else-if="previewType==='application/pdf'" class="attachment-preview pdf-preview" :src="previewUrl" :title="previewName"/></ElDialog>
</section>
</template>
<style scoped>.materials{padding:20px;margin:20px 0;border:1px solid #ddd}input,select,button{margin:6px;max-width:100%}li{margin:12px 0;overflow-wrap:anywhere}textarea{width:100%;box-sizing:border-box}pre{white-space:pre-wrap}.compact{padding:0;margin:0;border:0;color:#20383c}.compact .material-content{display:flex;flex-direction:column;gap:28px}.compact .attachment-files{order:0}.compact .upload-groups{order:1}.compact h3{font-size:16px;margin:0 0 16px}.compact ul{list-style:none;padding:0;margin:0}.compact li{border-bottom:1px solid #dce5e1;margin:0;padding:16px 0}.compact li:first-child{padding-top:0}.compact .file-category{display:block;color:#617570;font-size:12px;margin-bottom:7px}.compact .file-name{font-weight:600;line-height:1.6}.compact small{color:#617570;font-size:11px}.compact .file-actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px}.compact button{font:inherit;font-size:13px;border:1px solid #b5c8c5;border-radius:7px;padding:8px 12px;cursor:pointer;background:#f3f7f5;color:#25443e;margin:0;transition:transform .18s,opacity .18s}.compact button:disabled{cursor:not-allowed;opacity:.55}.compact button:active{transform:scale(.98)}.compact button:focus-visible{outline:2px solid #40675c;outline-offset:3px}.compact fieldset{min-width:0;border:1px solid #dce5e1;border-radius:8px;padding:14px;margin:0 0 14px}.compact legend{font-size:13px;color:#526c63;padding:0 5px}.compact .toggle-upload{font-size:12px}.compact .file-pages{display:flex;flex-wrap:wrap;align-items:center;gap:10px;margin-top:18px;font-size:12px;color:#617570}.attachment-preview{width:100%;max-height:72vh;object-fit:contain;display:block;background:#f5f8f6;border:0}.pdf-preview{height:72vh}@media(prefers-reduced-motion:reduce){.compact button{transition:none;transform:none}}
</style>
