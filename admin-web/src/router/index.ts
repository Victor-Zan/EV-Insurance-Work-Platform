import { createRouter, createWebHistory } from 'vue-router'
const HomeView = () => import('@/views/HomeView.vue')
const LoginView = () => import('@/views/LoginView.vue')
const AccessView = () => import('@/views/AccessView.vue')
const UsersView = () => import('@/views/UsersView.vue')
const RegionsView = () => import('@/views/RegionsView.vue')
const ShopsView = () => import('@/views/ShopsView.vue')
const AuditView = () => import('@/views/AuditView.vue')
const WorkOrderSettingsView = () => import('@/views/WorkOrderSettingsView.vue')
const NotificationsView = () => import('@/features/notification/NotificationPanel.vue')
const FundsWorkbenchView = () => import('@/views/FundsWorkbenchView.vue')
const ComplaintsWorkbenchView = () => import('@/views/ComplaintsWorkbenchView.vue')
const FundsImportsView = () => import('@/views/FundsImportsView.vue')
const WorkOrdersView = () => import('@/features/workorders/WorkOrdersView.vue')
const HistoricalCasesView = () => import('@/features/historical/HistoricalCasesView.vue')
const WorkOrderDetailView = () => import('@/features/workorders/WorkOrderDetailView.vue')
import { PRICE_READ_ROLES } from '@/features/pricing/prices'
import { authSession, api } from '@/shared/http/client'
import { guard } from '@/shared/auth/guard'
import type { Role } from '@/shared/auth/session'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/activate', component: () => import('@/views/ActivateView.vue'), meta: { shell: false } },
    { path: '/login', component: LoginView },
    { path: '/', component: HomeView },
    { path: '/users', redirect: '/admin/users', meta: { roles: ['ADMIN'] } },
    { path: '/admin/users', component: UsersView, meta: { roles: ['ADMIN'] } },
    { path: '/admin/regions', component: RegionsView, meta: { roles: ['ADMIN'] } },
    { path: '/admin/shops', component: ShopsView, meta: { roles: ['ADMIN'] } },
    { path: '/admin/audit-logs', component: AuditView, meta: { roles: ['ADMIN'] } },
    { path: '/admin/settings/work-orders', component: WorkOrderSettingsView, meta: { roles: ['ADMIN'] } },
    { path: '/notifications', component: NotificationsView, meta: { roles: ['ADMIN', 'CUSTOMER_SERVICE'] } },
    { path: '/funds/workbench', component: FundsWorkbenchView, meta: { roles: ['ADMIN', 'CUSTOMER_SERVICE'] } },
    { path: '/complaints', component: ComplaintsWorkbenchView, meta: { roles: ['ADMIN', 'CUSTOMER_SERVICE'] } },
    { path: '/funds/imports', component: FundsImportsView, meta: { roles: ['CUSTOMER_SERVICE'] } },
    { path: '/work-orders', component: WorkOrdersView, meta: { roles: ['ADMIN', 'CUSTOMER_SERVICE'] } },
    { path: '/ocr/intake', component: () => import('@/features/materials/OcrIntakeView.vue'), meta: { roles: ['ADMIN', 'CUSTOMER_SERVICE'] } },
    { path: '/historical-cases', component: HistoricalCasesView, meta: { roles: ['ADMIN', 'CUSTOMER_SERVICE'] } },
    { path: '/work-orders/:id', component: WorkOrderDetailView, meta: { roles: ['ADMIN', 'CUSTOMER_SERVICE'] } },
    { path: '/pricing', component: () => import('@/features/pricing/ReferencePricesView.vue'), meta: { roles: PRICE_READ_ROLES } },
    { path: '/pricing/:retiredPath(.*)+', redirect: '/pricing' },
    { path: '/forbidden', component: AccessView, props: { message: '没有访问权限', retry: false } },
    { path: '/connection-error', component: AccessView, props: { message: '网络连接失败，请重试', retry: true } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})
router.beforeEach(to => {
  if(to.path==='/activate'||(to.path==='/login'&&new URLSearchParams(to.hash.slice(1)).has('handoff')))return true
  if (to.path === '/forbidden' || to.path === '/connection-error') return authSession.valid() ? true : '/login'
  return guard(authSession, to.path, (to.meta.roles ?? []) as Role[], api.portalUser)
})
authSession.subscribe(() => { if (!authSession.token && !['/login','/activate'].includes(router.currentRoute.value.path)) void router.replace('/login') })
export default router
