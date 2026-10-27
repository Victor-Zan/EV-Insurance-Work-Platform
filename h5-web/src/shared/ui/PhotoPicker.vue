<script setup lang="ts">
import {onMounted,ref,watch} from 'vue'
import {httpClient} from '@/shared/http/client'
import {label} from './presentation'
import FileDropZone from '@/shared/upload/FileDropZone.vue'
interface Photo {id:string;category:string;name:string;state:string;version:number;contentType:string}
const props=defineProps<{caseId:string;modelValue:string[];categories?:string[];refreshToken?:number;uploadCategory?:'PROGRESS_PHOTO'|'COMPLETION_PHOTO';assignmentVersion?:number}>()
const emit=defineEmits<{'update:modelValue':[string[]];uploaded:[string]}>()
const page=ref(1),total=ref(0),items=ref<Photo[]>([]),busy=ref(false),message=ref(''),selectedFile=ref<File>(),uploadKey=ref(''),uploadInput=ref<HTMLInputElement>()
const names:Record<string,string>={ARRIVAL_PHOTO:'到店照',PROGRESS_PHOTO:'进度照',COMPLETION_PHOTO:'完工照'}
async function load(){busy.value=true;message.value='';try{const result=(await httpClient.get<{data:{records:Photo[];total:number}}>(`/materials/cases/${props.caseId}`,{params:{page:page.value,size:20}})).data.data;items.value=result.records.filter(p=>p.state==='ACTIVE'&&p.category.endsWith('_PHOTO')&&['image/png','image/jpeg'].includes(p.contentType)&&(!props.categories||props.categories.includes(p.category)));total.value=result.total}catch(e){message.value=e instanceof Error?e.message:'照片加载失败'}finally{busy.value=false}}
function toggle(id:string,checked:boolean){emit('update:modelValue',checked?[...props.modelValue.filter(x=>x!==id),id]:props.modelValue.filter(x=>x!==id))}
function choose(event:Event){const file=(event.target as HTMLInputElement).files?.[0];selectedFile.value=file;uploadKey.value=file?crypto.randomUUID():'';message.value=file?`已选择 ${file.name}，请点击上传`:''}
async function upload(){
 if(!selectedFile.value||!props.uploadCategory||!props.assignmentVersion)return
 const file=selectedFile.value
 if(file.size>10485760)throw Error('照片不能超过 10 MiB')
 if(!['image/jpeg','image/png'].includes(file.type)||!/\.(?:jpe?g|png)$/i.test(file.name))throw Error('维修照片仅支持 JPG、JPEG 或 PNG')
 const body=new FormData();body.append('file',file);body.append('category',props.uploadCategory);body.append('assignmentVersion',String(props.assignmentVersion))
 if(!uploadKey.value)uploadKey.value=crypto.randomUUID()
 const uploaded=(await httpClient.post<{data:Photo}>(`/materials/cases/${props.caseId}`,body,{headers:{'Content-Type':undefined,'Idempotency-Key':uploadKey.value}})).data.data
 selectedFile.value=undefined;uploadKey.value='';if(uploadInput.value)uploadInput.value.value='';page.value=1;await load()
 emit('update:modelValue',[...props.modelValue.filter(id=>id!==uploaded.id),uploaded.id]);emit('uploaded',uploaded.id)
 message.value=`${names[props.uploadCategory]}已上传并自动选中`
}
async function runUpload(){if(busy.value)return;busy.value=true;message.value='';try{await upload()}catch(e){message.value=e instanceof Error?e.message:'照片上传失败'}finally{busy.value=false}}
onMounted(load);watch(()=>props.refreshToken,load);watch(()=>props.caseId,()=>{page.value=1;emit('update:modelValue',[]);void load()});watch(()=>props.categories?.join(','),()=>{page.value=1;selectedFile.value=undefined;uploadKey.value='';if(uploadInput.value)uploadInput.value.value='';emit('update:modelValue',[]);void load()})
</script>
<template><fieldset class="photo-picker"><legend>选择有权查看的有效照片（已选{{ modelValue.length }}张）</legend><p role="status" aria-live="polite">{{ message }}</p><div v-if="uploadCategory&&assignmentVersion" class="photo-upload"><strong>上传{{ names[uploadCategory] }}</strong><FileDropZone hint="一次1张JPG/JPEG/PNG，≤10 MiB；上传成功后会自动选中。" :max-bytes="10485760" :feedback="message" :disabled="busy" :busy="busy"><label class="file-field">选择照片<input ref="uploadInput" type="file" accept=".jpg,.jpeg,.png" :disabled="busy" @change="choose"></label></FileDropZone><p v-if="selectedFile" class="selected-file">{{ selectedFile.name }} · {{ Math.ceil(selectedFile.size/1024) }} KiB</p><button type="button" :disabled="busy||!selectedFile" @click="runUpload">{{ busy?'上传中…':`上传${names[uploadCategory]}` }}</button></div><button type="button" :disabled="busy" @click="load">刷新照片</button><p v-if="!items.length&&!busy">暂无符合条件的{{ uploadCategory?names[uploadCategory]:'照片' }}，可直接在上方上传。</p><label v-for="photo in items" :key="photo.id"><input type="checkbox" :checked="modelValue.includes(photo.id)" :disabled="busy" @change="toggle(photo.id,($event.target as HTMLInputElement).checked)"/>{{ names[photo.category]||label(photo.category) }} · {{ photo.name }} · 版本{{ photo.version }}</label><button type="button" :disabled="busy||page<=1" @click="page--;load()">照片上一页</button><button type="button" :disabled="busy||page*20>=total" @click="page++;load()">照片下一页</button><button type="button" :disabled="!modelValue.length" @click="emit('update:modelValue',[])">清除选择</button></fieldset></template>
<style scoped>fieldset{min-width:0;border:1px solid #d0d5dd;padding:12px;margin:12px 0}.photo-upload{padding:12px;margin-bottom:12px;border-radius:8px;background:#f8fbfa}.photo-upload strong{display:block;margin-bottom:6px}.file-field input{display:block;width:100%;margin-top:8px}.selected-file{font-size:12px;color:var(--color-text-secondary);overflow-wrap:anywhere}label{display:flex;gap:8px;align-items:center;padding:10px 0;overflow-wrap:anywhere}input[type=checkbox]{width:20px!important;min-height:20px!important;flex-shrink:0}button{margin:4px}</style>
