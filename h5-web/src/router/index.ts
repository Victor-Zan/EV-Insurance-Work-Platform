import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '@/views/HomeView.vue'
import LoginView from '@/views/LoginView.vue'
import AccessView from '@/views/AccessView.vue'
import WorkOrdersView from '@/features/workorders/WorkOrdersView.vue'
import WorkOrderDetailView from '@/features/workorders/WorkOrderDetailView.vue'

import { authSession, api } from '@/shared/http/client'
import { guard } from '@/shared/auth/guard'
import type { Role } from '@/shared/auth/session'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/login', component: LoginView },
    { path: '/', component: HomeView },
    { path: '/work-orders', component: WorkOrdersView, meta: { roles: ['REPAIR_SHOP', 'OWNER'] } },
    { path: '/work-orders/:id', component: WorkOrderDetailView, meta: { roles: ['REPAIR_SHOP', 'OWNER'] } },

    { path: '/forbidden', component: AccessView, props: { message: '没有访问权限', retry: false } },
    { path: '/connection-error', component: AccessView, props: { message: '网络连接失败，请重试', retry: true } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})
router.beforeEach(to => {
  if (to.path === '/forbidden') return authSession.valid() ? '/' : '/login'
  if (to.path === '/connection-error') return authSession.valid() ? true : '/login'
  return guard(authSession, to.path, (to.meta.roles ?? []) as Role[], api.portalUser)
})
authSession.subscribe(() => { if (!authSession.token && router.currentRoute.value.path !== '/login') void router.replace('/login') })
export default router
