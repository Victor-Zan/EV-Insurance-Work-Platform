<script setup lang="ts">
import {computed} from 'vue'
import {records,fieldLabel,displayValue} from './presentation'
const props=defineProps<{value:unknown}>()
const rows=computed(()=>records(props.value))
</script>
<template><div class="business-records"><p v-if="!rows.length" class="empty-record">暂无记录</p><article v-for="(row,index) in rows" :key="index"><dl><template v-for="(value,key) in row" :key="key"><dt>{{ fieldLabel(String(key)) }}</dt><dd><details v-if="value!==null&&typeof value==='object'"><summary>展开{{ fieldLabel(String(key)) }}</summary><RecordList :value="value"/></details><span v-else>{{ displayValue(String(key),value) }}</span></dd></template></dl></article></div></template>
<style scoped>article{padding:12px 0;border-bottom:1px solid #e5e7eb}dl{display:grid;grid-template-columns:minmax(110px,160px) minmax(0,1fr);gap:8px 16px;margin:0}dt{color:#667085}dd{margin:0;overflow-wrap:anywhere}summary{cursor:pointer} @media(max-width:600px){dl{grid-template-columns:100px minmax(0,1fr);gap:8px}}</style>
