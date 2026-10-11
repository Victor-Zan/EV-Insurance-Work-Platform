<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Button, Empty, NavBar, Skeleton, Tag } from 'vant'
import { currentUser, httpClient } from '@/shared/http/client'
import { actionFor, statusLabels, workOrderApi, type WorkOrder } from '@/features/workorders/work-orders'

interface Task {taskKey:string;caseId:string;title:string;deadline:string|null;overdue:boolean}
interface Notice {id:number;caseId:string;title:string;read:boolean;createdAt:string}
const router=useRouter(),api=workOrderApi(httpClient),loading=ref(false),error=ref(''),orders=ref<WorkOrder[]>([]),orderTotal=ref(0),tasks=ref<Task[]>([]),taskTotal=ref(0),notices=ref<Notice[]>([])
const isShop=computed(()=>currentUser.value?.roles.includes('REPAIR_SHOP')===true)
const title=computed(()=>isShop.value?'本店任务':'我的案件')
function destination(row:WorkOrder){const section=actionFor(row.status,isShop.value?'REPAIR_SHOP':'OWNER').section;return `/work-orders/${row.id}?section=${section}`}
async function load(){loading.value=true;error.value='';try{const [cases,todo,inbox]=await Promise.all([api.list(1,5),httpClient.get<{data:{records:Task[];total:number}}>('/notifications/todos',{params:{page:1,size:5}}),httpClient.get<{data:{records:Notice[]}}>('/notifications',{params:{page:1,size:3}})]);orders.value=cases.records;orderTotal.value=cases.total;tasks.value=todo.data.data.records;taskTotal.value=todo.data.data.total;notices.value=inbox.data.data.records}catch(e){error.value=e instanceof Error?e.message:'首页加载失败'}finally{loading.value=false}}
onMounted(load)
</script>

  <template><div class="mobile-page"><NavBar :title="title"/><header class="home-lead"><p class="eyebrow">{{ isShop?'SHOP OPERATIONS':'OWNER CASE VIEW' }}</p><h1>{{ currentUser?.displayName }}，{{ isShop?'先处理最需要行动的案件。':'这里是你的案件当前进度。' }}</h1><p>{{ isShop?'接单、上传、报价和维修动作都从具体案件进入。':'这里只显示与你本人有关的进度、照片和可执行动作。' }}</p></header>
  <p v-if="error" class="inline-error" role="alert">{{ error }} <Button size="small" @click="load">重试</Button></p><Skeleton v-if="loading" title :row="5" class="home-skeleton"/>
  <template v-else-if="!error"><section v-if="isShop" class="home-section"><div class="section-title"><div><p class="section-index">01 / ACTION</p><h2>待执行动作</h2></div><span>{{ taskTotal }} 项</span></div><div v-if="tasks.length"><button v-for="task in tasks" :key="task.taskKey" class="task-row" @click="router.push(`/work-orders/${task.caseId}`)"><span><strong>{{ task.title }}</strong><small>{{ task.deadline?`截止 ${new Date(task.deadline).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false})}`:'不限期' }}</small></span><Tag v-if="task.overdue" plain type="warning">已逾期</Tag></button></div><Empty v-else image-size="64" description="当前没有待执行动作"/></section>
    <section class="home-section"><div class="section-title"><div><p class="section-index">{{ isShop?'02':'01' }} / CASES</p><h2>{{ isShop?'本店当前案件':'本人案件进度' }}</h2></div><button class="text-link" @click="router.push('/work-orders')">全部 {{ orderTotal }} 件</button></div><div v-if="orders.length"><button v-for="row in orders" :key="row.id" class="case-row home-case" @click="router.push(destination(row))"><span><strong>{{ row.businessNo||'案件' }}</strong><small>{{ row.vehicleBrand }} {{ row.vehicleModel }} · {{ actionFor(row.status,isShop?'REPAIR_SHOP':'OWNER').label }}</small></span><Tag plain type="primary">{{ statusLabels[row.status] }}</Tag></button></div><Empty v-else image-size="64" description="当前没有可查看的案件"/></section>
    <section class="home-section"><div class="section-title"><div><p class="section-index">{{ isShop?'03':'02' }} / NOTICE</p><h2>最近通知</h2></div><button class="text-link" @click="router.push('/notifications')">查看全部</button></div><div v-if="notices.length"><button v-for="notice in notices" :key="notice.id" class="notice-row" @click="router.push(`/work-orders/${notice.caseId}`)"><span :class="{dot:!notice.read}"></span><strong>{{ notice.title }}</strong></button></div><p v-else class="quiet-empty">暂无新通知</p></section>
  </template></div></template>

<style scoped>
.home-lead{padding:28px 20px 24px;background:linear-gradient(180deg,#f7f8f8,#f2f4f4)}.home-lead h1{max-width:14em;margin:6px 0 10px;font-size:25px;line-height:1.32;letter-spacing:-.035em}.home-lead>p:last-child{margin:0;color:var(--color-text-secondary);font-size:14px;line-height:1.7}.home-skeleton{padding:24px 20px}
.home-section{padding:22px 16px 8px;border-top:1px solid var(--color-border);background:#fff}.section-title{display:flex;align-items:end;justify-content:space-between;gap:12px;margin-bottom:8px}.section-title h2{margin:2px 0;font-size:18px}.section-title>span,.section-index{margin:0;color:var(--color-text-secondary);font-size:11px}.section-index{color:var(--color-accent);font-weight:700;letter-spacing:.1em}.text-link{min-height:40px;padding:0;border:0;background:transparent;color:var(--color-accent)}
.task-row,.notice-row{display:flex;width:100%;min-height:64px;align-items:center;justify-content:space-between;gap:10px;padding:12px 0;border:0;border-bottom:1px solid var(--color-border);background:transparent;color:var(--color-text);text-align:left}.task-row span,.task-row strong,.task-row small{display:block}.task-row small{margin-top:5px;color:var(--color-text-secondary);font-size:12px}.home-case{width:100%;margin:0}.notice-row{justify-content:flex-start;min-height:52px}.notice-row .dot{width:6px;height:6px;flex:0 0 auto;border-radius:50%;background:var(--color-accent)}.notice-row strong{font-size:14px}.quiet-empty{padding:18px 0;color:var(--color-text-secondary);text-align:center}
</style>
