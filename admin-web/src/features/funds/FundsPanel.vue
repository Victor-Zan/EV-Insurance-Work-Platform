<script setup lang="ts">
import {label,localTime} from '@/shared/ui/presentation'
import {computed,onMounted,ref,watch} from 'vue'
import {ElDialog} from 'element-plus'
import FundsAssessment from './FundsAssessment.vue'
import {chinaBusinessTime,fundsDirection,fundsConfirmationIssue,fundsAmount} from './funds-flow'
import {currentUser,httpClient} from '@/shared/http/client'
import FundsImportPanel from './FundsImportPanel.vue'
interface Direction {target:string|null;net:string;status:string}
interface Funds {stagedFlow:boolean;flowStage:string;receiveConfirmed:boolean;payConfirmed:boolean;canConfirmReceive:boolean;canConfirmPay:boolean;canRecordReceive:boolean;canRecordPay:boolean;canSetPayTarget:boolean;caseId:string;version:number;currency:string;receivable?:Direction;payable:Direction}
interface Target {id:number;direction:string;amount:string|null;reason:string;createdAt:string;actorId:number}
interface Entry {id:string;direction:string;amount:string;occurredAt:string;reversed:boolean;transactionNo?:string;note?:string}
const emit=defineEmits<{changed:[]}>()
const props=defineProps<{caseId:string;refreshToken?:number}>(),base=`/funds/cases/${props.caseId}`
const data=ref<Funds>(),entries=ref<Entry[]>([]),total=ref(0),page=ref(1),busy=ref(false),message=ref(''),amount=ref(''),number=ref(''),time=ref(chinaBusinessTime()),note=ref(''),pending=ref<{body:string;key:string}>()
const targetAmount=ref(''),targetDirty=ref(false),noteOpen=ref(false),targetInput=ref<HTMLInputElement>()
const targets=ref<Target[]>([]),targetPage=ref(1),targetTotal=ref(0),reversal=ref<Entry>(),reversalReason=ref('')
const confirmationError=ref(''),messageError=ref(false),amountInput=ref<HTMLInputElement>(),numberInput=ref<HTMLInputElement>()
const direction=computed(()=>fundsDirection(data.value))
const balance=computed(()=>direction.value==='PAY'?data.value?.payable:data.value?.receivable)
const stepName=computed(()=>direction.value==='PAY'?'平台 → 维修网点':'保险公司 → 平台')
const amountName=computed(()=>direction.value==='PAY'?'结算金额':'到账金额')
const staff=computed(()=>currentUser.value?.roles.some(role=>role==='ADMIN'||role==='CUSTOMER_SERVICE'))
async function loadTargets(){if(!staff.value)return;const result=(await httpClient.get<{data:{records:Target[];total:number}}>(`${base}/targets`,{params:{page:targetPage.value,size:20}})).data.data;targets.value=result.records;targetTotal.value=result.total}
const cs=computed(()=>currentUser.value?.roles.includes('CUSTOMER_SERVICE'))
async function load(){const previous=data.value?.flowStage;data.value=(await httpClient.get<{data:Funds}>(base)).data.data;if(previous!==data.value.flowStage){time.value=chinaBusinessTime();targetDirty.value=false;noteOpen.value=false;if(previous!==undefined){amount.value='';number.value='';note.value=''}}if(!targetDirty.value)targetAmount.value=balance.value?.target??'';const result=(await httpClient.get<{data:{records:Entry[];total:number}}>(`${base}/entries`,{params:{page:page.value,size:20}})).data.data;entries.value=result.records;total.value=result.total;await loadTargets()}
async function run(action:()=>Promise<void>){if(busy.value)return;busy.value=true;message.value='';confirmationError.value='';messageError.value=false;try{await action()}catch(error){messageError.value=true;message.value=error instanceof Error?error.message:'操作失败'}finally{busy.value=false}}
async function post<T=unknown>(path:string,body:unknown){const serialized=JSON.stringify({path,body});if(pending.value?.body!==serialized)pending.value={body:serialized,key:crypto.randomUUID()};const response=await httpClient.post<{data:T}>(path,body,{headers:{'Idempotency-Key':pending.value.key}});pending.value=undefined;confirmationError.value='';message.value='记录已保存，历史保留';await load();emit('changed');return response.data.data}
async function confirmStep(){
 if(!data.value||!direction.value)return
 const version=data.value.version,stage=data.value.flowStage,intended=direction.value;confirmationError.value=''
 try{
  await load()
  if(data.value.version!==version||data.value.flowStage!==stage||direction.value!==intended){confirmationError.value='资金记录已有更新，请核对当前金额和步骤后再次确认。';emit('changed');return}
  const issue=fundsConfirmationIssue(data.value)
  if(issue){confirmationError.value=issue.message;if(issue.field==='amount')targetInput.value?.focus();else if(issue.field==='number')amountInput.value?.focus();return}
  await post(`${base}/confirmations`,{expectedVersion:version,direction:intended})
 }catch(error){confirmationError.value=error instanceof Error?error.message:'确认失败，请重试';throw error}
}
async function target(){if(data.value&&direction.value){const value=direction.value==='PAY'&&!targetAmount.value.trim()?null:fundsAmount(targetAmount.value);await post(`${base}/targets`,{expectedVersion:data.value.version,direction:direction.value,amount:value,reason:direction.value==='PAY'?'客服在网点结算步骤确认人工应付金额':'客服在保险到账步骤确认人工应收金额'});targetDirty.value=false;targetAmount.value=balance.value?.target??value??''}}
async function entry(){if(data.value&&direction.value){if(!time.value)time.value=chinaBusinessTime();const result=await post<{funds:Funds}>(`${base}/entries`,{expectedVersion:data.value.version,direction:direction.value,transactionNo:number.value.trim(),amount:fundsAmount(amount.value,true),occurredAt:new Date(time.value+'+08:00').toISOString(),note:note.value});return result.funds}}
async function confirmEntry(){
 if(!data.value||!direction.value)return
 const version=data.value.version,stage=data.value.flowStage,intended=direction.value
 await load()
 if(data.value.version!==version||data.value.flowStage!==stage||direction.value!==intended){confirmationError.value='资金记录已有更新，请核对当前金额和步骤后再次确认。';emit('changed');return}
 if(balance.value?.target==null){confirmationError.value=`请先填写并确认${intended==='PAY'?'应付':'应收'}金额。`;targetInput.value?.focus();return}
 if(fundsAmount(targetAmount.value)!==fundsAmount(balance.value.target)){confirmationError.value='目标金额已修改，请先确认该金额。';targetInput.value?.focus();return}
 if(balance.value.status==='SETTLED'){if(data.value.stagedFlow)await confirmStep();else message.value='当前流水已结清';return}
 const actual=fundsAmount(amount.value,true)
 if(!number.value.trim()){confirmationError.value='请填写流水号 / 凭证编号';numberInput.value?.focus();return}
 if(BigInt(actual.replace('.',''))+BigInt(fundsAmount(balance.value.net).replace('.',''))>BigInt(fundsAmount(balance.value.target).replace('.',''))){confirmationError.value='本次金额加已记录金额不能超过目标金额';amountInput.value?.focus();return}
 const recorded=await entry()
 amount.value='';number.value='';note.value='';noteOpen.value=false
 if(!recorded||data.value.version!==recorded.version||data.value.flowStage!==recorded.flowStage||(data.value.stagedFlow&&direction.value!==intended)){confirmationError.value='本次流水已保存，资金记录另有更新，请核对后再次确认。';emit('changed');return}
 if(data.value.stagedFlow&&direction.value===intended&&balance.value?.status==='SETTLED')await confirmStep()
 else message.value=`本次${intended==='PAY'?'结算':'到账'}流水已保存，累计 ${(intended==='PAY'?recorded.payable:recorded.receivable)?.net??'0.00'} 元`
}
function openReversal(row:Entry){reversal.value=row;reversalReason.value='';message.value=''}
async function reverse(){if(!reversalReason.value.trim())throw Error('请填写冲销原因');if(data.value&&reversal.value){await post(`${base}/entries/${reversal.value.id}/reverse`,{expectedVersion:data.value.version,reason:reversalReason.value});reversal.value=undefined}}
async function download(){const response=await httpClient.get(`${base}/export`,{params:{page:page.value,size:20},responseType:'blob'});const url=URL.createObjectURL(response.data);const a=document.createElement('a');a.href=url;a.download=`ledger-${props.caseId}-${page.value}.csv`;a.click();URL.revokeObjectURL(url)}
onMounted(()=>run(load));watch(()=>props.refreshToken,()=>run(load))
</script>
<template>
 <section class="funds">
  <header class="funds-heading"><h2>{{ data?.stagedFlow?'资金流动':'资金记录' }}</h2><button :disabled="busy" @click="run(load)">刷新</button></header>
  <p v-if="message&&!(cs&&direction)" role="status">{{ message }}</p>
  <template v-if="data">
   <ol v-if="data.stagedFlow" class="fund-steps" aria-label="资金办理顺序">
    <li :class="{done:data.receiveConfirmed,current:data.flowStage==='WAITING_INSURER_PAYMENT'}"><span>01</span><div><h3>保险公司 → 平台</h3><p>{{ data.receiveConfirmed?'已确认到账':'确认实际到账' }}</p></div></li>
    <li :class="{done:data.payConfirmed,current:data.flowStage==='WAITING_SHOP_SETTLEMENT'}"><span>02</span><div><h3>平台 → 维修网点</h3><p>{{ data.payConfirmed?'已确认结算':data.receiveConfirmed?'确认网点结算':'等待到账确认' }}</p></div></li>
   </ol>
   <p v-if="data.stagedFlow&&data.flowStage==='COMPLETED'" class="completion-note" role="status">两步资金已确认，案件已完成。</p>
   <div class="funds-workspace" :class="{staff}">
    <FundsAssessment v-if="staff" :case-id="caseId" :refresh-token="refreshToken"/>
    <section class="current-transfer" data-reading="当前资金步骤">
     <template v-if="cs&&direction">
      <h3>{{ stepName }}</h3>
      <div class="balance-line"><span>{{ direction==='RECEIVE'?'已确认应收':'已确认应付' }} <strong>{{ balance?.target??'未定' }}</strong> 元</span><span>{{ direction==='RECEIVE'?'累计到账':'累计结算' }} <strong>{{ balance?.net??'0.00' }}</strong> 元</span></div>
      <label>流水号 / 凭证编号<input ref="numberInput" v-model="number" maxlength="128" :disabled="busy||balance?.status==='SETTLED'"/></label>
      <label>{{ direction==='RECEIVE'?'到账时间':'结算时间' }}（中国时间）<input v-model="time" type="datetime-local" :disabled="busy||balance?.status==='SETTLED'"/></label>
      <div class="amount-row"><label>{{ direction==='RECEIVE'?'应收金额':'应付金额' }}（元）<input ref="targetInput" v-model="targetAmount" inputmode="decimal" :disabled="busy" @input="targetDirty=true"/></label><button class="target-action" :disabled="busy||(direction==='PAY'&&!data.canSetPayTarget)" @click="run(target)">{{ direction==='RECEIVE'?'确认应收金额':'确认应付金额' }}</button></div>
      <div class="amount-row"><label>本次{{ amountName }}（元）<input ref="amountInput" v-model="amount" inputmode="decimal" :disabled="busy||balance?.status==='SETTLED'"/></label><button class="primary" :disabled="busy" @click="run(confirmEntry)">{{ direction==='RECEIVE'?'确认到账':'确认结算' }}</button></div>
      <p v-if="confirmationError||message" class="confirmation-error" :class="{success:!confirmationError&&!messageError}" :role="confirmationError||messageError?'alert':'status'">{{ confirmationError||message }}</p>
      <details class="optional-note" :open="noteOpen" @toggle="noteOpen=($event.target as HTMLDetailsElement).open"><summary>备注（选填）</summary><textarea v-model="note" aria-label="备注（选填）" maxlength="2000" rows="2" :disabled="busy||balance?.status==='SETTLED'"/></details>
     </template>
     <template v-else><h3>资金记录</h3><p v-if="data.receivable">应收 {{ data.receivable.target??'未定' }} 元 · 已到账 {{ data.receivable.net }} 元</p><p>应付 {{ data.payable.target??'未定' }} 元 · 已结算 {{ data.payable.net }} 元</p></template>
    </section>
   </div>
  </template>
  <details><summary>资金记录与修订历史</summary>
   <section v-if="staff"><h3>应收 / 应付金额版本（{{ targetTotal }} 条）</h3><article v-for="row in targets" :key="row.id"><p>#{{ row.id }} · {{ label(row.direction) }} · {{ row.amount??'未定' }}元 · {{ localTime(row.createdAt) }} · 操作者 {{ row.actorId }}</p><p>{{ row.reason }}</p></article><button :disabled="busy||targetPage<=1" @click="targetPage--;run(loadTargets)">上一页金额版本</button><span>第 {{ targetPage }} 页</span><button :disabled="busy||targetPage*20>=targetTotal" @click="targetPage++;run(loadTargets)">下一页金额版本</button></section>
   <h3>分笔历史（{{ total }} 条）</h3><article v-for="row in entries" :key="row.id"><p>{{ label(row.direction) }} {{ row.amount }}元 · {{ localTime(row.occurredAt) }} · {{ row.reversed?'已冲销':'有效' }}</p><p v-if="row.transactionNo">凭证编号：{{ row.transactionNo }}</p><p v-if="row.note">{{ row.note }}</p><button v-if="cs&&!row.reversed" :disabled="busy" @click="openReversal(row)">整笔冲销</button></article><button :disabled="busy||page<=1" @click="page--;run(load)">上一页</button><button :disabled="busy||page*20>=total" @click="page++;run(load)">下一页</button><button :disabled="busy" @click="run(download)">导出当前页</button>
  </details>
  <ElDialog :model-value="Boolean(reversal)" title="冲销记录" width="min(520px,94vw)" @update:model-value="!$event&&(reversal=undefined)"><p>{{ reversal?.amount }} 元 · {{ localTime(reversal?.occurredAt??'') }}</p><label>冲销原因<textarea v-model="reversalReason" maxlength="2000" rows="3"/></label><p v-if="message" role="alert">{{ message }}</p><template #footer><button :disabled="busy" @click="reversal=undefined">取消</button><button :disabled="busy" @click="run(reverse)">确认整笔冲销</button></template></ElDialog>
  <FundsImportPanel v-if="cs&&!data?.stagedFlow" @changed="run(async()=>{await load();emit('changed')})"/>
 </section>
</template>
<style scoped>.funds{color:#20383c}.funds-heading{display:flex;align-items:center;justify-content:space-between;gap:16px}.funds-heading h2{margin:0}.funds-workspace.staff{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:24px;align-items:start}.current-transfer{min-width:0;border-left:1px solid #dce5e4;padding-left:24px}.current-transfer h3{font-size:16px;margin:0 0 12px}.balance-line{display:grid;gap:8px;padding:16px 0;border-block:1px solid #dce5e4;margin:16px 0;font-size:13px;color:#536b70}.balance-line strong{font-variant-numeric:tabular-nums;color:#20383c}.amount-row{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:12px;align-items:end;margin:16px 0}.amount-row label{margin:0!important}.amount-row button{margin:0;min-height:42px;white-space:nowrap;font-size:12px;padding:10px 12px}.amount-row input{width:100%}.amount-row .primary{background:#40675c;color:#fff;border-color:#40675c}.optional-note{margin-top:18px}.optional-note summary{color:#536b70;font-size:13px}.optional-note textarea{margin-top:12px}.confirmation-error.success{color:#43675d}.current-transfer label{display:grid;gap:8px;font-size:13px}.current-transfer input,.current-transfer textarea{border:1px solid #b5c8c5;border-radius:6px;padding:10px;font:inherit;color:#20383c;min-width:0}.funds-workspace:not(.staff) .current-transfer{border:0;padding-left:0}@media(max-width:1100px){.funds-workspace.staff{grid-template-columns:1fr}.current-transfer{border-left:0;border-top:1px solid #dce5e4;padding:20px 0 0}}.fund-steps{display:grid;grid-template-columns:1fr 1fr;gap:20px;padding:0;list-style:none;margin:24px 0}.fund-steps li{display:flex;gap:14px;padding:16px 0;border-top:3px solid #dce5e4;color:#637579}.fund-steps li>span{font-size:20px;font-variant-numeric:tabular-nums}.fund-steps h3{font-size:15px;margin:0 0 8px}.fund-steps p{font-size:12px;margin:0}.fund-steps .current{border-color:#32b77d;color:#176346}.fund-steps .done{border-color:#638d80;color:#236b4b}.confirmation-error{margin:10px 0;color:#a7313c;font-size:12px;line-height:1.6}.completion-note{color:#236b4b}details{margin-top:28px}summary{cursor:pointer;font-size:14px}@media(max-width:600px){.fund-steps{grid-template-columns:1fr}}label{display:block;margin:10px 0}input,textarea,select{width:100%;box-sizing:border-box}button{margin:6px}</style>
