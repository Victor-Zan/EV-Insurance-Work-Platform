<script setup lang="ts">
import {computed,ref} from 'vue'
import {currentUser,httpClient} from '@/shared/http/client'
import InvitationResult,{type Invitation} from './InvitationResult.vue'
const props=defineProps<{caseId:string;version:number}>(),emit=defineEmits<{changed:[]}>(),reason=ref(''),existing=ref(''),busy=ref(false),error=ref(''),invitation=ref<Invitation>()
const admin=computed(()=>currentUser.value?.roles.includes('ADMIN'))
async function invite(){if(busy.value)return;if(!reason.value.trim()){error.value='须先核实本人及案件归属并填写说明';return}busy.value=true;error.value='';invitation.value=undefined;try{invitation.value=(await httpClient.post('/work-orders/'+props.caseId+'/owner-invitation',{expectedVersion:props.version,verified:true,reason:reason.value.trim(),existingOwnerId:admin.value&&existing.value?Number(existing.value):null})).data.data;emit('changed')}catch(e){error.value=e instanceof Error?e.message:'开通邀请未完成'}finally{busy.value=false}}
</script>
<template><details><summary>邀请本案车主开通账号</summary><p>仅邀请当前案件明确关联的车主；已有账号复用。若已有身份或资料冲突，停止自动处理，请管理员核实。已激活账号请直接登录。</p><p>点击生成或重发邀请，即确认已通过已核实联系方式核实本人和本案归属（非系统身份验证）。</p><label>核实说明<textarea v-model="reason" maxlength="2000"/></label><label v-if="admin">人工核实后指定已有车主账号ID（仅未绑定案件，可留空）<input v-model="existing" inputmode="numeric"></label><p role="alert">{{ error }}</p><button :disabled="busy" @click="invite">生成或重发本案车主邀请</button><InvitationResult :invitation="invitation" @close="invitation=undefined"/></details></template>
<style scoped>label{display:block;margin:12px 0}textarea{display:block;box-sizing:border-box;width:100%;min-height:72px}button{min-height:44px}details{margin:16px 0;padding:12px;border:1px solid #ddd}</style>
