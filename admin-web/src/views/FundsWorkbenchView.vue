<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ElButton, ElInput, ElOption, ElSelect, ElTable, ElTableColumn } from 'element-plus'
import { httpClient } from '@/shared/http/client'
import { label } from '@/shared/ui/presentation'
import { statusLabels } from '@/features/workorders/work-orders'
import type { ApiResponse, Page } from '@/shared/http/create-client'
interface Balance { target:string|null;net:string;status:string }
interface Row { caseId:string;businessNo:string;claimNo:string;status:string;receivable:Balance;payable:Balance }
const rows=ref<Row[]>([]),total=ref(0),page=ref(1),query=ref(''),status=ref(''),receive=ref(''),pay=ref(''),busy=ref(false),error=ref('')
const fundsLabels={UNSET:'未定',UNPAID:'未到账/未付',PARTIAL:'部分到账/部分支付',SETTLED:'结清'}
async function load(){busy.value=true;error.value='';try{const result=(await httpClient.get<ApiResponse<Page<Row>>>('/funds/workbench',{params:{page:page.value,size:20,query:query.value.trim()||undefined,status:status.value||undefined,receivableStatus:receive.value||undefined,payableStatus:pay.value||undefined}})).data.data;rows.value=result.records;total.value=result.total}catch(cause){error.value=cause instanceof Error?cause.message:'资金查询失败'}finally{busy.value=false}}
function filter(){page.value=1;void load()}
onMounted(load)
</script>
<template><main class="work-page"><h1>跨案件资金查询</h1><p>币种 CNY，单位元；维修状态和资金状态分别筛选，金额仅人工记账。</p><form class="filters" @submit.prevent="filter"><ElInput v-model="query" clearable placeholder="工单号 / 报案号"/><ElSelect v-model="status" clearable placeholder="维修状态"><ElOption v-for="(name,key) in statusLabels" :key="key" :value="key" :label="name"/></ElSelect><ElSelect v-model="receive" clearable placeholder="应收状态"><ElOption v-for="(name,key) in fundsLabels" :key="key" :value="key" :label="name"/></ElSelect><ElSelect v-model="pay" clearable placeholder="应付状态"><ElOption v-for="(name,key) in fundsLabels" :key="key" :value="key" :label="name"/></ElSelect><ElButton native-type="submit" :loading="busy">筛选</ElButton></form><p v-if="error" role="alert">{{ error }} <ElButton @click="load">重试</ElButton></p><ElTable :data="rows" empty-text="当前条件下没有案件"><ElTableColumn prop="businessNo" label="工单号" min-width="190"/><ElTableColumn prop="claimNo" label="报案号" min-width="140"/><ElTableColumn label="维修状态" min-width="130"><template #default="scope">{{ label(scope.row.status) }}</template></ElTableColumn><ElTableColumn label="应收 / 净实收 / 状态" min-width="210"><template #default="scope">{{ scope.row.receivable.target??'未定' }} / {{ scope.row.receivable.net }} / {{ label(scope.row.receivable.status) }}</template></ElTableColumn><ElTableColumn label="应付 / 净实付 / 状态" min-width="210"><template #default="scope">{{ scope.row.payable.target??'未定' }} / {{ scope.row.payable.net }} / {{ label(scope.row.payable.status) }}</template></ElTableColumn><ElTableColumn label="操作"><template #default="scope"><RouterLink :to="{path:`/work-orders/${scope.row.caseId}`,query:{section:'funds'}}">资金详情</RouterLink></template></ElTableColumn></ElTable><div class="pager"><ElButton :disabled="busy||page<=1" @click="page--;load()">上一页</ElButton><span>第 {{ page }} 页 / 共 {{ total }} 条</span><ElButton :disabled="busy||page*20>=total" @click="page++;load()">下一页</ElButton></div></main></template>
<style scoped>.filters{display:flex;gap:12px;flex-wrap:wrap;margin:20px 0}.filters .el-input{width:240px}.filters .el-select{width:180px}.pager{display:flex;gap:16px;align-items:center;justify-content:flex-end;margin-top:20px}</style>
