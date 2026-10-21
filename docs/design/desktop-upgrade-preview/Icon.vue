<script setup lang="ts">
defineProps<{ name: string; size?: number }>()
const paths: Record<string, string> = {
  home: 'm3 11 9-8 9 8v9H3z M9 20v-6h6v6',
  case: 'M5 5h14v16H5z M9 5V3h6v2 M8 10h8 M8 14h8',
  price: 'M5 3h14v18H5z M8 8h8 M8 12h8 M8 16h5',
  funds: 'M3 21h18 M5 18V9h4v9 M10 18V5h4v13 M15 18v-7h4v7',
  bell: 'M5 17h14l-2-3V9a5 5 0 0 0-10 0v5z M10 20h4',
  admin: 'M4 20h16 M6 20V5l6-2 6 2v15 M9 9h1 M14 9h1 M9 13h1 M14 13h1',
  search: 'M21 21l-5-5 M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
  arrow: 'M5 12h14 M13 6l6 6-6 6',
  back: 'M19 12H5 M11 6l-6 6 6 6',
  check: 'm5 12 4 4L19 6',
  close: 'm6 6 12 12 M18 6 6 18',
  plus: 'M12 5v14 M5 12h14',
  more: 'M4 12h1 M11 12h1 M18 12h1',
  down: 'm6 9 6 6 6-6',
  expand: 'M9 3H3v6 M15 3h6v6 M3 15v6h6 M21 15v6h-6',
  file: 'M14 3H5v18h14V8z M14 3v5h5 M8 12h8 M8 16h5',
  clock: 'M12 8v5l3 2 M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
  filter: 'M4 7h16 M7 12h10 M10 17h4',
  history: 'M3 5v5h5 M4 9a9 9 0 1 1 0 7 M12 7v5l3 2',
  upload: 'M12 16V3 M7 8l5-5 5 5 M4 16v5h16v-5',
}
</script>
<template><svg :width="size || 20" :height="size || 20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] || paths.file" /></svg></template>
