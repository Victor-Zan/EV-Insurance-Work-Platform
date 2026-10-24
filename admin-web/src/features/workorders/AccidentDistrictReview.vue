<script setup lang="ts">
import {onMounted,ref,watch} from 'vue'
import {httpClient} from '@/shared/http/client'
import {workOrderApi,type WorkOrder} from './work-orders'
const props=defineProps<{order:WorkOrder}>()
const emit=defineEmits<{changed:[];pending:[value:boolean]}>(),api=workOrderApi(httpClient)
const query=ref(''),rows=ref<{id:number;code:string;name:string}[]>([]),page=ref(0),total=ref(0),selected=ref(''),reason=ref(''),confirmed=ref(false),busy=ref(false),error=ref(''),currentName=ref('')
async function search(append=false){
 busy.value=true;error.value=''
 try{const next=append?page.value+1:1,result=await api.accidentDistricts(query.value,next);rows.value=append?[...rows.value,...result.records]:result.records;page.value=next;total.value=result.total;const current=rows.value.find(r=>r.id===props.order.accidentRegionId);if(current)currentName.value=current.name}
 catch(e){error.value=e instanceof Error?e.message:'事故区县读取失败'}finally{busy.value=false}
}
async function save(){
 if(busy.value||!selected.value||!reason.value.trim()||!confirmed.value)return
 busy.value=true;error.value='';emit('pending',true)
 try{await api.accidentDistrict(props.order.id,props.order.version,Number(selected.value),reason.value.trim());reason.value='';confirmed.value=false;selected.value='';emit('changed')}
 catch(e){error.value=e instanceof Error?e.message:'事故区县保存失败'}finally{busy.value=false;emit('pending',false)}
}
onMounted(()=>search())
watch(()=>props.order.accidentRegionId,id=>{currentName.value=rows.value.find(row=>row.id===id)?.name??''})
</script>
<template><section class="accident-review" aria-label="事故区县人工核对"><h3>事故区县</h3><p>当前：{{ currentName||(order.accidentRegionId?'已登记（区县ID '+order.accidentRegionId+'）':'缺失，请人工核实并补全') }}</p><p>用于网点服务资格，与下方车辆当前停放位置独立；不得为匹配目标网点修改事故事实。城市级服务关系不会自动扩展为区县。</p><details><summary>人工核对或更正事故区县</summary><label>查找事故区县<input v-model="query" maxlength="100" :disabled="busy" placeholder="输入区县名称或行政区划编码（非邮编）"/></label><button :disabled="busy" @click="search(false)">查询区县</button><p>可按实际事故区县名称查询；这里的编码指行政区划编码，不是邮政编码。查询后在下拉框中选择，不会自动保存。</p><p v-if="page>0&&!busy&&!error&&!rows.length" role="status">未找到匹配的已启用区县，请改用实际事故区县名称查询；邮政编码不能作为区县编码。</p><label>核实后的事故区县<select v-model="selected" :disabled="busy" @change="confirmed=false"><option value="">请选择已核实的区县</option><option v-for="row in rows" :key="row.id" :value="String(row.id)">{{ row.name }}</option></select></label><button v-if="rows.length<total" :disabled="busy" @click="search(true)">加载更多区县（{{ rows.length }}/{{ total }}）</button><label>核对或更正原因<textarea v-model="reason" maxlength="500" :disabled="busy" rows="2"/></label><label><input v-model="confirmed" type="checkbox" :disabled="busy"/>已人工核实事故事实；不是根据停车位置或目标网点推断</label><button :disabled="busy||!selected||!reason.trim()||!confirmed" @click="save">{{ busy?'处理中…':'保存事故区县并记录审计' }}</button></details><p v-if="error" role="alert">{{ error }}</p></section></template>
<style scoped>.accident-review{padding:12px 16px;margin-bottom:20px;border:1px solid #d8e2dc;border-radius:8px;background:#f7faf8}.accident-review h3{margin:0 0 8px}.accident-review p{font-size:13px;line-height:1.6}.accident-review label{display:block;margin:12px 0}.accident-review input:not([type=checkbox]),select,textarea{display:block;box-sizing:border-box;width:100%;padding:8px;border:1px solid #bacdc4;border-radius:6px}.accident-review [role=alert]{color:#a7313c}</style>
