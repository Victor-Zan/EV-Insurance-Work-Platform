<script setup lang="ts">
import { RouterView, useRoute } from 'vue-router'
import AppShell from '@/shared/ui/AppShell.vue'
import { currentUser } from '@/shared/http/client'
const route = useRoute()
</script>

<template>
  <AppShell v-if="currentUser && route.path !== '/login'"><RouterView :key="route.path" /></AppShell>
  <RouterView v-else :key="route.path" />
</template>
