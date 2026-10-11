<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import { ConfigProvider } from 'vant'
import H5Shell from '@/shared/ui/H5Shell.vue'
import { currentUser } from '@/shared/http/client'

const route = useRoute()
const useShell = computed(() => Boolean(currentUser.value) && route.meta.shell !== false)
const themeVars = {
  colorPrimary: '#526E6E',
  textColor: '#2C3133',
  textColor2: '#667074',
  borderColor: '#D5D9DA',
  background: '#F7F8F8',
  background2: '#FFFFFF',
  buttonPrimaryBackground: '#526E6E',
  buttonPrimaryBorderColor: '#526E6E',
  buttonBorderRadius: '8px',
  fieldLabelColor: '#596265',
  navBarBackground: '#F7F8F8',
  navBarTitleTextColor: '#2C3133',
  tabbarBackground: 'rgba(255,255,255,.96)',
  tabbarItemActiveColor: '#526E6E',
  tabbarItemTextColor: '#6F777A',
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
