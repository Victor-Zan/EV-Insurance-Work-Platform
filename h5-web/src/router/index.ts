import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '@/views/HomeView.vue'
import LoginView from '@/views/LoginView.vue'
import AccessView from '@/views/AccessView.vue'
import WorkOrdersView from '@/features/workorders/WorkOrdersView.vue'
import WorkOrderDetailView from '@/features/workorders/WorkOrderDetailView.vue'
import NotificationsView from '@/views/NotificationsView.vue'
import MeView from '@/views/MeView.vue'
import HelpView from '@/views/HelpView.vue'
import ComplaintEntryView from '@/views/ComplaintEntryView.vue'

import { authSession, api } from '@/shared/http/client'
import { guard } from '@/shared/auth/guard'
import type { Role } from '@/shared/auth/session'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/activate', component: () => import('@/views/ActivateView.vue'), meta: { shell: false } },
    { path: '/login', component: LoginView, meta: { shell: false } },
    { path: '/', component: HomeView, meta: { roles: ['REPAIR_SHOP', 'OWNER'] } },
    { path: '/work-orders', component: WorkOrdersView, meta: { roles: ['REPAIR_SHOP', 'OWNER'] } },
    { path: '/work-orders/:id', component: WorkOrderDetailView, meta: { roles: ['REPAIR_SHOP', 'OWNER'] } },
    { path: '/notifications', component: NotificationsView, meta: { roles: ['REPAIR_SHOP', 'OWNER'] } },
    { path: '/complaints', component: ComplaintEntryView, meta: { roles: ['OWNER'] } },
    { path: '/help', component: HelpView, meta: { roles: ['REPAIR_SHOP', 'OWNER'] } },
    { path: '/guide', component: HelpView, meta: { roles: ['REPAIR_SHOP', 'OWNER'] } },
    { path: '/me', component: MeView, meta: { roles: ['REPAIR_SHOP', 'OWNER'] } },

    { path: '/forbidden', component: AccessView, props: { message: '没有访问权限', retry: false }, meta: { shell: false } },
    { path: '/connection-error', component: AccessView, props: { message: '网络连接失败，请重试', retry: true }, meta: { shell: false } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})
router.beforeEach(to => {
  if(to.path==='/activate'||(to.path==='/login'&&new URLSearchParams(to.hash.slice(1)).has('handoff')))return true
  if (to.path === '/forbidden') return authSession.valid() ? '/' : '/login'
  if (to.path === '/connection-error') return authSession.valid() ? true : '/login'
  return guard(authSession, to.path, (to.meta.roles ?? []) as Role[], api.portalUser)
})
authSession.subscribe(() => { if (!authSession.token && !['/login','/activate'].includes(router.currentRoute.value.path)) void router.replace('/login') })
export default router
