<script setup lang="ts">
import {computed,onUnmounted,ref,watch} from 'vue'
import {showImagePreview} from 'vant'
import {httpClient} from '@/shared/http/client'
import {localTime} from '@/shared/ui/presentation'
import type {RepairEntry} from './repair'
const props=defineProps<{completion:RepairEntry;compact?:boolean}>()
const photos=ref<{id:string;version:number;url:string;error:string;loading:boolean}[]>([]),selected=ref(0)
const active=computed(()=>photos.value[selected.value])
let generation=0,disposed=false,previewInstance:ReturnType<typeof showImagePreview>|undefined
function release(){for(const photo of photos.value)if(photo.url)URL.revokeObjectURL(photo.url)}
async function loadPhoto(index:number,request=generation){
 const photo=photos.value[index];if(!photo)return
 photo.loading=true;photo.error=''
 try{const response=await httpClient.get<Blob>(`/materials/${photo.id}/download`,{responseType:'blob'});if(disposed||request!==generation)return;if(!['image/jpeg','image/png'].includes(response.data.type))throw Error('完工照片格式无法预览');if(photo.url)URL.revokeObjectURL(photo.url);photo.url=URL.createObjectURL(response.data)}catch(error){if(!disposed&&request===generation)photo.error=error instanceof Error?error.message:'照片加载失败'}finally{if(!disposed&&request===generation)photo.loading=false}
}
function preview(){if(!active.value?.url)return;const images=photos.value.filter(photo=>photo.url).map(photo=>photo.url);previewInstance=showImagePreview({images,startPosition:images.indexOf(active.value.url),closeable:true})}
watch(()=>JSON.stringify([props.completion.id,props.completion.photos.map(photo=>[photo.id,photo.version])]),()=>{generation++;release();selected.value=0;photos.value=props.completion.photos.map(photo=>({...photo,url:'',error:'',loading:true}));void Promise.all(photos.value.map((_,index)=>loadPhoto(index)))},{immediate:true})
onUnmounted(()=>{disposed=true;generation++;previewInstance?.close();release()})
</script>
<template><section class="completion-evidence" aria-label="完工照片"><header><h3>{{ compact?'完工照片':'完工证据快照' }}</h3><span v-if="!compact" class="frozen-label">已冻结</span></header><p v-if="!compact" class="evidence-meta">第 {{ completion.assignmentVersion }} 次派单 · {{ localTime(completion.createdAt) }}</p><div v-if="active" class="evidence-stage" :aria-busy="active.loading"><p v-if="active.loading" role="status">正在加载完工照片…</p><div v-else-if="active.error" role="alert"><p>{{ active.error }}</p><button type="button" @click="loadPhoto(selected)">重新加载</button></div><button v-else class="photo-preview" type="button" aria-label="放大查看完工照片" @click="preview"><img :src="active.url" alt="完工证据照片" @error="active.error='照片无法显示，请重新加载'"/></button></div><p v-else class="empty-evidence">暂无完工照片</p><div v-if="photos.length" class="evidence-caption"><span>完工照 {{ selected+1 }} / {{ photos.length }} · 点击放大</span><span v-if="!compact">版本 {{ active?.version }}</span></div><div v-if="photos.length>1" class="evidence-thumbnails" aria-label="切换完工照片"><button v-for="(photo,index) in photos" :key="photo.id" type="button" :class="{selected:index===selected}" :aria-pressed="index===selected" :aria-label="'查看第 '+(index+1)+' 张完工照片'" @click="selected=index"><img v-if="photo.url" :src="photo.url" alt=""/><span v-else>{{ photo.error?'加载失败':index+1 }}</span></button></div></section></template>
<style scoped>
.completion-evidence{margin:20px 0}.completion-evidence header{display:flex;align-items:center;justify-content:space-between;gap:12px}.completion-evidence h3{margin:0;font-size:18px}.frozen-label{padding:4px 8px;border-radius:5px;background:var(--color-success-soft);color:var(--color-success);font-size:12px}.evidence-meta,.evidence-caption{color:var(--color-text-secondary);font-size:12px;line-height:1.7}.evidence-stage{display:grid;place-items:center;height:280px;background:#f5f8f6;border:1px solid var(--color-border);border-radius:10px;overflow:hidden;text-align:center}.photo-preview{width:100%;height:100%;margin:0!important;padding:0!important;border:0!important;background:transparent!important}.photo-preview img{width:100%;height:100%;object-fit:contain}.evidence-caption{display:flex;justify-content:space-between;gap:10px;margin-top:8px}.evidence-thumbnails{display:flex;gap:8px;overflow-x:auto;padding:4px 0;margin-top:10px}.evidence-thumbnails button{width:64px;height:52px;flex:0 0 64px;padding:3px!important;margin:0!important;overflow:hidden;border:1px solid var(--color-border)!important;border-radius:6px!important;background:#f5f8f6!important}.evidence-thumbnails button.selected{border:2px solid var(--color-success)!important}.evidence-thumbnails img{width:100%;height:100%;object-fit:contain}.evidence-thumbnails span{font-size:11px}.empty-evidence{padding:40px 16px;text-align:center;color:var(--color-text-secondary)}
</style>
