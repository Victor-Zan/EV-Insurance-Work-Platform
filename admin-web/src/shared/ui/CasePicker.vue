<script setup lang="ts">
import {ref} from 'vue'
import {httpClient} from '@/shared/http/client'
import {label} from './presentation'
interface CaseOption {id:string;businessNo?:string;claimNo?:string;vehicleBrand?:string;vehicleModel?:string;status:string}
defineProps<{modelValue:string}>()
const emit=defineEmits<{'update:modelValue':[string]}>()
const query=ref(''),page=ref(1),total=ref(0),rows=ref<CaseOption[]>([]),busy=ref(false),message=ref('')
async function search(reset=false){if(reset)page.value=1;busy.value=true;message.value='';try{const result=(await httpClient.get<{data:{records:CaseOption[];total:number}}>('/work-orders',{params:{page:page.value,size:20,query:query.value.trim()}})).data.data;rows.value=result.records.filter(x=>x.status!=='DRAFT');total.value=result.total}catch(e){message.value=e instanceof Error?e.message:'案件查询失败'}finally{busy.value=false}}
</script>
<template><fieldset class="case-picker"><legend>人工核对后选择案件</legend><label>案件号 / 报案号<input v-model="query" placeholder="输入案件号或报案号" @keydown.enter.prevent="search(true)"/></label><button type="button" :disabled="busy" @click="search(true)">搜索案件</button><p role="status">{{ message }}</p><p v-if="modelValue">已选择：{{ rows.find(x=>x.id===modelValue)?.businessNo||'已选案件' }}</p><label v-for="row in rows" :key="row.id"><input type="radio" name="manual-case" :checked="modelValue===row.id" :disabled="busy" @change="emit('update:modelValue',row.id)"/><span>{{ row.businessNo }} · 报案号{{ row.claimNo }} · {{ row.vehicleBrand }} {{ row.vehicleModel }} · {{ label(row.status) }}</span></label><p v-if="!busy&&total===0">尚无搜索结果，请核对后查询。</p><button type="button" :disabled="busy||page<=1" @click="page--;search()">案件上一页</button><button type="button" :disabled="busy||page*20>=total" @click="page++;search()">案件下一页</button><button type="button" :disabled="!modelValue" @click="emit('update:modelValue','')">清除案件选择</button></fieldset></template>
<style scoped>fieldset{min-width:0;border:1px solid #d0d5dd;margin:12px 0;padding:12px}label{display:flex;gap:8px;align-items:center;padding:8px 0;overflow-wrap:anywhere}input[type=radio]{width:20px!important;min-height:20px!important;flex-shrink:0}button{margin:4px}</style>
