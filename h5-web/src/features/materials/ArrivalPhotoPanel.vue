<script setup lang="ts">
import {onUnmounted,ref} from 'vue'
import {httpClient} from '@/shared/http/client'

const props=defineProps<{caseId:string;assignmentVersion:number;uploaded:boolean}>()
const emit=defineEmits<{changed:[];next:[]}>()
const selected=ref<File>(),preview=ref(''),busy=ref(false),message=ref(''),uploadKey=ref('')
function releasePreview(){if(preview.value)URL.revokeObjectURL(preview.value);preview.value=''}
function choose(event:Event){
  const input=event.target as HTMLInputElement,file=input.files?.[0]
  if(!file)return
  if(!/\.(jpe?g|png)$/i.test(file.name)||!['image/jpeg','image/png'].includes(file.type)){message.value='请选择 JPG 或 PNG 到店照片';input.value='';return}
  if(file.size===0||file.size>10485760){message.value='照片须大于 0 字节且不超过 10 MiB';input.value='';return}
  releasePreview();selected.value=file;preview.value=URL.createObjectURL(file);uploadKey.value=crypto.randomUUID();message.value='已选择照片，请点击上传'
}
async function upload(){
  if(!selected.value||busy.value)return
  busy.value=true;message.value=''
  try{
    const body=new FormData();body.append('file',selected.value);body.append('category','ARRIVAL_PHOTO');body.append('assignmentVersion',String(props.assignmentVersion))
    await httpClient.post(`/materials/cases/${props.caseId}`,body,{headers:{'Content-Type':undefined,'Idempotency-Key':uploadKey.value}})
    selected.value=undefined;releasePreview();message.value='到店照片已上传';emit('changed')
  }catch(error){message.value=error instanceof Error?error.message:'上传失败，请重试'}finally{busy.value=false}
}
onUnmounted(releasePreview)
</script>

<template>
  <section class="arrival-photos" aria-label="上传到店照片">
    <p class="step-label">第 1 步 · 到店记录</p><h3>上传到店照片</h3>
    <p>请拍摄车辆到店情况，至少上传 1 张 JPG/PNG 照片，每张不超过 10 MiB。</p>
    <p v-if="uploaded" class="uploaded" role="status">本次派单已有有效到店照片，可继续报价，也可补充上传。</p>
    <FileDropZone accept=".jpg,.jpeg,.png" hint="选择或拖入 JPG/PNG 照片，手机可拍照上传" :max-bytes="10485760" :disabled="busy" :busy="busy" :feedback="message">
      <label>选择到店照片<input type="file" accept="image/jpeg,image/png" :disabled="busy" @change="choose"></label>
    </FileDropZone>
    <figure v-if="selected"><img :src="preview" alt="待上传的车辆到店照片"><figcaption>{{ selected.name }}</figcaption></figure>
    <p v-if="message" role="status" aria-live="polite">{{ message }}</p>
    <button type="button" :disabled="busy||!selected" @click="upload">{{ busy?'上传中…':'上传到店照片' }}</button>
    <button type="button" :disabled="busy||!uploaded||Boolean(selected)" @click="emit('next')">下一步：提交原始报价</button>
    <p v-if="selected&&uploaded">请先上传当前选择的照片，再进入下一步。</p>
  </section>
</template>

<style scoped>
.arrival-photos{margin:18px 0;padding:18px 14px;border:1px solid var(--color-border);border-radius:12px;background:#fff}.step-label{font-size:12px;color:var(--color-text-secondary)}h3{margin:8px 0 12px;font-size:20px}p{line-height:1.65}.uploaded{color:var(--color-success)}input{display:block;max-width:100%;margin-top:10px}figure{margin:16px 0}img{display:block;max-width:100%;max-height:280px;border-radius:8px;object-fit:contain}figcaption{margin-top:8px;font-size:12px;overflow-wrap:anywhere}button{display:block;width:100%;min-height:46px}button:last-of-type{background:var(--color-primary,var(--color-text));color:#fff}button:disabled{opacity:.5}
</style>
