<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import artwork from '@/assets/workbench-speedometer.png'

const speed = ref(0)
const activeSegments = computed(() => Math.round(speed.value / 60 * 16))
const segments = Array.from({ length: 16 }, (_, index) => {
  const point = (radius: number, degrees: number) => {
    const angle = degrees * Math.PI / 180
    return `${626 + radius * Math.cos(angle)},${589 + radius * Math.sin(angle)}`
  }
  const start = 135 + index * 270 / 16 + 1
  const end = 135 + (index + 1) * 270 / 16 - 1
  return `M${point(227, start)} A227,227 0 0 1 ${point(227, end)} L${point(194, end)} A194,194 0 0 0 ${point(194, start)} Z`
})
const digitSegments = ['abcdef', 'bc', 'abdeg', 'abcdg', 'bcfg', 'acdfg', 'acdefg', 'abc', 'abcdefg', 'abcdfg']
const digitPaths = {
  a: 'M10 0h60l-8 8H18z', b: 'M72 2v48l-8-6V10z',
  c: 'M72 54v48l-8-8V60z', d: 'M10 104h60l-8-8H18z',
  e: 'M8 102V54l8 6v34z', f: 'M8 50V2l8 8v34z',
  g: 'M10 52l8-5h44l8 5-8 5H18z',
}
const digits = computed(() => String(speed.value).padStart(2, '0').split('').map(Number))
let frame = 0
let started: number | undefined
let reducedMotion: MediaQueryList | undefined
function animate(time: number) {
  if (started === undefined) started = time
  speed.value = Math.round((1 - Math.cos((time - started) / 10000 * Math.PI * 2)) * 30)
  frame = requestAnimationFrame(animate)
}
function syncMotion() {
  cancelAnimationFrame(frame)
  started = undefined
  if (reducedMotion?.matches) speed.value = 30
  else frame = requestAnimationFrame(animate)
}
onMounted(() => {
  reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)')
  reducedMotion.addEventListener('change', syncMotion)
  syncMotion()
})
onUnmounted(() => {
  cancelAnimationFrame(frame)
  reducedMotion?.removeEventListener('change', syncMotion)
})
</script>

<template>
  <div class="workbench-speedometer" aria-hidden="true">
    <img :src="artwork" alt="" width="1254" height="1254">
    <svg viewBox="0 0 1254 1254" fill="none">
      <path v-for="(path, index) in segments" :key="index" :d="path" :class="{ lit: index < activeSegments }" class="speed-segment"/>
      <g transform="translate(522 516) scale(1.3)">
        <g v-for="(digit, index) in digits" :key="index" :transform="`translate(${index * 88} 0)`">
          <path v-for="(path, key) in digitPaths" :key="key" :d="path" :fill="digitSegments[digit]?.includes(key) ? '#f4f5f0' : '#ffffff08'"/>
        </g>
      </g>
    </svg>
  </div>
</template>

<style scoped>
.workbench-speedometer{position:relative;pointer-events:none;aspect-ratio:1}
img{display:block;width:100%;height:auto}
svg{position:absolute;inset:0;width:100%;height:100%}
.speed-segment{fill:#ffffff0c;stroke:#e1e3df80;stroke-width:1}
.speed-segment.lit{fill:#e4eee6;stroke:#edf6ee}
</style>
