<script setup lang="ts">
import { computed, onMounted, onUnmounted, provide, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ElButton } from 'element-plus'
import { authSession, currentUser, httpClient } from '@/shared/http/client'
import { createUnreadCounter, unreadNotificationsKey } from '@/features/notification/unread-counter'
import WorkOrderAutocomplete from './WorkOrderAutocomplete.vue'
import engineeringBlueprint from '@/assets/e-scooter-graphite.png'

type Module = 'home' | 'cases' | 'pricing' | 'funds' | 'notices' | 'admin'
const route = useRoute(), router = useRouter(), preview = ref<Module | null>(null), opened = ref<Module | null>(null)
const desktopWide=ref(window.innerWidth>=1440)
const unread=createUnreadCounter(async () => (await httpClient.get<{data:{count:number}}>('/notifications/unread-count')).data.data.count)
const unreadCount=unread.count,unreadLabel=unread.label
provide(unreadNotificationsKey,unread)
let unreadTimer:ReturnType<typeof setInterval>|undefined
function refreshUnread(){if(document.visibilityState==='visible'&&authSession.valid())void unread.refresh()}
watch(()=>currentUser.value?.id,id=>{unread.reset();if(id)refreshUnread()},{immediate:true})
watch(()=>route.fullPath,refreshUnread)
let previewTimer:ReturnType<typeof setTimeout>|undefined
function cancelPreviewClose(){if(previewTimer)clearTimeout(previewTimer);previewTimer=undefined}
function schedulePreviewClose(){cancelPreviewClose();previewTimer=setTimeout(()=>{preview.value=null},120)}
function onResize(){const wide=window.innerWidth>=1440;if(wide!==desktopWide.value){desktopWide.value=wide;opened.value=wide&&active.value!=='pricing'&&route.path!=='/funds/analytics'?active.value:null;preview.value=null}}
onMounted(()=>{window.addEventListener('resize',onResize);window.addEventListener('focus',refreshUnread);document.addEventListener('visibilitychange',refreshUnread);unreadTimer=setInterval(refreshUnread,30000)})
onUnmounted(()=>{cancelPreviewClose();window.removeEventListener('resize',onResize);window.removeEventListener('focus',refreshUnread);document.removeEventListener('visibilitychange',refreshUnread);if(unreadTimer)clearInterval(unreadTimer);unread.reset()})
const isAdmin = computed(() => currentUser.value?.roles.includes('ADMIN') === true)
const isService = computed(() => currentUser.value?.roles.includes('CUSTOMER_SERVICE') === true)
const modules = computed(() => [
  { id: 'home' as Module, label: '工作台', path: '/', glyph: 'home' },
  { id: 'cases' as Module, label: '案件', path: '/work-orders', glyph: 'case' },
  { id: 'pricing' as Module, label: '价格库', path: '/pricing', glyph: 'price' },
  ...(isService.value || isAdmin.value ? [{ id: 'funds' as Module, label: '资金', path: '/funds/analytics', glyph: 'funds' }] : []),
  { id: 'notices' as Module, label: '通知', path: '/notifications?tab=notices', glyph: 'bell' },
  ...(isAdmin.value ? [{ id: 'admin' as Module, label: '管理', path: '/admin/users', glyph: 'admin' }] : []),
])
const active = computed<Module>(() => route.path.startsWith('/work-orders') || route.path.startsWith('/historical-cases') || route.path.startsWith('/complaints') ? 'cases' : route.path.startsWith('/pricing') ? 'pricing' : route.path.startsWith('/funds') ? 'funds' : route.path.startsWith('/notifications') ? 'notices' : route.path.startsWith('/admin') || route.path === '/users' ? 'admin' : 'home')
const visible = computed(() => preview.value ?? opened.value)
const links = computed(() => {
  switch (visible.value) {
    case 'home': return [{ label: '工作台', to: '/' }, { label: '我的待办', to: '/notifications?tab=todos' }]
    case 'cases': return [{ label: '全部案件', to: '/work-orders' }, { label: '历史档案', to: '/historical-cases' }, { label: '投诉跟踪', to: '/complaints' }, { label: '案件草稿', to: '/work-orders?status=DRAFT' }]
    case 'funds': return [{ label: '经营看板', to: '/funds/analytics' }, { label: '案件收支', to: '/funds/workbench' }, ...(isService.value ? [{ label: '资金导入', to: '/funds/imports' }] : [])]
    case 'notices': return [{ label: '我的待办', to: '/notifications?tab=todos' }, { label: '站内通知', to: '/notifications?tab=notices' }]
    case 'admin': return [{ label: '用户与角色', to: '/admin/users' }, { label: '区域', to: '/admin/regions' }, { label: '网点', to: '/admin/shops' }, { label: '审计日志', to: '/admin/audit-logs' }, { label: '重复判断配置', to: '/admin/settings/work-orders' }]
    default: return []
  }
})
watch(active, value => { if(desktopWide.value&&value!=='pricing')opened.value=value;else if(opened.value!==value)opened.value=null;preview.value=null }, { immediate: true })
watch(()=>route.path,path=>{if(path==='/funds/analytics'){opened.value=null;preview.value=null}},{immediate:true})
function activate(id: Module, path: string) { opened.value = id==='pricing'?null:id; preview.value = null; void router.push(path) }
function previewModule(id: Module) { cancelPreviewClose(); preview.value = id==='pricing'?null:id }
function close() { preview.value = null; opened.value = null }

function logout() { authSession.clear(); void router.replace('/login') }
const previousPage = computed(() => {
  // Reading the current route keeps the history lookup in sync after navigation.
  const current = route.fullPath
  const previous = router.options.history.state.back
  return typeof previous === 'string' && previous.startsWith('/') &&
    previous !== current && !['/login', '/activate'].includes(router.resolve(previous).path)
})
function goBack() {
  if (previousPage.value) {
    router.back()
    return
  }
  const fallback = route.params.id && route.path.startsWith('/work-orders/')
    ? route.query.section === 'funds' ? '/funds/workbench' : '/work-orders'
    : '/'
  void router.replace(fallback)
}
</script>

<template>
  <div class="app-layout" @keyup.esc="close">
    <a class="skip-link" href="#main-content">跳到主内容</a>
    <nav class="command-rail" aria-label="主导航" @mouseleave="schedulePreviewClose">
      <RouterLink class="brand-mark" to="/" aria-label="工作台首页"><svg viewBox="0 0 32 32" aria-hidden="true"><path d="M16 2 28 7v9c0 8-5 12-12 14C9 28 4 24 4 16V7L16 2Z" fill="currentColor"/><path d="m9 16 5 5 9-10" fill="none" stroke="white" stroke-width="2.5"/></svg><small>e保</small></RouterLink>
      <button v-for="item in modules" :key="item.id" class="rail-item" :class="{ 'is-active': active===item.id }" type="button" :aria-label="item.id==='notices'&&unreadCount?`通知，${unreadCount}条未读`:item.label" :aria-expanded="item.id==='pricing'?undefined:visible===item.id" :aria-current="active===item.id?'page':undefined" @click="activate(item.id,item.path)" @mouseenter="previewModule(item.id)" @focus="previewModule(item.id)">
        <svg :class="`rail-icon-${item.id}`" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <template v-if="item.glyph==='home'"><path d="m3 11 9-8 9 8v9H3z"/><path d="M9 20v-6h6v6"/></template>
          <template v-else-if="item.glyph==='case'"><path d="M5 5h14v16H5zM9 5V3h6v2M8 10h8M8 14h8"/></template>
          <template v-else-if="item.glyph==='price'"><path d="M5 3h14v18H5zM8 8h8M8 12h8M8 16h5"/></template>
          <template v-else-if="item.glyph==='funds'"><path d="M3 21h18M5 18V9h4v9M10 18V5h4v13M15 18v-7h4v7"/></template>
          <template v-else-if="item.glyph==='bell'"><path d="M5 17h14l-2-3V9a5 5 0 0 0-10 0v5zM10 20h4"/></template>
          <template v-else><path d="M4 20h16M6 20V5l6-2 6 2v15M9 9h1M14 9h1M9 13h1M14 13h1"/></template>
        </svg><span>{{ item.label }}</span><span v-if="item.id==='notices'&&unreadCount>0" class="unread-badge rail-unread-badge" aria-hidden="true">{{ unreadLabel }}</span>
      </button>
      <div class="rail-spacer" />
      <div class="rail-user" :title="currentUser?.displayName">{{ currentUser?.displayName?.slice(0, 1) }}</div>
    </nav>
    <Transition name="pod-slide"><aside v-if="visible" class="module-pod" :aria-label="`${modules.find(item=>item.id===visible)?.label}模块导航`" @mouseenter="cancelPreviewClose" @mouseleave="schedulePreviewClose">
      <div class="pod-heading"><strong>{{ modules.find(item=>item.id===visible)?.label }}</strong><button type="button" aria-label="收起模块导航" @click="close">×</button></div>

      <nav><RouterLink v-for="link in links" :key="link.to" :to="link.to" @click="opened=visible;preview=null">{{ link.label }}</RouterLink></nav>
    </aside></Transition>
    <div class="app-main-wrap" @click="preview=null;opened=null">
      <header class="context-header"><img class="context-blueprint" :src="engineeringBlueprint" alt="" aria-hidden="true"><div class="header-start"><ElButton :disabled="route.path === '/' && !previousPage" @click="goBack"><span aria-hidden="true">←</span> 返回</ElButton><WorkOrderAutocomplete v-if="route.path!=='/funds/analytics'" class="global-search" placeholder="搜索案件号、报案号或手机号" @select="order => router.push(`/work-orders/${order.id}`)"/></div><div class="header-actions" :style="route.path==='/funds/analytics'?{marginLeft:'auto'}:undefined"><RouterLink class="notification-link" to="/notifications?tab=notices" :aria-label="unreadCount?`站内通知，${unreadCount}条未读`:'站内通知'">站内通知<span v-if="unreadCount>0" class="unread-badge" aria-hidden="true">{{ unreadLabel }}</span></RouterLink><span>{{ currentUser?.displayName }}</span><ElButton text @click="logout">退出</ElButton></div></header>
      <main id="main-content" class="app-content" tabindex="-1"><slot /></main>
    </div>
  </div>
</template>
