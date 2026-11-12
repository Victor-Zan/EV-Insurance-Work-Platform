<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { ElButton, ElDatePicker, ElSelect, ElOption, ElTable, ElTableColumn, ElPagination, ElCheckbox, ElProgress } from 'element-plus'
import { currentUser, httpClient } from '@/shared/http/client'
import AnalyticsChart from './AnalyticsChart.vue'
import FundsNavigation from './FundsNavigation.vue'
import type { EChartsOption } from 'echarts'
interface Totals { completed:number; accounted:number; insurance:string; payable:string; profit:string; margin?:string }
interface Group extends Totals { id:number|null; name:string }
interface Detail { id:string; business_no:string; creator_name:string; shop_name:string; completed_at:string; insurance?:string; payable?:string; profit?:string; pending_reason?:string }
interface Page<T> { records:T[]; total:number }
interface Dashboard { summary:Totals; grain:string; trend:(Totals & {bucket:string})[]; details:Page<Detail> }
const admin=computed(()=>currentUser.value?.roles.includes('ADMIN'))
function recentCompletionDates(now = new Date()): [string, string] {
  const today = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  }).format(now)
  const firstDay = new Date(today + 'T00:00:00Z')
  firstDay.setUTCDate(firstDay.getUTCDate() - 89)
  return [firstDay.toISOString().slice(0, 10), today]
}
const dates = ref(recentCompletionDates())
const view=ref('platform'),shop=ref<number>(),creator=ref<number>(),page=ref(1),rankPage=ref(1),metric=ref('profit'),pending=ref(false),distribution=ref(false)
const data=ref<Dashboard>(),ranking=ref<Page<Group>>({records:[],total:0}),shops=ref<Group[]>([]),staff=ref<Group[]>([]),shopOptionsPage=ref(1),staffOptionsPage=ref(1),shopTotal=ref(0),staffTotal=ref(0),shopDistribution=ref<Page<Group>>({records:[],total:0}),distributionPage=ref(1)
const busy=ref(false),error=ref(''),drill=ref<string>(),dataGrain=ref('day')
let request=0
const scope=computed(()=>({from:dates.value?.[0],to:dates.value?.[1],shop:shop.value,creator:creator.value}))
const selectedName=computed(()=>view.value==='shop'?shops.value.find(x=>x.id===shop.value)?.name:staff.value.find(x=>x.id===creator.value)?.name)
const rankMaximum=computed(()=>Math.max(1,...ranking.value.records.map(x=>Number(metric.value==='profit'?x.profit:x.completed))))
const distributionMaximum=computed(()=>Math.max(1,...shopDistribution.value.records.map(x=>x.completed)))
const money=(value?:string)=>value==null?'—':new Intl.NumberFormat('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2}).format(value as unknown as number)
async function groups(params:object):Promise<Page<Group>> {return (await httpClient.get('/funds/analytics/groups',{params:{...scope.value,size:8,...params}})).data.data}
async function options(dimension:'shop'|'creator',append=false){
  const range=dates.value?.join();if(!range)return
  const result=await groups({dimension,shop:undefined,creator:undefined,size:100,page:dimension==='shop'?shopOptionsPage.value:staffOptionsPage.value})
  if(range!==dates.value?.join())return
  if(dimension==='shop'){shops.value=append?[...shops.value,...result.records]:result.records;shopTotal.value=result.total}
  else{staff.value=append?[...staff.value,...result.records]:result.records;staffTotal.value=result.total}
}
async function moreOptions(dimension:'shop'|'creator'){
  if(dimension==='shop')shopOptionsPage.value++;else staffOptionsPage.value++
  try{await options(dimension,true)}catch{error.value='筛选项加载失败，请重试'}
}
async function load(){
  const serial=++request
  if(!dates.value?.[0]||!dates.value?.[1]){busy.value=false;return}
  busy.value=true;error.value='';data.value=undefined;ranking.value={records:[],total:0};shopDistribution.value={records:[],total:0}
  try{
    const detailStart=drill.value&&drill.value<dates.value[0]?dates.value[0]:drill.value
    const monthEnd=drill.value?new Date(Date.UTC(Number(drill.value.slice(0,4)),Number(drill.value.slice(5,7)),0)).toISOString().slice(0,10):undefined
    const detailEnd=drill.value&&dataGrain.value==='month'?monthEnd:drill.value
    const detailScope=drill.value?{detailFrom:detailStart,detailTo:detailEnd!>dates.value[1]?dates.value[1]:detailEnd}:{}
    const [main,ranks,spread]=await Promise.all([
      httpClient.get('/funds/analytics',{params:{...scope.value,...detailScope,pending:pending.value,page:page.value,size:20}}),
      groups({dimension:view.value==='creator'?'creator':'shop',shop:view.value==='shop'?undefined:shop.value,creator:view.value==='creator'?undefined:creator.value,metric:metric.value,page:rankPage.value}),
      view.value==='creator'?groups({dimension:'shop',page:distributionPage.value}):Promise.resolve({records:[],total:0}),
    ])
    if(serial!==request)return
    data.value=main.data.data;dataGrain.value=main.data.data.grain;ranking.value=ranks;shopDistribution.value=spread
  }catch(cause){if(serial===request)error.value=cause instanceof Error?cause.message:'经营数据加载失败'}
  finally{if(serial===request)busy.value=false}
}
watch([dates,shop,creator,view],()=>{page.value=1;rankPage.value=1;drill.value=undefined;distributionPage.value=1;void load()},{deep:true,immediate:true})
watch(pending,()=>{page.value=1;void load()})
watch([page,rankPage,metric,drill,distributionPage],()=>{void load()})
watch(dates,()=>{shopOptionsPage.value=1;staffOptionsPage.value=1;void Promise.all([options('shop'),...(admin.value?[options('creator')]:[])]).catch(()=>{error.value='筛选项加载失败，请重试'})},{deep:true,immediate:true})
onBeforeUnmount(()=>{request++})
function reset(){shop.value=undefined;creator.value=undefined;pending.value=false;drill.value=undefined;dates.value=recentCompletionDates()}
function selectGroup(group:Group){if(group.id===null)return;if(view.value==='creator')creator.value=group.id;else{view.value='shop';shop.value=group.id}}
const trend=computed<EChartsOption>(()=>{
  const rows=data.value?.trend??[]
  return {textStyle:{fontFamily:'Microsoft YaHei, sans-serif'},color:['#7395ad','#9eb6b4'],tooltip:{trigger:'axis'},grid:[{left:60,right:20,top:28,height:'31%'},{left:60,right:20,top:'67%',height:'22%'}],
    title:[{left:0,text:'平台盈利（元）',textStyle:{fontSize:14,fontWeight:500}},{left:0,text:'完成量（单）',top:'51%',textStyle:{fontSize:14,fontWeight:500}}],
    xAxis:[{type:'category',data:rows.map(x=>x.bucket),axisLabel:{formatter:(s:string)=>s.slice(5)}},{type:'category',gridIndex:1,data:rows.map(x=>x.bucket),axisLabel:{formatter:(s:string)=>s.slice(5)}}],
    yAxis:[{type:'value',splitNumber:3},{type:'value',gridIndex:1,minInterval:1,splitNumber:3}],series:[{type:'line',data:rows.map(x=>Number(x.profit)),symbolSize:4},{type:'bar',xAxisIndex:1,yAxisIndex:1,data:rows.map(x=>x.completed),barMaxWidth:32}]}
})
const bars=computed<EChartsOption>(()=>({textStyle:{fontFamily:'Microsoft YaHei, sans-serif'},color:['#7395ad'],tooltip:{trigger:'axis'},grid:{left:125,right:70,top:20,bottom:35},xAxis:{type:'value',name:metric.value==='profit'?'元':'单'},yAxis:{type:'category',inverse:true,axisLabel:{width:110,overflow:'truncate'},data:ranking.value.records.map(x=>x.name)},series:[{type:'bar',barMaxWidth:25,data:ranking.value.records.map(x=>Number(metric.value==='profit'?x.profit:x.completed)),label:{show:true,position:'right'}}]}))
function selectBar(index:number){const group=ranking.value.records[index];if(group)selectGroup(group)}
function selectDate(index:number){
  const bucket=data.value?.trend[index]?.bucket
  if(bucket){drill.value=bucket;page.value=1}
}
</script>

<template>
  <main class="work-page analytics" :aria-busy="busy">
    <div class="breadcrumb">资金 / 经营看板</div>
    <h1>{{ admin?'经营看板':'我的经营' }}</h1>
    <FundsNavigation />
    <div v-if="admin" class="tabs"><button v-for="tab in [{key:'platform',name:'平台'},{key:'shop',name:'网点'},{key:'creator',name:'客服'}]" :key="tab.key" :class="{active:view===tab.key}" @click="view=tab.key">{{ tab.name }}</button></div>
    <div class="filters">
      <label>完成日期 <ElDatePicker v-model="dates" type="daterange" value-format="YYYY-MM-DD" :clearable="false" range-separator="至" /></label>
      <label>网点 <ElSelect v-model="shop" filterable clearable placeholder="全部网点"><ElOption v-for="item in shops.filter(x=>x.id!==null)" :key="item.id!" :value="item.id!" :label="item.name"/><template #footer><ElButton v-if="shops.length<shopTotal" text @click="moreOptions('shop')">加载更多网点</ElButton></template></ElSelect></label>
      <label v-if="admin">客服 <ElSelect v-model="creator" filterable clearable placeholder="全部客服"><ElOption v-for="item in staff" :key="item.id!" :value="item.id!" :label="item.name"/><template #footer><ElButton v-if="staff.length<staffTotal" text @click="moreOptions('creator')">加载更多客服</ElButton></template></ElSelect></label>
      <ElButton @click="reset">重置</ElButton>
    </div>
    <div v-if="error" role="alert" class="error">{{ error }} <ElButton @click="load">重试</ElButton></div>
    <p v-if="busy" role="status">正在加载经营数据…</p>
    <template v-if="data">
      <div class="metrics">
        <div><span>已完成工单</span><strong>{{ data.summary.completed }} <small>单</small></strong><span class="sub">已核算 {{ data.summary.accounted }} 单 · 待核算 {{ data.summary.completed-data.summary.accounted }} 单</span></div>
        <div><span>保险应收</span><strong>¥{{ money(data.summary.insurance) }}</strong></div>
        <div><span>网点应付</span><strong>¥{{ money(data.summary.payable) }}</strong></div>
        <div><span>平台盈利</span><strong>¥{{ money(data.summary.profit) }}</strong></div>
        <div><span>毛利率</span><strong>{{ data.summary.margin==null?'—':data.summary.margin+'%' }}</strong><span class="sub">平台盈利 ÷ 保险应收</span></div>
      </div>
      <p v-if="!data.summary.completed" class="empty" role="status">当前条件下没有已完成工单，可扩大完成日期范围或重置筛选。</p>
      <div class="charts" :class="{overview:view==='platform'}">
        <section v-if="view==='platform'" class="surface"><h2>经营趋势 <small>{{ data.grain==='month'?'按月':'按日' }}</small></h2><AnalyticsChart :option="trend" label="盈利和完成量趋势，点击日期查看明细" @select="selectDate"/></section>
        <section class="surface ranking"><header><h2>{{ view==='platform'?'网点贡献':view==='shop'?'网点排名':'客服排名' }}</h2><div class="tabs"><button :class="{active:metric==='profit'}" @click="metric='profit'">盈利</button><button :class="{active:metric==='completed'}" @click="metric='completed'">完成量</button></div></header>
          <p v-if="view!=='platform'" class="sub">当前日期及交叉筛选范围 · 点击切换对象</p>
          <AnalyticsChart v-if="view==='platform'" :option="bars" label="网点贡献排名" @select="selectBar"/>
          <div v-else class="rank-list"><button v-for="(item,index) in ranking.records" :key="String(item.id)" :class="{selected:item.id===(view==='shop'?shop:creator)}" @click="selectGroup(item)"><span>{{ (rankPage-1)*8+index+1 }}</span><span class="rank-name">{{ item.name }}<ElProgress :percentage="Math.max(0,Number(metric==='profit'?item.profit:item.completed))/rankMaximum*100" :show-text="false" :stroke-width="7" color="#7395ad"/></span><strong>{{ metric==='profit'?'¥'+money(item.profit):item.completed+' 单' }}</strong></button></div>
          <div v-if="view==='platform'" class="accessible-ranks"><button v-for="item in ranking.records" :key="String(item.id)" @click="selectGroup(item)">{{ item.name }} · {{ metric==='profit'?'¥'+money(item.profit):item.completed+' 单' }}</button></div>
          <ElPagination v-if="ranking.total>8" small layout="prev, pager, next" :total="ranking.total" :page-size="8" v-model:current-page="rankPage"/>
        </section>
        <section v-if="view!=='platform'" class="surface"><header><h2>{{ selectedName||(view==='shop'?'全部网点':'全部客服') }}</h2><div v-if="view==='creator'" class="tabs"><button :class="{active:!distribution}" @click="distribution=false">盈利趋势</button><button :class="{active:distribution}" @click="distribution=true">网点分布</button></div></header>
          <template v-if="view==='creator'&&distribution"><ElTable :data="shopDistribution.records" empty-text="暂无网点分布"><ElTableColumn prop="name" label="承修网点"/><ElTableColumn label="完成量（单）"><template #default="s"><div class="distribution-count"><ElProgress :percentage="s.row.completed/distributionMaximum*100" :show-text="false" :stroke-width="12" color="#9eb6b4"/><span>{{ s.row.completed }}</span></div></template></ElTableColumn><ElTableColumn label="平台盈利（元）"><template #default="s">{{ money(s.row.profit) }}</template></ElTableColumn></ElTable><ElPagination v-if="shopDistribution.total>8" small layout="prev, pager, next" :total="shopDistribution.total" :page-size="8" v-model:current-page="distributionPage"/></template>
          <AnalyticsChart v-else :option="trend" label="所选对象盈利和完成量趋势" @select="selectDate"/>
        </section>
      </div>
      <details class="trend-data"><summary>查看趋势数据</summary><table><thead><tr><th>日期</th><th>完成工单（单）</th><th>平台盈利（元）</th><th>操作</th></tr></thead><tbody><tr v-for="(point,index) in data.trend" :key="point.bucket"><td>{{ point.bucket }}</td><td>{{ point.completed }}</td><td>{{ money(point.profit) }}</td><td><ElButton text @click="selectDate(index)">查看工单</ElButton></td></tr></tbody></table></details>
      <section class="surface details"><header><h2>已完成工单明细 <small v-if="selectedName">{{ selectedName }}</small></h2><ElCheckbox v-model="pending">只看待核算</ElCheckbox><ElButton v-if="drill" text @click="drill=undefined">{{ drill }} × 清除日期下钻</ElButton></header>
        <ElTable :data="data.details.records" empty-text="当前条件下没有工单"><ElTableColumn prop="business_no" label="工单号" min-width="190"/><ElTableColumn prop="creator_name" label="完成负责人" min-width="100"/><ElTableColumn prop="shop_name" label="承修网点" min-width="140" show-overflow-tooltip/><ElTableColumn prop="completed_at" label="完成时间" min-width="155"/><ElTableColumn v-for="column in [{key:'insurance',name:'保险应收'},{key:'payable',name:'网点应付'},{key:'profit',name:'平台盈利'}]" :key="column.key" :label="column.name" min-width="115"><template #default="s">{{ money(s.row[column.key]) }}</template></ElTableColumn><ElTableColumn label="核算依据" min-width="140"><template #default="s">{{ s.row.pending_reason||'金额依据完整' }}</template></ElTableColumn><ElTableColumn label="操作" width="70"><template #default="s"><RouterLink :to="{path:'/work-orders/'+s.row.id,query:{section:'funds'}}">查看</RouterLink></template></ElTableColumn></ElTable>
        <ElPagination layout="total, prev, pager, next" :total="data.details.total" :page-size="20" v-model:current-page="page"/>
      </section>
    </template>
  </main>
</template>
<style scoped>
.analytics{color:#292d30}.trend-data{margin:12px 0;font-size:13px}.trend-data summary{cursor:pointer;color:#536e80}.trend-data table{width:100%;text-align:left;background:white;border-collapse:collapse}.trend-data td,.trend-data th{padding:8px 12px;border-bottom:1px solid #e1e0da}.breadcrumb{font-size:12px;margin:8px 0 10px}.analytics h1{font-size:28px;margin:0 0 18px}.tabs button{border:1px solid #d8d8d2;background:white;border-radius:7px;padding:9px 22px;color:inherit;text-decoration:none;font:inherit;cursor:pointer}.tabs button.active{background:#303638!important;color:white!important;border-color:#303638!important}.tabs{display:flex;gap:3px}.tabs button{padding:7px 20px}.filters{display:flex;gap:20px;align-items:center;flex-wrap:wrap;margin:14px 0 20px}.filters label{display:flex;gap:10px;align-items:center;font-size:14px}.filters .el-select{width:190px}.metrics{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));padding:16px 10px;background:#fff;border:1px solid #e1e0da;border-radius:10px;margin-bottom:14px}.metrics>div{padding:0 24px;border-right:1px solid #e3e3df;display:flex;flex-direction:column;gap:6px;line-height:20px}.metrics>div:last-child{border:0}.metrics strong{font-size:clamp(19px,2vw,29px);font-variant-numeric:tabular-nums;line-height:36px}.metrics small{font-size:17px}.sub{font-size:12px;color:#747774}.charts{display:grid;grid-template-columns:2fr 3fr;gap:14px;margin-bottom:14px}.charts.overview{grid-template-columns:3fr 2fr}.surface{background:white;border:1px solid #e1e0da;border-radius:10px;padding:20px;min-width:0}.surface h2{font-size:18px;margin:0 0 16px}.surface h2 small{font-size:12px;color:#737975;font-weight:400;margin-left:12px}.surface header{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:8px}.surface header h2{margin:0}.rank-list{min-height:230px}.rank-list button{width:100%;display:flex;align-items:center;gap:16px;border:0;border-bottom:1px solid #e5e6e2;background:white;color:inherit;padding:15px 10px;font:inherit;text-align:left;cursor:pointer}.rank-name{flex:1;min-width:0}.rank-name .el-progress{margin-top:9px}.distribution-count{display:flex;align-items:center;gap:10px}.distribution-count .el-progress{flex:1}.rank-list strong{margin-left:auto;font-size:14px}.rank-list .selected{background:#e5eff6}.el-pagination{margin-top:14px;justify-content:flex-end}.accessible-ranks{display:flex;flex-wrap:wrap;gap:5px}.accessible-ranks button{background:#f5f6f3;border:0;color:#4c6678;font-size:11px;padding:4px;cursor:pointer}.empty{padding:14px;text-align:center;background:white}.error{color:#9d3935;margin:15px 0}.details header{margin-bottom:18px}button:focus-visible,a:focus-visible{outline:2px solid #7395ad;outline-offset:2px}@media(max-width:1100px){.metrics>div{padding:0 12px}.charts,.charts.overview{grid-template-columns:1fr}.metrics{grid-template-columns:repeat(3,1fr);gap:24px}}@media(max-width:640px){.metrics{grid-template-columns:1fr 1fr}.filters label{flex-wrap:wrap}.surface{padding:12px}.surface header{flex-wrap:wrap}}
</style>
