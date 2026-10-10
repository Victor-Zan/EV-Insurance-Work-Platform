import { createRouter, createWebHistory } from 'vue-router'
const HomeView = () => import('@/views/HomeView.vue')
const LoginView = () => import('@/views/LoginView.vue')
const AccessView = () => import('@/views/AccessView.vue')
const UsersView = () => import('@/views/UsersView.vue')
const CatalogueView = () => import('@/features/pricing/CatalogueView.vue')
const PartModelsView = () => import('@/features/pricing/PartModelsView.vue')
const PricesView = () => import('@/features/pricing/PricesView.vue')
const PriceEditorView = () => import('@/features/pricing/PriceEditorView.vue')
const PriceHistoryView = () => import('@/features/pricing/PriceHistoryView.vue')
const ImportView = () => import('@/features/pricing/ImportView.vue')
const BatchesView = () => import('@/features/pricing/BatchesView.vue')
const WorkOrdersView = () => import('@/features/workorders/WorkOrdersView.vue')
const WorkOrderDetailView = () => import('@/features/workorders/WorkOrderDetailView.vue')
import { PRICE_READ_ROLES, PRICE_WRITE_ROLES } from '@/features/pricing/prices'
import { authSession, api } from '@/shared/http/client'
import { guard } from '@/shared/auth/guard'
import type { Role } from '@/shared/auth/session'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/login', component: LoginView },
    { path: '/', component: HomeView },
    { path: '/users', component: UsersView, meta: { roles: ['ADMIN'] } },
    { path: '/work-orders', component: WorkOrdersView, meta: { roles: ['ADMIN', 'CUSTOMER_SERVICE'] } },
    { path: '/work-orders/:id', component: WorkOrderDetailView, meta: { roles: ['ADMIN', 'CUSTOMER_SERVICE'] } },
    { path: '/pricing/catalogue/:kind(brands|models|parts|aliases|sources)', component: CatalogueView, meta: { roles: ['ADMIN', 'CUSTOMER_SERVICE'] } },
    { path: '/pricing/part-models', component: PartModelsView, meta: { roles: ['ADMIN', 'CUSTOMER_SERVICE'] } },
    { path: '/pricing', component: PricesView, meta: { roles: PRICE_READ_ROLES } },
    { path: '/pricing/new', component: PriceEditorView, meta: { roles: PRICE_WRITE_ROLES } },
    { path: '/pricing/records/:recordId/new', component: PriceEditorView, meta: { roles: PRICE_WRITE_ROLES } },
    { path: '/pricing/records/:recordId/history', component: PriceHistoryView, meta: { roles: PRICE_READ_ROLES } },
    { path: '/pricing/import', component: ImportView, meta: { roles: PRICE_WRITE_ROLES } },
    { path: '/pricing/batches', component: BatchesView, meta: { roles: PRICE_WRITE_ROLES } },
    { path: '/pricing/batches/:batchId', component: BatchesView, meta: { roles: PRICE_WRITE_ROLES } },
    { path: '/forbidden', component: AccessView, props: { message: '没有访问权限', retry: false } },
    { path: '/connection-error', component: AccessView, props: { message: '网络连接失败，请重试', retry: true } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})
router.beforeEach(to => {
  if (to.path === '/forbidden' || to.path === '/connection-error') return authSession.valid() ? true : '/login'
  return guard(authSession, to.path, (to.meta.roles ?? []) as Role[], api.portalUser)
})
authSession.subscribe(() => { if (!authSession.token && router.currentRoute.value.path !== '/login') void router.replace('/login') })
export default router
