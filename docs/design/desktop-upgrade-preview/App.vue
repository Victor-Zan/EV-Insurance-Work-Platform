<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import Icon from './Icon.vue'
import HomeSample from './HomeSample.vue'
import CaseSample from './CaseSample.vue'
import TableSample from './TableSample.vue'
import blueprint from './assets/scooter-graphite.png'

type Page = 'home' | 'case' | 'table'
const pages: { key: Page; label: string }[] = [{ key: 'home', label: '工作台' }, { key: 'case', label: '案件详情' }, { key: 'table', label: '表格与表单' }]
const page = ref<Page>((['home', 'case', 'table'].includes(location.hash.slice(1)) ? location.hash.slice(1) : 'home') as Page)
const search = ref(''), quiet = ref(false), solid = ref(false), navOpen = ref(false)
const scrollArea = ref<HTMLElement>(), home = ref<InstanceType<typeof HomeSample>>()
const role = computed(() => page.value === 'table' ? '管理员' : '客服')
const section = computed(() => page.value === 'home' ? '工作台' : page.value === 'case' ? '案件' : '价格库')
const nav = [{ key: 'home', name: '工作台' }, { key: 'case', name: '案件' }, { key: 'price', name: '价格库' }, { key: 'funds', name: '资金' }, { key: 'bell', name: '通知' }, { key: 'admin', name: '管理' }]
function navigate(key: Page) { page.value = key; location.hash = key; navOpen.value = false }
function hashChange() { const hash = location.hash.slice(1); if (['home', 'case', 'table'].includes(hash)) page.value = hash as Page }
function navClick(key: string) {
  if (key === 'home') navigate('home')
  else if (key === 'case') navigate('case')
  else if (key === 'price') navigate('table')
  else ElMessage.info('这轮展示工作台、案件详情和价格库样板，其他模块保留现有功能。')
}
let pendingHomeAction: 'search' | 'notices' | undefined
function runHomeAction() {
  if (pendingHomeAction === 'search') home.value?.setSearch(search.value)
  if (pendingHomeAction === 'notices') home.value?.showNotifications()
  pendingHomeAction = undefined
}
function searchSample() { pendingHomeAction = 'search'; if (page.value === 'home') runHomeAction(); else navigate('home') }
function showNotifications() { pendingHomeAction = 'notices'; if (page.value === 'home') runHomeAction(); else navigate('home') }
let observer: IntersectionObserver | undefined
function observe() {
  observer?.disconnect()
  if (quiet.value || matchMedia('(prefers-reduced-motion: reduce)').matches) return
  observer = new IntersectionObserver(entries => entries.forEach(entry => {
    if (entry.isIntersecting) { entry.target.classList.remove('will-reveal'); entry.target.classList.add('revealed'); observer?.unobserve(entry.target) }
  }), { root: scrollArea.value, threshold: 0.04 })
  const bottom = scrollArea.value?.getBoundingClientRect().bottom || innerHeight
  scrollArea.value?.querySelectorAll('[data-reveal]').forEach(node => {
    if (node.getBoundingClientRect().top > bottom - 10) { node.classList.add('will-reveal'); observer?.observe(node) }
  })
}
function viewReady() { scrollArea.value?.scrollTo({ top: 0 }); observe(); runHomeAction() }
watch(quiet, async () => { if (quiet.value) document.querySelectorAll('.will-reveal').forEach(n => n.classList.remove('will-reveal')); else { await nextTick(); observe() } })
onMounted(() => { addEventListener('hashchange', hashChange); observe() })
onBeforeUnmount(() => { removeEventListener('hashchange', hashChange); observer?.disconnect() })
</script>

<template>
  <div class="preview-root" :class="{ 'quiet-motion': quiet, 'solid-surfaces': solid }">
    <div class="review-bar"><span class="review-label"><span class="review-dot" />设计预览 <span class="review-separator">/</span> 示例数据</span><nav aria-label="选择设计样板"><button v-for="(item, index) in pages" :key="item.key" :aria-current="page === item.key ? 'page' : undefined" @click="navigate(item.key)"><span>0{{ index + 1 }}</span>{{ item.label }}</button></nav><div class="review-tools"><button :aria-pressed="quiet" @click="quiet = !quiet">{{ quiet ? '动效已减弱' : '减少动效' }}</button><button :aria-pressed="solid" @click="solid = !solid">{{ solid ? '实体背景' : '玻璃背景' }}</button></div></div>
    <a href="#main-content" class="skip-link">跳到主内容</a>
    <aside class="command-rail"><button class="brand-mark" aria-label="返回工作台样板" @click="navigate('home')"><svg viewBox="0 0 32 32" aria-hidden="true"><path d="M16 2 28 7v9c0 8-5 12-12 14C9 28 4 24 4 16V7L16 2Z" fill="currentColor"/><path d="m9 16 5 5 9-10" fill="none" stroke="white" stroke-width="2.2"/></svg><span>e保</span></button><nav aria-label="主导航"><button v-for="item in nav" :key="item.key" class="rail-button" :class="{ active: (page === 'table' ? 'price' : page) === item.key }" :aria-current="(page === 'table' ? 'price' : page) === item.key ? 'page' : undefined" @click="navClick(item.key)"><Icon :name="item.key" :size="22"/><span>{{ item.name }}</span></button></nav><div class="rail-bottom"><button class="avatar" aria-label="查看样板说明" @click="ElMessage.info('全部为本地示例数据，不连接业务接口。')">{{ role[0] }}</button><span>示例{{ role }}</span></div></aside>
    <div class="application">
      <header class="context-header glass"><div class="context-left"><button class="context-module" :aria-expanded="navOpen" @click="navOpen = !navOpen">{{ section }}<Icon name="down" :size="14"/></button><span class="context-divider"/><form class="global-search" role="search" @submit.prevent="searchSample"><Icon name="search" :size="17"/><input v-model="search" aria-label="搜索示例案件" placeholder="搜索案件号、报案号或手机号"><button aria-label="搜索" type="submit"><Icon name="arrow" :size="16"/></button></form></div><img class="context-art" :src="blueprint" alt=""><div class="header-tools"><button class="icon-button" aria-label="查看通知" @click="showNotifications"><Icon name="bell"/><span class="notification-dot"/></button><span class="header-rule"/><span>示例{{ role }}</span></div>
        <nav v-if="navOpen" class="module-menu glass" aria-label="模块导航"><button v-for="item in pages" :key="item.key" @click="navigate(item.key)">{{ item.label }}<Icon name="arrow" :size="16"/></button></nav>
      </header>
      <main id="main-content" ref="scrollArea" class="main-content" :class="{ 'case-main': page === 'case' }" tabindex="-1">
        <Transition name="page" mode="out-in" @after-enter="viewReady"><HomeSample v-if="page === 'home'" ref="home" @open-case="navigate('case')" @open-table="navigate('table')"/><CaseSample v-else-if="page === 'case'" @back="navigate('home')"/><TableSample v-else /></Transition>
      </main>
    </div>
  </div>
</template>
