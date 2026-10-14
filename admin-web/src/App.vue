<script setup lang="ts">
import { RouterView, useRoute } from 'vue-router'
import AppShell from '@/shared/ui/AppShell.vue'
import { currentUser } from '@/shared/http/client'
const route = useRoute()
</script>

<template>
  <RouterView v-slot="{ Component }">
    <AppShell v-if="currentUser && route.path !== '/login'">
      <Transition name="page-shift" mode="out-in"><div :key="route.path"><component :is="Component" v-if="Component" /><p v-else role="status">正在加载页面…</p></div></Transition>
    </AppShell>
    <Transition v-else name="page-shift" mode="out-in"><div :key="route.path"><component :is="Component" v-if="Component" /><p v-else role="status">正在加载页面…</p></div></Transition>
  </RouterView>
</template>
