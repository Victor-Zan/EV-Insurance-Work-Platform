<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElButton, ElTable, ElTableColumn, vLoading } from 'element-plus'
import { httpClient } from '@/shared/http/client'
import { localTime } from '@/shared/ui/presentation'
import type { ApiResponse, Page } from '@/shared/http/create-client'
interface Audit {id:number;actorId:number;actorRoles:string;action:string;objectType:string;objectId:string;summary:string;traceId:string;occurredAt:string}
const rows=ref<Audit[]>([]),page=ref(1),total=ref(0),loading=ref(false),error=ref('')
async function load(){loading.value=true;error.value='';try{const response=await httpClient.get<ApiResponse<Page<Audit>>>('/admin/audit-logs',{params:{page:page.value,size:20}});rows.value=response.data.data.records;total.value=response.data.data.total}catch(cause){error.value=cause instanceof Error?cause.message:'读取失败'}finally{loading.value=false}}
onMounted(()=>{void load()})
</script>
<template><div class="work-page"><header class="module-page-head"><p>ADMIN / AUDIT</p><h1>审计日志</h1><span>只读、只追加；按服务端分页查看关键操作。</span></header><section class="admin-surface"><p v-if="error" role="alert" class="error-message">{{ error }} <ElButton @click="load">重试</ElButton></p><ElTable v-loading="loading" :data="rows" empty-text="暂无审计记录"><ElTableColumn label="时间" min-width="170"><template #default="scope">{{ localTime(scope.row.occurredAt) }}</template></ElTableColumn><ElTableColumn prop="actorId" label="操作者 ID" width="110"/><ElTableColumn prop="actorRoles" label="角色" min-width="110"/><ElTableColumn prop="action" label="动作" min-width="150"/><ElTableColumn prop="objectType" label="对象" min-width="130"/><ElTableColumn prop="objectId" label="对象 ID" min-width="140"/><ElTableColumn prop="summary" label="变更摘要" min-width="240"/><ElTableColumn prop="traceId" label="追踪标识" min-width="170"/></ElTable><div class="admin-pager"><ElButton :disabled="loading||page<=1" @click="page--;load()">上一页</ElButton><span>第 {{ page }} 页 · 共 {{ total }} 条</span><ElButton :disabled="loading||page*20>=total" @click="page++;load()">下一页</ElButton></div></section></div></template>
