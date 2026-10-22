<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Tabbar, TabbarItem } from 'vant'
import { currentUser } from '@/shared/http/client'

const route = useRoute(), router = useRouter(), online = ref(navigator.onLine)
const isShop = computed(() => currentUser.value?.roles.includes('REPAIR_SHOP') === true)
const tabs = computed(() => isShop.value
  ? [{to:'/',label:'任务',glyph:'home'},{to:'/work-orders',label:'案件',glyph:'case'},{to:'/notifications',label:'通知',glyph:'bell'},{to:'/me',label:'我的',glyph:'user'}]
  : [{to:'/',label:'案件',glyph:'case'},{to:'/notifications',label:'通知',glyph:'bell'},{to:'/me',label:'我的',glyph:'user'}])
const active = computed(() => {
  if (route.path.startsWith('/notifications')) return '/notifications'
  if (['/complaints','/help','/guide'].some(path=>route.path.startsWith(path))) return '/me'
  if (route.path.startsWith('/me')) return '/me'
  if (route.path.startsWith('/work-orders')) return isShop.value ? '/work-orders' : '/'
  return '/'
})
function updateOnline(){online.value=navigator.onLine}
function navigate(path:string){if(path!==route.path)void router.push(path)}
onMounted(()=>{window.addEventListener('online',updateOnline);window.addEventListener('offline',updateOnline)})
onBeforeUnmount(()=>{window.removeEventListener('online',updateOnline);window.removeEventListener('offline',updateOnline)})
</script>

<template>
  <div class="h5-shell">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <header class="h5-context">
      <button class="brand" aria-label="返回角色首页" @click="router.push('/')"><span class="brand-mark"><svg viewBox="0 0 32 32" aria-hidden="true"><path d="M16 2 28 7v9c0 8-5 12-12 14C9 28 4 24 4 16V7L16 2Z" fill="currentColor"/><path d="m9 16 5 5 9-10" fill="none" stroke="white" stroke-width="2.5"/></svg></span><span><strong>e保</strong><small>保险维修服务</small></span></button>
      <span class="role-chip">{{ isShop ? '维修网点' : '车主' }}</span>
    </header>
    <p v-if="!online" class="offline-banner" role="alert">连接已断开。已填写内容会保留，请恢复网络后手动重试。</p>
    <main id="main-content" class="h5-content" tabindex="-1"><slot /></main>
    <Tabbar :model-value="active" safe-area-inset-bottom placeholder>
      <TabbarItem v-for="tab in tabs" :key="tab.to" :name="tab.to" @click="navigate(tab.to)"><template #icon><svg class="tab-glyph" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><template v-if="tab.glyph==='home'"><path d="m3 11 9-8 9 8v9H3z"/><path d="M9 20v-6h6v6"/></template><template v-else-if="tab.glyph==='case'"><path d="M5 5h14v16H5zM9 5V3h6v2M8 10h8M8 14h8"/></template><template v-else-if="tab.glyph==='bell'"><path d="M5 17h14l-2-3V9a5 5 0 0 0-10 0v5zM10 20h4"/></template><template v-else><circle cx="12" cy="8" r="4"/><path d="M4 21c0-4 3.6-6 8-6s8 2 8 6"/></template></svg></template>{{ tab.label }}</TabbarItem>
    </Tabbar>
  </div>
</template>

<style scoped>
.h5-shell { min-height: 100dvh; background: var(--color-canvas); }
.h5-context {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 58px;
  overflow: hidden;
  padding: 8px 16px;
  border-bottom: 1px solid var(--color-border);
  background: var(--glass-surface);
  box-shadow: inset 0 1px 0 rgba(255,255,255,.96),0 8px 26px rgba(65,55,38,.055);
  backdrop-filter:blur(16px) saturate(.78);
}
.h5-context::before {
  position: absolute;
  inset: 0;
  background-image: linear-gradient(rgba(65,55,38,.035) 1px,transparent 1px),linear-gradient(90deg,rgba(65,55,38,.035) 1px,transparent 1px);
  background-size: 18px 18px;
  content: '';
  mask-image: linear-gradient(90deg,transparent 32%,#000);
  pointer-events: none;
}
.h5-context::after {
  position: absolute;
  right: 62px;
  bottom: 8px;
  width: 92px;
  height: 30px;
  border-top: 1px solid rgba(65,55,38,.12);
  border-bottom: 1px solid rgba(65,55,38,.08);
  transform: skewX(-28deg);
  content: '';
  pointer-events: none;
}
.brand { display: flex; align-items: center; gap: 9px; padding: 0; border: 0; background: transparent; color: var(--color-text); text-align: left; font: inherit; }
.brand-mark {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 1px solid rgba(255,255,255,.94);
  border-radius: 10px;
  background: var(--glass-surface);
  box-shadow: inset 0 1px 0 #fff,var(--shadow-surface);
  color: var(--color-accent);
  font-size: 20px;
}
.brand-mark svg { width: 20px; height: 20px; }
.brand strong,.brand small { display: block; }
.brand strong { font-size: 15px; letter-spacing: .02em; }
.brand small { margin-top: 1px; color: var(--color-text-secondary); font-size: 10px; }
.role-chip { position: relative; z-index: 1; padding: 5px 8px; border: 1px solid var(--color-border); border-radius: 999px; background: rgba(255,254,250,.84); color: var(--color-text-secondary); font-size: 11px; }
.offline-banner { margin: 0; padding: 10px 16px; background: var(--color-warning-soft); color: var(--color-warning); font-size: 13px; line-height: 20px; }
.h5-content { min-height: calc(100dvh - 58px); padding-bottom: calc(20px + env(safe-area-inset-bottom)); outline: none; }
:deep(.van-tabbar) {
  border-top: 1px solid var(--color-border);
  background: var(--glass-surface)!important;
  box-shadow: inset 0 1px 0 #fff,0 -10px 28px rgba(65,55,38,.07);
  backdrop-filter:blur(16px) saturate(.78);
}
:deep(.van-tabbar-item) { min-height: 56px; background: transparent; transition: color 180ms ease,transform 220ms var(--motion-fluid); }
:deep(.van-tabbar-item--active) { transform: translate3d(0,-2px,0); }
:deep(.van-tabbar-item--active .tab-glyph) { transform: scale(1.08); filter: drop-shadow(0 3px 5px rgba(53,56,59,.18)); }
.tab-glyph { display: block; width: 22px; height: 22px; transition: transform 220ms var(--motion-fluid),filter 220ms var(--motion-fluid); }
:deep(.van-tabbar-item__icon) { font-size: 21px; }
:deep(.van-tabbar-item__text) { font-size: 11px; }
@supports not (backdrop-filter:blur(1px)) { .h5-context,:deep(.van-tabbar) { background: #fffefa!important; } }
@media(prefers-reduced-transparency:reduce) {
  .h5-context,:deep(.van-tabbar) { background: #fffefa!important; backdrop-filter: none; }
  .brand-mark,.role-chip { background: #fffefa; }
}
@media(prefers-reduced-motion:reduce) { :deep(.van-tabbar-item),.tab-glyph { transition: none; } }
</style>
