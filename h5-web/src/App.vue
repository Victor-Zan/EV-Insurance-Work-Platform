<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import { ConfigProvider } from 'vant'
import H5Shell from '@/shared/ui/H5Shell.vue'
import { currentUser } from '@/shared/http/client'

const route = useRoute()
const useShell = computed(() => Boolean(currentUser.value) && route.meta.shell !== false)
const themeVars = {
  colorPrimary: '#35383B',
  textColor: '#292B2D',
  textColor2: '#706D65',
  borderColor: '#E0DCD3',
  background: '#F5F3EE',
  background2: '#FFFFFF',
  buttonPrimaryBackground: '#35383B',
  buttonPrimaryBorderColor: '#35383B',
  buttonBorderRadius: '8px',
  fieldLabelColor: '#706D65',
  navBarBackground: '#F5F3EE',
  navBarTitleTextColor: '#292B2D',
  tabbarBackground: 'rgba(255,254,250,.96)',
  tabbarItemActiveColor: '#35383B',
  tabbarItemTextColor: '#706D65',
}
</script>

<template>
  <ConfigProvider :theme-vars="themeVars">
    <RouterView v-slot="{ Component }">
      <H5Shell v-if="useShell"><Transition name="mobile-page-shift" mode="out-in"><component :is="Component" :key="route.path" /></Transition></H5Shell>
      <Transition v-else name="mobile-page-shift" mode="out-in"><component :is="Component" :key="route.path" /></Transition>
    </RouterView>
  </ConfigProvider>
</template>
