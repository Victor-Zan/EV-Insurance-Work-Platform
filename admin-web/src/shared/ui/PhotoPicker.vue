<script setup lang="ts">
import {onMounted,ref,watch} from 'vue'
import {httpClient} from '@/shared/http/client'
import {label} from './presentation'
interface Photo {id:string;category:string;name:string;state:string;version:number;contentType:string}
const props=defineProps<{caseId:string;modelValue:string[];categories?:string[];refreshToken?:number}>()
const emit=defineEmits<{'update:modelValue':[string[]]}>()
const page=ref(1),total=ref(0),items=ref<Photo[]>([]),busy=ref(false),message=ref('')
const names:Record<string,string>={ARRIVAL_PHOTO:'到店照',PROGRESS_PHOTO:'进度照',COMPLETION_PHOTO:'完工照'}
async function load(){busy.value=true;message.value='';try{const result=(await httpClient.get<{data:{records:Photo[];total:number}}>(`/materials/cases/${props.caseId}`,{params:{page:page.value,size:20}})).data.data;items.value=result.records.filter(p=>p.state==='ACTIVE'&&p.category.endsWith('_PHOTO')&&['image/png','image/jpeg'].includes(p.contentType)&&(!props.categories||props.categories.includes(p.category)));total.value=result.total}catch(e){message.value=e instanceof Error?e.message:'照片加载失败'}finally{busy.value=false}}
function toggle(id:string,checked:boolean){emit('update:modelValue',checked?[...props.modelValue.filter(x=>x!==id),id]:props.modelValue.filter(x=>x!==id))}
onMounted(load);watch(()=>props.refreshToken,load);watch(()=>props.caseId,()=>{page.value=1;emit('update:modelValue',[]);void load()})
</script>
<template><fieldset class="photo-picker"><legend>选择有权查看的有效照片（已选{{ modelValue.length }}张）</legend><p role="status">{{ message }}</p><button type="button" :disabled="busy" @click="load">刷新照片</button><p v-if="!items.length&&!busy">本页没有符合条件的照片；可翻页查找或先在附件区上传。</p><label v-for="photo in items" :key="photo.id"><input type="checkbox" :checked="modelValue.includes(photo.id)" :disabled="busy" @change="toggle(photo.id,($event.target as HTMLInputElement).checked)"/>{{ names[photo.category]||label(photo.category) }} · {{ photo.name }} · 版本{{ photo.version }}</label><button type="button" :disabled="busy||page<=1" @click="page--;load()">照片上一页</button><button type="button" :disabled="busy||page*20>=total" @click="page++;load()">照片下一页</button><button type="button" :disabled="!modelValue.length" @click="emit('update:modelValue',[])">清除选择</button></fieldset></template>
<style scoped>fieldset{min-width:0;border:1px solid #d0d5dd;padding:12px;margin:12px 0}label{display:flex;gap:8px;align-items:center;padding:10px 0;overflow-wrap:anywhere}input[type=checkbox]{width:20px!important;min-height:20px!important;flex-shrink:0}button{margin:4px}</style>
