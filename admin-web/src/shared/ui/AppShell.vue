<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ElButton, ElInput } from 'element-plus'
import { authSession, currentUser } from '@/shared/http/client'
import engineeringBlueprint from '@/assets/e-scooter-blueprint-panorama.webp'
import { vMagnetic } from '@/shared/ui/magnetic'

type Module = 'home' | 'cases' | 'pricing' | 'funds' | 'notices' | 'admin'
const route = useRoute(), router = useRouter(), search = ref(''), preview = ref<Module | null>(null), opened = ref<Module | null>(null)
const desktopWide=ref(window.innerWidth>=1440)
let previewTimer:ReturnType<typeof setTimeout>|undefined
function cancelPreviewClose(){if(previewTimer)clearTimeout(previewTimer);previewTimer=undefined}
function schedulePreviewClose(){cancelPreviewClose();previewTimer=setTimeout(()=>{preview.value=null},120)}
function onResize(){const wide=window.innerWidth>=1440;if(wide!==desktopWide.value){desktopWide.value=wide;opened.value=wide?active.value:null;preview.value=null}}
onMounted(()=>window.addEventListener('resize',onResize))
onUnmounted(()=>{cancelPreviewClose();window.removeEventListener('resize',onResize)})
const isAdmin = computed(() => currentUser.value?.roles.includes('ADMIN') === true)
const isService = computed(() => currentUser.value?.roles.includes('CUSTOMER_SERVICE') === true)
const modules = computed(() => [
  { id: 'home' as Module, label: '工作台', path: '/', glyph: 'home' },
  { id: 'cases' as Module, label: '案件', path: '/work-orders', glyph: 'case' },
  { id: 'pricing' as Module, label: '价格库', path: '/pricing', glyph: 'price' },
  ...(isService.value ? [{ id: 'funds' as Module, label: '资金', path: '/funds/imports', glyph: 'funds' }] : []),
  { id: 'notices' as Module, label: '通知', path: '/notifications', glyph: 'bell' },
  ...(isAdmin.value ? [{ id: 'admin' as Module, label: '管理', path: '/admin/users', glyph: 'admin' }] : []),
])
const active = computed<Module>(() => route.path.startsWith('/work-orders') || route.path.startsWith('/historical-cases') ? 'cases' : route.path.startsWith('/pricing') ? 'pricing' : route.path.startsWith('/funds') ? 'funds' : route.path.startsWith('/notifications') ? 'notices' : route.path.startsWith('/admin') || route.path === '/users' ? 'admin' : 'home')
const visible = computed(() => preview.value ?? opened.value)
const links = computed(() => {
  switch (visible.value) {
    case 'home': return [{ label: '工作台', to: '/' }, { label: '我的待办', to: '/notifications?tab=todos' }]
    case 'cases': return [{ label: '全部案件', to: '/work-orders' }, { label: '历史档案导入', to: '/historical-cases' }, { label: '我的待办', to: '/notifications?tab=todos' }, { label: '案件草稿', to: '/work-orders?status=DRAFT' }]
    case 'pricing': return [{ label: '价格候选', to: '/pricing' }, { label: '品牌资料', to: '/pricing/catalogue/brands' }, { label: '车型资料', to: '/pricing/catalogue/models' }, { label: '配件资料', to: '/pricing/catalogue/parts' }, { label: '适配关系', to: '/pricing/part-models' }, ...(isAdmin.value ? [{ label: '价格导入', to: '/pricing/import' }] : [])]
    case 'funds': return [{ label: '资金导入', to: '/funds/imports' }, { label: '案件资金记录', to: '/work-orders' }]
    case 'notices': return [{ label: '我的待办', to: '/notifications?tab=todos' }, { label: '站内通知', to: '/notifications?tab=notices' }]
    case 'admin': return [{ label: '用户与角色', to: '/admin/users' }, { label: '区域', to: '/admin/regions' }, { label: '网点', to: '/admin/shops' }, { label: '审计日志', to: '/admin/audit-logs' }, { label: '重复判断配置', to: '/admin/settings/work-orders' }]
    default: return []
  }
})
watch(active, value => { if(desktopWide.value)opened.value=value;else if(opened.value!==value)opened.value=null;preview.value=null }, { immediate: true })
function activate(id: Module, path: string) { opened.value = id; preview.value = null; void router.push(path) }
function close() { preview.value = null; opened.value = null }
function submitSearch() { if (search.value.trim()) void router.push({ path: '/work-orders', query: { query: search.value.trim() } }) }
function logout() { authSession.clear(); void router.replace('/login') }
</script>

<template>
  <div class="app-layout" @keyup.esc="close">
    <a class="skip-link" href="#main-content">跳到主内容</a>
    <nav class="command-rail" aria-label="主导航" @mouseleave="schedulePreviewClose">
      <RouterLink class="brand-mark" to="/" aria-label="工作台首页"><svg viewBox="0 0 32 32" aria-hidden="true"><path d="M16 2 28 7v9c0 8-5 12-12 14C9 28 4 24 4 16V7L16 2Z" fill="currentColor"/><path d="m9 16 5 5 9-10" fill="none" stroke="white" stroke-width="2.5"/></svg><small>e保</small></RouterLink>
      <button v-for="item in modules" :key="item.id" class="rail-item" :class="{ 'is-active': active===item.id }" type="button" :aria-label="item.label" :aria-expanded="visible===item.id" :aria-current="active===item.id?'page':undefined" @click="activate(item.id,item.path)" @mouseenter="cancelPreviewClose();preview=item.id" @focus="preview=item.id">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <template v-if="item.glyph==='home'"><path d="m3 11 9-8 9 8v9H3z"/><path d="M9 20v-6h6v6"/></template>
          <template v-else-if="item.glyph==='case'"><path d="M5 5h14v16H5zM9 5V3h6v2M8 10h8M8 14h8"/></template>
          <template v-else-if="item.glyph==='price'"><path d="M5 3h14v18H5zM8 8h8M8 12h8M8 16h5"/></template>
          <template v-else-if="item.glyph==='funds'"><path d="M3 21h18M5 18V9h4v9M10 18V5h4v13M15 18v-7h4v7"/></template>
          <template v-else-if="item.glyph==='bell'"><path d="M5 17h14l-2-3V9a5 5 0 0 0-10 0v5zM10 20h4"/></template>
          <template v-else><path d="M4 20h16M6 20V5l6-2 6 2v15M9 9h1M14 9h1M9 13h1M14 13h1"/></template>
        </svg><span>{{ item.label }}</span>
      </button>
      <div class="rail-spacer" />
      <div class="rail-user" :title="currentUser?.displayName">{{ currentUser?.displayName?.slice(0, 1) }}</div>
    </nav>
    <Transition name="pod-slide"><aside v-if="visible" class="module-pod" :aria-label="`${modules.find(item=>item.id===visible)?.label}模块导航`" @mouseenter="cancelPreviewClose" @mouseleave="schedulePreviewClose">
      <div class="pod-heading"><strong>{{ modules.find(item=>item.id===visible)?.label }}</strong><button type="button" aria-label="收起模块导航" @click="close">×</button></div>
      <p>当前模块导航</p>
      <nav><RouterLink v-for="link in links" :key="link.to" :to="link.to" @click="opened=visible;preview=null">{{ link.label }}</RouterLink></nav>
    </aside></Transition>
    <div class="app-main-wrap" @click="preview=null;opened=null">
      <header class="context-header"><img class="context-blueprint" :src="engineeringBlueprint" alt="" aria-hidden="true"><form class="global-search" role="search" @submit.prevent="submitSearch"><ElInput v-model="search" clearable placeholder="搜索案件号、报案号或手机号" aria-label="搜索案件"/><ElButton v-magnetic native-type="submit">搜索</ElButton></form><div class="header-actions"><RouterLink to="/notifications">站内通知</RouterLink><span>{{ currentUser?.displayName }}</span><ElButton text @click="logout">退出</ElButton></div></header>
      <main id="main-content" class="app-content" tabindex="-1"><slot /></main>
    </div>
  </div>
</template>
