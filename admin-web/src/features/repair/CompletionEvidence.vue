<script setup lang="ts">
import {computed,onUnmounted,ref,watch} from 'vue'
import {ElImage} from 'element-plus'
import {httpClient} from '@/shared/http/client'
import {localTime} from '@/shared/ui/presentation'
import type {RepairEntry} from './repair'

const props=defineProps<{completion:RepairEntry;shopName?:string}>()
const photos=ref<{id:string;version:number;url:string;error:string;loading:boolean}[]>([]),selected=ref(0)
const active=computed(()=>photos.value[selected.value])
const previewList=computed(()=>active.value?.url?[active.value.url,...photos.value.filter(p=>p.id!==active.value?.id&&p.url).map(p=>p.url)]:[])
const signature=computed(()=>JSON.stringify([props.completion.id,props.completion.photos.map(p=>[p.id,p.version])]))
let generation=0,disposed=false
function release(){for(const photo of photos.value)if(photo.url)URL.revokeObjectURL(photo.url)}
async function loadPhoto(index:number,request=generation){
  const photo=photos.value[index];if(!photo)return
  photo.loading=true;photo.error=''
  try{
    const response=await httpClient.get<Blob>(`/materials/${photo.id}/download`,{responseType:'blob'})
    if(disposed||request!==generation)return
    if(!['image/jpeg','image/png'].includes(response.data.type))throw Error('完工照片格式无法预览')
    if(photo.url)URL.revokeObjectURL(photo.url)
    photo.url=URL.createObjectURL(response.data)
  }catch(error){if(!disposed&&request===generation)photo.error=error instanceof Error?error.message:'照片加载失败'}
  finally{if(!disposed&&request===generation)photo.loading=false}
}
watch(signature,()=>{
  generation++;release();selected.value=0
  photos.value=props.completion.photos.map(p=>({id:p.id,version:p.version,url:'',error:'',loading:true}))
  void Promise.all(photos.value.map((_,index)=>loadPhoto(index)))
},{immediate:true})
onUnmounted(()=>{disposed=true;generation++;release()})
</script>

<template>
  <section class="completion-evidence" aria-label="完工证据快照">
    <header class="evidence-heading"><h3>完工证据快照</h3><span class="frozen-label">已冻结</span></header>
    <div class="evidence-meta"><span v-if="shopName">{{ shopName }}</span><span v-else>第 {{ completion.assignmentVersion }} 次派单</span><time>{{ localTime(completion.createdAt) }}</time></div>
    <div v-if="active" class="evidence-stage" :aria-busy="active.loading">
      <div v-if="active.loading" class="photo-skeleton" role="status" aria-label="正在加载完工照片"/>
      <div v-else-if="active.error" class="photo-error" role="alert"><p>{{ active.error }}</p><button type="button" @click="loadPhoto(selected)">重新加载</button></div>
      <ElImage v-else :key="active.url" :src="active.url" :preview-src-list="previewList" fit="contain" :preview-teleported="true" :hide-on-click-modal="true" alt="完工证据照片"><template #error><p class="photo-error" role="alert">照片无法显示<button type="button" @click="loadPhoto(selected)">重新加载</button></p></template></ElImage>
    </div>
    <p v-else class="empty-evidence" role="status">暂无完工照片</p>
    <div v-if="photos.length" class="evidence-caption"><span>完工照 {{ selected+1 }} / {{ photos.length }}</span><span>版本 {{ active?.version }}</span></div>
    <div v-if="photos.length>1" class="evidence-thumbnails" aria-label="切换完工照片">
      <button v-for="(photo,index) in photos" :key="photo.id" type="button" :class="{selected:index===selected}" :aria-pressed="index===selected" :aria-label="'查看第 '+(index+1)+' 张完工照片'" @click="selected=index"><img v-if="photo.url" :src="photo.url" alt=""/><span v-else>{{ photo.error?'加载失败':index+1 }}</span></button>
    </div>
  </section>
</template>

<style scoped>
.evidence-heading{display:flex;align-items:center;justify-content:space-between;gap:16px}.evidence-heading h3{font-size:18px;margin:0;font-weight:650}.frozen-label{font-size:12px;color:#43675d;background:#edf4f0;border-radius:5px;padding:4px 8px}.evidence-meta{display:flex;flex-wrap:wrap;gap:6px 20px;color:#617570;font-size:12px;line-height:1.6;margin:12px 0 18px}.evidence-stage{height:clamp(280px,40vh,440px);display:grid;place-items:center;background:#f5f8f6;border:1px solid #dce5e1;border-radius:10px;overflow:hidden}.evidence-stage :deep(.el-image){width:100%;height:100%;cursor:zoom-in}.photo-skeleton{width:100%;height:100%;background:#e7eeeb}.photo-error{padding:24px;text-align:center;color:#8d3e3e;font-size:13px}.photo-error button{margin-top:8px}.empty-evidence{padding:60px 20px;text-align:center;color:#617570;background:#f5f8f6;border-radius:10px}.evidence-caption{display:flex;justify-content:space-between;gap:12px;font-size:12px;color:#617570;margin-top:10px}.evidence-thumbnails{display:flex;flex-wrap:wrap;gap:10px;margin-top:16px}.evidence-thumbnails button{width:72px;height:56px;padding:3px!important;border:1px solid #dce5e1!important;border-radius:6px!important;background:#f5f8f6!important;overflow:hidden;margin:0!important}.evidence-thumbnails button.selected{border:2px solid #40675c!important}.evidence-thumbnails img{width:100%;height:100%;object-fit:contain}.evidence-thumbnails span{font-size:11px;color:#617570}
</style>
