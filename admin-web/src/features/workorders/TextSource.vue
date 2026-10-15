<script setup lang="ts">
import {onMounted,ref} from 'vue'
import {httpClient} from '@/shared/http/client'
import {draftLabels} from '../materials/ocr-form'
const props=defineProps<{caseId:string}>(),source=ref<{text:string;adopted:Record<string,string>}|null>(),error=ref('')
async function load(){try{source.value=(await httpClient.get<{data:typeof source.value}>('/work-orders/'+props.caseId+'/text-source')).data.data}catch(e){error.value=e instanceof Error?e.message:'文字来源暂不可用'}}
onMounted(()=>void load())
</script>
<template><details v-if="source||error" class="text-source"><summary>建单文字来源</summary><p v-if="error" role="alert">{{ error }} <button @click="load">重试</button></p><template v-if="source"><pre>{{ source.text }}</pre><h3>当次采用信息</h3><dl><template v-for="(name,key) in draftLabels" :key="key"><dt>{{ name }}</dt><dd>{{ source.adopted[key]||'未填写' }}</dd></template></dl></template></details></template>
<style scoped>.text-source{margin:18px 0;color:#536b70;font-size:13px}.text-source pre{white-space:pre-wrap;overflow-wrap:anywhere}.text-source dl{display:grid;grid-template-columns:140px 1fr;gap:10px}.text-source dd{margin:0;overflow-wrap:anywhere}@media(max-width:640px){.text-source dl{grid-template-columns:1fr}}</style>
