<script setup lang="ts">
import {computed} from 'vue'
import {records} from './presentation'
const props=defineProps<{lines:unknown[];formal?:boolean}>()
const rows=computed(()=>records(props.lines))
</script>
<template><div class="quote-table"><table><caption>{{ formal?'对外报价明细（行金额为准）':'网点原始明细' }}</caption><thead><tr><th>项目</th><th>数量</th><th>单价（元）</th><th>行金额（元）</th></tr></thead><tbody><tr v-for="(row,index) in rows" :key="index"><td data-label="项目">{{ row.description }}</td><td data-label="数量">{{ row.quantity }}</td><td data-label="单价（元）">{{ formal?row.externalUnitPrice:row.unitPrice }}</td><td data-label="行金额（元）">{{ formal?row.externalAmount:row.amount }}</td></tr></tbody></table></div></template>
<style scoped>table{width:100%;border-collapse:collapse;text-align:left}caption{text-align:left;font-weight:600;padding:10px 0}td,th{padding:10px;border-bottom:1px solid #e5e7eb;overflow-wrap:anywhere} @media(max-width:600px){thead{display:none}tr{display:block;padding:8px 0;border-bottom:1px solid #ddd}td{display:grid;grid-template-columns:100px minmax(0,1fr);border:0;padding:6px}td::before{content:attr(data-label);color:#667085}}</style>
