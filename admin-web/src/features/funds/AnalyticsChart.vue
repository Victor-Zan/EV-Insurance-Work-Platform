<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { init, use, type EChartsType } from 'echarts/core'
import type { EChartsOption } from 'echarts'
import { LineChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, TitleComponent, AriaComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
use([LineChart,BarChart,GridComponent,TooltipComponent,TitleComponent,AriaComponent,CanvasRenderer])
const props=defineProps<{option:EChartsOption;label:string}>()
const emit=defineEmits<{select:[index:number]}>()
const element=ref<HTMLElement>()
let chart:EChartsType|undefined,observer:ResizeObserver|undefined
onMounted(()=>{if(!element.value)return;chart=init(element.value);chart.setOption(props.option);chart.on('click',event=>{if(typeof event.dataIndex==='number')emit('select',event.dataIndex)});observer=new ResizeObserver(()=>chart?.resize());observer.observe(element.value)})
watch(()=>props.option,option=>chart?.setOption(option,true),{deep:true})
onBeforeUnmount(()=>{observer?.disconnect();chart?.dispose()})
</script>
<template><div ref="element" class="analytics-chart" role="img" :aria-label="label" /></template>
<style scoped>.analytics-chart{width:100%;height:255px;min-width:0}</style>
