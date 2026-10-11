<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElButton, ElInputNumber, ElMessage } from 'element-plus'
import { httpClient } from '@/shared/http/client'
import { workOrderApi } from '@/features/workorders/work-orders'
const api=workOrderApi(httpClient),days=ref(1),reason=ref(''),busy=ref(false),error=ref('')
async function load(){busy.value=true;error.value='';try{days.value=(await api.configuration()).possibleDuplicateDays}catch(cause){error.value=cause instanceof Error?cause.message:'读取失败'}finally{busy.value=false}}
async function save(){if(!reason.value.trim()){error.value='请填写修改原因';return}busy.value=true;error.value='';try{await api.updateConfiguration(days.value,reason.value.trim());reason.value='';ElMessage.success('重复判断时间窗口已更新并审计');await load()}catch(cause){error.value=cause instanceof Error?cause.message:'保存失败'}finally{busy.value=false}}
onMounted(()=>{void load()})
</script>
<template><div class="work-page"><header class="module-page-head"><p>ADMIN / RULES</p><h1>重复判断配置</h1><span>仅调整已有疑似重复天数，不改变案件或派单规则。</span></header><section class="admin-surface"><p v-if="error" role="alert" class="error-message">{{ error }}</p><label>相近/短期天数（1—365）<ElInputNumber v-model="days" :min="1" :max="365"/></label><label>修改原因（必填）<textarea v-model="reason" maxlength="2000"/></label><ElButton type="primary" :loading="busy" @click="save">保存并审计</ElButton></section></div></template>
