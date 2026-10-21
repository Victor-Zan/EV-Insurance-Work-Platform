<script setup lang="ts">
import { RouterView, useRoute } from 'vue-router'
import { ElConfigProvider } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import AppShell from '@/shared/ui/AppShell.vue'
import { currentUser } from '@/shared/http/client'
const route = useRoute()
</script>

<template>
  <ElConfigProvider :locale="zhCn">
  <RouterView v-slot="{ Component }">
    <AppShell v-if="currentUser && route.path !== '/login' && route.meta?.shell !== false">
      <Transition name="page-shift" mode="out-in"><div :key="route.path"><component :is="Component" v-if="Component" /><p v-else role="status">正在加载页面…</p></div></Transition>
    </AppShell>
    <Transition v-else name="page-shift" mode="out-in"><div :key="route.path"><component :is="Component" v-if="Component" /><p v-else role="status">正在加载页面…</p></div></Transition>
  </RouterView>
  </ElConfigProvider>
</template>
