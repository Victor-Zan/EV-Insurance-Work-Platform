<script setup lang="ts">
import { RouterView } from 'vue-router'
import NotificationPanel from './features/notification/NotificationPanel.vue'
import { currentUser } from '@/shared/http/client'
</script>

<template>
  <NotificationPanel v-if="currentUser" :key="currentUser.id" />
  <RouterView />
</template>
