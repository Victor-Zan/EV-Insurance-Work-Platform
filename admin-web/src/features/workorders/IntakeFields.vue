<script setup lang="ts">
import {computed,ref} from 'vue'
import {ElForm,ElFormItem,ElInput} from 'element-plus'
import type {DraftForm} from './work-orders'
import {intakeFields} from './intake'
import {identityPatch,plateCheck,surveyorPatch,surveyorText} from './intake-values'
const props=defineProps<{form:DraftForm;disabled?:boolean}>(),emit=defineEmits<{merge:[value:Partial<DraftForm>]}>()
const kind=ref<'AUTO'|'PLATE'|'VIN'>('AUTO')
const identity=computed(()=>props.form.vehiclePlate||props.form.vehicleVin)
const plate=computed(()=>props.form.vehiclePlate?plateCheck(props.form.vehiclePlate):undefined)
function value(field:keyof DraftForm){return field==='vehiclePlate'?identity.value:field==='surveyorName'?surveyorText(props.form):props.form[field]}
function update(field:keyof DraftForm,input:string){emit('merge',field==='vehiclePlate'?identityPatch(input,kind.value):field==='surveyorName'?surveyorPatch(input):{[field]:input})}
</script>
<template><ElForm label-position="top" :disabled="disabled"><div class="intake-fields"><ElFormItem v-for="item in intakeFields" :key="item.key" :label="item.label"><ElInput :model-value="value(item.key)" :maxlength="item.key==='surveyorName'?140:100" :placeholder="item.key==='surveyorName'?'姓名 电话':item.key==='vehiclePlate'?'车牌号或车架号，填写一种即可':''" @update:model-value="update(item.key,$event)"><template v-if="item.key==='vehiclePlate'" #append><select v-model="kind" :disabled="disabled" aria-label="车辆编号类型" @change="update('vehiclePlate',identity)"><option value="AUTO">自动识别</option><option value="PLATE">车牌</option><option value="VIN">车架号</option></select></template></ElInput><small v-if="item.key==='vehiclePlate'&&identity" :class="{'needs-review':plate?.review}" :role="plate?.acceptable===false?'alert':undefined">{{ plate?.message||'按车架号留档' }}</small><small v-if="item.key==='surveyorName'&&form.surveyorPhone&&!form.surveyorName" class="needs-review">电话已填入，请补充或确认查勘员姓名</small></ElFormItem></div></ElForm></template>
<style scoped>.intake-fields{display:grid;grid-template-columns:1fr 1fr;gap:0 20px}.intake-fields small{display:block;font-size:11px;line-height:1.6;color:#596a65;margin-top:5px}.intake-fields .needs-review{color:#8a5b13}.intake-fields select{max-width:84px;border:0;background:transparent;color:#596a65;font:inherit;font-size:12px;outline-offset:4px}@media(max-width:640px){.intake-fields{grid-template-columns:1fr}}</style>
