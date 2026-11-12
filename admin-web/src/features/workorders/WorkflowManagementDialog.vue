<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElButton, ElDialog, ElInput, ElOption, ElSelect, ElMessage } from 'element-plus'
import { httpClient } from '@/shared/http/client'
import type { ApiResponse, Page } from '@/shared/http/create-client'
import { statusLabels, type WorkOrderStatus } from './work-orders'

export interface ManagedMaterial { id:string; name:string; category:string }
export interface Management {
  version:number
  responsibleName:string|null
  readOnly:boolean
  canTransfer:boolean
  canChangeShop:boolean
  removalPending:boolean
  withdrawal?:{ label:string; target:WorkOrderStatus; noRepair:boolean; materials:ManagedMaterial[] }
  shopMaterials:ManagedMaterial[]
}
const props=defineProps<{caseId:string; management?:Management}>()
const emit=defineEmits<{changed:[]}>()
const mode=ref<'withdraw'|'transfer'>('withdraw')
const open=ref(false)
const busy=ref(false)
const reason=ref('')
const recipient=ref<number>()
const version=ref<number>()
const withdrawal=ref<Management['withdrawal']>()
const recipients=ref<{id:number;name:string}[]>([])
const recipientPage=ref(0)
const recipientTotal=ref(0)
const recipientsLoading=ref(false)
const endpoint=computed(()=>'/work-orders/'+props.caseId+'/management')
const funds=computed(()=>withdrawal.value?.label==='撤回回款确认'||withdrawal.value?.label==='撤回结算确认')

async function moreRecipients(){
  recipientsLoading.value=true
  try{
    const {data}=await httpClient.get<ApiResponse<Page<{id:number;name:string}>>>(endpoint.value+'/recipients',{params:{page:recipientPage.value+1}})
    recipients.value.push(...data.data.records)
    recipientTotal.value=data.data.total
    recipientPage.value++
  }catch(error){ElMessage.error(error instanceof Error?error.message:'客服列表加载失败')}
  finally{recipientsLoading.value=false}
}
function show(action:'withdraw'|'transfer'){
  if(!props.management)return
  mode.value=action
  version.value=props.management.version
  withdrawal.value=props.management.withdrawal
  reason.value=''
  recipient.value=undefined
  open.value=true
  if(action==='transfer'){
    recipients.value=[]
    recipientPage.value=0
    recipientTotal.value=0
    void moreRecipients()
  }
}
async function submit(){
  if(busy.value||!reason.value.trim()||version.value!==props.management?.version)return
  busy.value=true
  try{
    if(mode.value==='transfer'){
      if(!recipient.value)return
      await httpClient.post(endpoint.value+'/transfer',{version:version.value,reason:reason.value.trim(),recipient:recipient.value})
      ElMessage.success('已转交工单')
    }else{
      const plan=withdrawal.value
      if(!plan)return
      const {data}=await httpClient.post<ApiResponse<{removalPending:boolean}>>(endpoint.value+'/withdraw',{
        version:version.value,reason:reason.value.trim(),noRepair:plan.noRepair,materialIds:plan.materials.map(file=>file.id),
      })
      if(data.data.removalPending)ElMessage.info('步骤已回撤，原件正在清理')
      else ElMessage.success('步骤已回撤')
    }
    open.value=false
    emit('changed')
  }catch(error){ElMessage.error(error instanceof Error?error.message:'操作失败')}
  finally{busy.value=false}
}
defineExpose({show})
</script>

<template>
  <ElDialog v-model="open" :title="mode==='transfer'?'转交客服':withdrawal?.label" width="min(640px, 94vw)"
    append-to-body :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy">
    <div v-if="mode==='transfer'" class="management-fields">
      <p>当前负责人 {{ management?.responsibleName || '未记录' }}。转交后由接手客服继续办理。</p>
      <label for="handover-recipient">接手客服</label>
      <ElSelect id="handover-recipient" v-model="recipient" filterable :loading="recipientsLoading" :disabled="busy" placeholder="请选择接手客服">
        <ElOption v-for="person in recipients" :key="person.id" :label="person.name" :value="person.id"/>
      </ElSelect>
      <ElButton v-if="recipients.length<recipientTotal" text :loading="recipientsLoading" @click="moreRecipients">加载更多客服</ElButton>
    </div>
    <div v-else-if="withdrawal" class="management-fields">
      <p><strong>返回至 {{ statusLabels[withdrawal.target] }}</strong></p>
      <div v-if="withdrawal.materials.length" class="management-warning">
        <strong>以下材料将永久删除</strong>
        <ul><li v-for="file in withdrawal.materials" :key="file.id">{{ file.name }}</li></ul>
      </div>
      <p v-else>本次不删除附件。</p>
      <p v-if="withdrawal.noRepair" class="management-warning">仅在网点尚未实际施工时使用。点击确认即表示你已核实这一事实。</p>
      <p v-if="funds">保留已有流水。请在资金页面对错录的流水执行冲销，再重新核对和确认。</p>
      <p v-else-if="withdrawal.target==='WAITING_OWNER_CONFIRMATION'">保留完工事实、完工照片和已有流水。</p>
      <p v-else>保留未列入删除范围的材料和历史记录。</p>
    </div>
    <div class="management-fields">
      <label for="management-reason">{{ mode==='transfer'?'交接说明':'回撤原因' }}</label>
      <ElInput id="management-reason" v-model="reason" type="textarea" :rows="3" maxlength="1000" :disabled="busy"/>
      <p v-if="version!==management?.version" role="alert">工单已更新，请关闭后重新核对。</p>
    </div>
    <template #footer>
      <ElButton :disabled="busy" @click="open=false">取消</ElButton>
      <ElButton :type="mode==='transfer'?'primary':'danger'" :loading="busy"
        :disabled="!reason.trim()||version!==management?.version||(mode==='transfer'&&!recipient)" @click="submit">
        {{ mode==='transfer'?'确认转交':withdrawal?.noRepair?'确认未施工并回撤':'确认回撤' }}
      </ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.management-fields{display:grid;gap:10px;margin-bottom:14px}
.management-fields p{margin:0}
.management-warning{padding:12px 14px;border-radius:8px;background:#fff0ef;color:#922d36}
.management-warning ul{max-height:168px;overflow:auto;margin:8px 0 0;padding-left:22px}
.management-warning li{overflow-wrap:anywhere}
</style>
