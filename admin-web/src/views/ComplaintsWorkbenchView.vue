<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ElButton, ElInput, ElOption, ElSelect, ElTable, ElTableColumn } from 'element-plus'
import { httpClient } from '@/shared/http/client'
import { label,localTime } from '@/shared/ui/presentation'
import type { ApiResponse, Page } from '@/shared/http/create-client'
interface Row {id:string;caseId:string;businessNo:string;claimNo:string;status:string;description:string;createdAt:string}
const rows=ref<Row[]>([]),page=ref(1),total=ref(0),query=ref(''),status=ref(''),busy=ref(false),error=ref('')
const statuses={SUBMITTED:'已提交',PROCESSING:'处理中',RESOLVED:'已解决',CLOSED:'已关闭'}
async function load(){busy.value=true;error.value='';try{const result=(await httpClient.get<ApiResponse<Page<Row>>>('/complaints',{params:{page:page.value,size:20,query:query.value.trim()||undefined,status:status.value||undefined}})).data.data;rows.value=result.records;total.value=result.total}catch(cause){error.value=cause instanceof Error?cause.message:'投诉查询失败'}finally{busy.value=false}}
function filter(){page.value=1;void load()}
onMounted(load)
</script>
<template><main class="work-page"><h1>跨案件投诉跟踪</h1><p>管理员处理投诉，客服只读跟踪及追加纠正说明；投诉不改变维修状态或网点评分。</p><form class="filters" @submit.prevent="filter"><ElInput v-model="query" clearable placeholder="工单号 / 报案号"/><ElSelect v-model="status" clearable placeholder="全部处理状态"><ElOption v-for="(name,key) in statuses" :key="key" :label="name" :value="key"/></ElSelect><ElButton native-type="submit" :loading="busy">筛选</ElButton></form><p v-if="error" role="alert">{{ error }} <ElButton @click="load">重试</ElButton></p><ElTable :data="rows" empty-text="当前条件下没有投诉"><ElTableColumn prop="businessNo" label="工单号" min-width="190"/><ElTableColumn prop="claimNo" label="报案号" min-width="130"/><ElTableColumn label="状态"><template #default="scope">{{ label(scope.row.status) }}</template></ElTableColumn><ElTableColumn prop="description" label="车主描述" min-width="220" show-overflow-tooltip/><ElTableColumn label="提交时间" min-width="180"><template #default="scope">{{ localTime(scope.row.createdAt) }}</template></ElTableColumn><ElTableColumn label="操作"><template #default="scope"><RouterLink :to="{path:`/work-orders/${scope.row.caseId}`,query:{section:'complaint'}}">投诉详情与处理</RouterLink></template></ElTableColumn></ElTable><div class="pager"><ElButton :disabled="busy||page<=1" @click="page--;load()">上一页</ElButton><span>第 {{ page }} 页 / 共 {{ total }} 条</span><ElButton :disabled="busy||page*20>=total" @click="page++;load()">下一页</ElButton></div></main></template>
<style scoped>.filters{display:flex;gap:12px;flex-wrap:wrap;margin:20px 0}.filters .el-input{width:240px}.filters .el-select{width:180px}.pager{display:flex;gap:16px;align-items:center;justify-content:flex-end;margin-top:20px}</style>
