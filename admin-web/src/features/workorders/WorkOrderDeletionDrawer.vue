<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElButton, ElDrawer, ElInput, ElMessage } from 'element-plus'
import { httpClient } from '@/shared/http/client'
import type { ApiResponse } from '@/shared/http/create-client'
import { localTime } from '@/shared/ui/presentation'

export interface Deletion {
  version:number
  canRequest:boolean
  canCancel:boolean
  canReview:boolean
  confirmationNumber:string
  request?:{ name:string; reason:string; at:string }
  materials:number
  ledger:{direction:string;count:number;total:number}[]
}

const props=defineProps<{caseId:string; deletion?:Deletion}>()
const emit=defineEmits<{changed:[];deleted:[]}>()
const open=ref(false)
const busy=ref(false)
const reason=ref('')
const number=ref('')
const version=ref<number>()
const title=computed(()=>props.deletion?.canReview?'审批删除工单':props.deletion?.request?'删除申请':'申请删除工单')

function show(){
  if(!props.deletion)return
  version.value=props.deletion.version
  reason.value=''
  number.value=''
  open.value=true
}
async function submit(action:'request'|'cancel'|'reject'|'approve'){
  if(busy.value||version.value!==props.deletion?.version)return
  busy.value=true
  try{
    const {data}=await httpClient.post<ApiResponse<{removalPending:boolean}|null>>(
      '/work-orders/'+props.caseId+'/management/deletion'+(action==='request'?'':'/'+action),
      {version:version.value,reason:reason.value.trim(),number:number.value.trim()},
    )
    open.value=false
    if(action==='approve'){
      if(data.data?.removalPending)ElMessage.info('已批准，原件正在永久清理')
      else ElMessage.success('工单已永久删除')
      emit('deleted')
    }else{
      ElMessage.success(action==='request'?'已提交删除申请，工单暂停办理':action==='cancel'?'已撤回删除申请':'已驳回删除申请')
      emit('changed')
    }
  }catch(error){ElMessage.error(error instanceof Error?error.message:'操作失败')}
  finally{busy.value=false}
}
defineExpose({show})
</script>

<template>
  <ElDrawer v-model="open" :title="title" size="min(680px, 100vw)" append-to-body
    :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy">
    <div v-if="deletion" class="deletion-fields">
      <strong>{{ deletion.confirmationNumber }}</strong>
      <template v-if="deletion.request">
        <p>{{ deletion.request.name }} 于 {{ localTime(deletion.request.at) }} 申请删除。</p>
        <p class="request-reason">{{ deletion.request.reason }}</p>
      </template>
      <p v-if="deletion.canRequest">提交后工单暂停办理，等待管理员审批。你可以在审批前撤回申请。</p>
      <div v-if="deletion.canReview" class="deletion-warning">
        <strong>批准后永久删除工单、流水、历史及审批记录</strong>
        <p>关联材料 {{ deletion.materials }} 份。</p>
        <template v-if="deletion.ledger.length">
          <p>该工单已有收付款流水，删除不会撤销实际收付款。</p>
          <ul><li v-for="row in deletion.ledger" :key="row.direction">
            {{ row.direction==='RECEIVE'?'回款':'付款' }} {{ row.count }} 笔，录入金额合计 {{ row.total }} 元
          </li></ul>
        </template>
      </div>
      <template v-if="deletion.canReview">
        <label for="deletion-number">输入完整工单编号</label>
        <ElInput id="deletion-number" v-model="number" :disabled="busy"/>
      </template>
      <template v-if="deletion.canRequest||deletion.canReview">
        <label for="deletion-reason">{{ deletion.canReview?'驳回原因（驳回时填写）':'申请原因' }}</label>
        <ElInput id="deletion-reason" v-model="reason" type="textarea" :rows="3" maxlength="1000" :disabled="busy"/>
      </template>
      <p v-if="version!==deletion.version" role="alert">工单已更新，请关闭后重新核对。</p>
    </div>
    <template #footer>
      <ElButton :disabled="busy" @click="open=false">关闭</ElButton>
      <ElButton v-if="deletion?.canCancel" :loading="busy" :disabled="version!==deletion.version" @click="submit('cancel')">撤回申请</ElButton>
      <ElButton v-if="deletion?.canRequest" type="danger" :loading="busy"
        :disabled="!reason.trim()||version!==deletion.version" @click="submit('request')">提交删除申请</ElButton>
      <ElButton v-if="deletion?.canReview" :loading="busy" :disabled="!reason.trim()||version!==deletion.version" @click="submit('reject')">驳回申请</ElButton>
      <ElButton v-if="deletion?.canReview" type="danger" :loading="busy"
        :disabled="number.trim()!==deletion.confirmationNumber||version!==deletion.version" @click="submit('approve')">批准并永久删除</ElButton>
    </template>
  </ElDrawer>
</template>

<style scoped>
.deletion-fields{display:grid;gap:12px}
.deletion-fields p{margin:0;overflow-wrap:anywhere}
.request-reason{padding:12px;background:#f6faf9;border-radius:8px}
.deletion-warning{display:grid;gap:8px;padding:14px;border-radius:8px;background:#fff0ef;color:#922d36}
.deletion-warning ul{margin:0;padding-left:22px}
</style>
