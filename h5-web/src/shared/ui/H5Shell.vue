<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Icon, Tabbar, TabbarItem } from 'vant'
import { currentUser } from '@/shared/http/client'

const route = useRoute(), router = useRouter(), online = ref(navigator.onLine)
const isShop = computed(() => currentUser.value?.roles.includes('REPAIR_SHOP') === true)
const tabs = computed(() => isShop.value
  ? [{to:'/',label:'任务',icon:'todo-list-o'},{to:'/work-orders',label:'案件',icon:'orders-o'},{to:'/notifications',label:'通知',icon:'bell-o'},{to:'/me',label:'我的',icon:'user-o'}]
  : [{to:'/',label:'案件',icon:'orders-o'},{to:'/notifications',label:'通知',icon:'bell-o'},{to:'/complaints',label:'投诉',icon:'comment-o'},{to:'/me',label:'我的',icon:'user-o'}])
const active = computed(() => {
  if (route.path.startsWith('/notifications')) return '/notifications'
  if (route.path.startsWith('/complaints')) return '/complaints'
  if (route.path.startsWith('/me')) return '/me'
  if (route.path.startsWith('/work-orders')) return isShop.value ? '/work-orders' : '/'
  return '/'
})
function updateOnline(){online.value=navigator.onLine}
function navigate(path:string){if(path!==active.value)void router.push(path)}
onMounted(()=>{window.addEventListener('online',updateOnline);window.addEventListener('offline',updateOnline)})
onBeforeUnmount(()=>{window.removeEventListener('online',updateOnline);window.removeEventListener('offline',updateOnline)})
</script>

<template>
  <div class="h5-shell">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <header class="h5-context">
      <button class="brand" aria-label="返回角色首页" @click="router.push('/')"><span class="brand-mark"><Icon name="shield-o" /></span><span><strong>e保</strong><small>保险协作台 · 设计占位</small></span></button>
      <span class="role-chip">{{ isShop ? '维修网点' : '车主' }}</span>
    </header>
    <p v-if="!online" class="offline-banner" role="alert">连接已断开。已填写内容会保留，请恢复网络后手动重试。</p>
    <main id="main-content" class="h5-content" tabindex="-1"><slot /></main>
    <Tabbar :model-value="active" safe-area-inset-bottom placeholder @change="navigate">
      <TabbarItem v-for="tab in tabs" :key="tab.to" :name="tab.to" :icon="tab.icon">{{ tab.label }}</TabbarItem>
    </Tabbar>
  </div>
</template>

<style scoped>
.h5-shell{min-height:100dvh;background:var(--color-canvas)}
.h5-context{position:sticky;top:0;z-index:20;display:flex;align-items:center;justify-content:space-between;min-height:58px;overflow:hidden;padding:8px 16px;border-bottom:1px solid rgba(213,217,218,.76);background:linear-gradient(120deg,rgba(255,255,255,.82),rgba(242,245,245,.7));box-shadow:inset 0 1px 0 rgba(255,255,255,.96),0 8px 26px rgba(82,110,110,.055);backdrop-filter:blur(16px) saturate(.78)}
.h5-context::before{position:absolute;inset:0;background-image:linear-gradient(rgba(82,110,110,.035) 1px,transparent 1px),linear-gradient(90deg,rgba(82,110,110,.035) 1px,transparent 1px);background-size:18px 18px;content:'';mask-image:linear-gradient(90deg,transparent 32%,#000);pointer-events:none}.h5-context::after{position:absolute;right:62px;bottom:8px;width:92px;height:30px;border-top:1px solid rgba(82,110,110,.12);border-bottom:1px solid rgba(82,110,110,.08);transform:skewX(-28deg);content:'';pointer-events:none}
.brand{display:flex;align-items:center;gap:9px;padding:0;border:0;background:transparent;color:var(--color-text);text-align:left;font:inherit}
.brand-mark{display:grid;width:34px;height:34px;place-items:center;border:1px solid rgba(255,255,255,.94);border-radius:10px;background:rgba(255,255,255,.76);box-shadow:inset 0 1px 0 #fff,0 6px 16px rgba(82,110,110,.07);color:var(--color-accent);font-size:20px}
.brand strong,.brand small{display:block}.brand strong{font-size:15px;letter-spacing:.02em}.brand small{margin-top:1px;color:var(--color-text-secondary);font-size:10px}
.role-chip{position:relative;z-index:1;padding:5px 8px;border:1px solid rgba(255,255,255,.92);border-radius:999px;background:rgba(255,255,255,.72);box-shadow:inset 0 1px 0 #fff;color:var(--color-text-secondary);font-size:11px}
.offline-banner{margin:0;padding:10px 16px;background:var(--color-warning-soft);color:var(--color-warning);font-size:13px;line-height:20px}
.h5-content{min-height:calc(100dvh - 58px);padding-bottom:calc(20px + env(safe-area-inset-bottom));outline:none}
:deep(.van-tabbar){border-top:1px solid rgba(255,255,255,.94);background:rgba(255,255,255,.86)!important;box-shadow:inset 0 1px 0 rgba(255,255,255,.96),0 -10px 28px rgba(82,110,110,.07);backdrop-filter:blur(16px) saturate(.78)}
:deep(.van-tabbar-item){min-height:56px;background:transparent;transition:color 180ms ease,transform 220ms cubic-bezier(.16,1,.3,1)}:deep(.van-tabbar-item--active){transform:translate3d(0,-2px,0)}:deep(.van-tabbar-item__icon){font-size:21px}:deep(.van-tabbar-item__text){font-size:11px}
@supports not (backdrop-filter:blur(1px)){.h5-context{background:#f7f8f8}}
@media(prefers-reduced-transparency:reduce){.h5-context,:deep(.van-tabbar){background:#fff!important;backdrop-filter:none}.brand-mark,.role-chip{background:#fff}}
</style>
