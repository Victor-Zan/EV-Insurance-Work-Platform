import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { actionFor } from '../src/features/workorders/work-orders.ts'

const source = path => readFileSync(new URL(path, import.meta.url), 'utf8')

test('phase 11 shell exposes role-specific H5 navigation and dedicated pages', () => {
  const shell=source('../src/shared/ui/H5Shell.vue'),router=source('../src/router/index.ts'),app=source('../src/App.vue')
  assert.match(shell,/维修网点/);assert.match(shell,/车主/);assert.match(shell,/safe-area-inset-bottom/)
  assert.match(shell,/任务/);assert.match(shell,/案件/);assert.match(shell,/通知/);assert.doesNotMatch(shell,/label:'投诉'/);assert.match(shell,/glyph:'bell'/);assert.match(shell,/我的/)
  assert.match(router,/path: '\/notifications'/);assert.match(router,/path: '\/complaints'.*roles: \['OWNER'\]/);assert.match(router,/path: '\/me'/)
  assert.match(app,/ConfigProvider/);assert.match(app,/H5Shell/)
})

test('H5 case lists use real service pagination and role-specific next actions', () => {
  const api=source('../src/features/workorders/work-orders.ts'),view=source('../src/features/workorders/WorkOrdersView.vue')
  assert.match(api,/list:\(page=1,size=20,query=''\)/);assert.match(api,/params:\{page,size/)
  assert.doesNotMatch(api,/size:50/);assert.match(view,/List v-model:loading/);assert.match(view,/PullRefresh/)
  assert.deepEqual(actionFor('PENDING_ACCEPTANCE','REPAIR_SHOP'),{label:'处理接单',section:'overview'})
  assert.deepEqual(actionFor('WAITING_OWNER_CONFIRMATION','OWNER'),{label:'确认收车',section:'receipt'})
})

test('owner and repair shop case sections keep forbidden fields out of their rendered routes', () => {
  const detail=source('../src/features/workorders/WorkOrderDetailView.vue'),quote=source('../src/features/quotation/QuotationPanel.vue')
  assert.match(detail,/QuotationPanel v-if="isShop&&activeSection==='quotation'"/)
  assert.match(detail,/FundsPanel v-if="isShop&&activeSection==='settlement'"/)
  assert.match(detail,/\{key:'photos',label:'维修照片'\}/);assert.match(detail,/WAITING_INSURER_PAYMENT.*WAITING_SHOP_SETTLEMENT.*COMPLETED/);assert.match(detail,/'评价':'收车确认'/)
  assert.doesNotMatch(detail,/车主.*(?:报价|加价|核损金额|利润|结算)/)
  assert.match(quote,/v-if="staff"/);assert.match(quote,/v-if="shop&&editable"/)
})

test('uploads retain the same idempotency key for manual weak-network retry', () => {
  const materials=source('../src/features/materials/MaterialsPanel.vue'),styles=source('../src/styles.css')
  assert.match(materials,/uploadKey\.value=file\?crypto\.randomUUID\(\):''/)
  assert.match(materials,/'Idempotency-Key':uploadKey\.value/)
  assert.match(materials,/网络中断不会自动重放上传/)
  assert.match(styles,/prefers-reduced-motion:reduce/);assert.match(styles,/min-height:100dvh/)
})

test('mobile refinement uses the charging scene, glass hierarchy and reduced-motion route transitions', () => {
  const login=source('../src/views/LoginView.vue'),shell=source('../src/shared/ui/H5Shell.vue'),app=source('../src/App.vue'),styles=source('../src/styles.css')
  assert.match(login,/e-scooter-charging-login\.webp/)
  assert.match(source('../src/views/HomeView.vue'),/repair-shop-service-hero\.webp/)
  assert.match(source('../src/views/HomeView.vue'),/owner-progress-hero\.webp/)
  assert.match(login,/backdrop-filter:blur\(18px\)/)
  assert.match(shell,/backdrop-filter:blur\(16px\)/)
  assert.match(app,/Transition name="mobile-page-shift"/)
  assert.match(styles,/mobile-page-shift-enter-active/)
  assert.match(styles,/prefers-reduced-motion:reduce/)
})


test('mobile help navigation returns to profile and notification icon exists in installed Vant',()=>{
  const shell=source('../src/shared/ui/H5Shell.vue'),me=source('../src/views/MeView.vue'),help=source('../src/views/HelpView.vue')
  assert.match(shell,/if\(path!==route.path\)/)
  assert.match(shell,/@click="navigate\(tab.to\)"/)
  assert.match(me,/title="帮助中心".*to="\/help"/)
  assert.match(me,/title="使用说明".*to="\/guide"/)
  assert.match(help,/案件正式提交后/)
  const css=source('../node_modules/vant/lib/icon/index.css')
  assert.match(css,/\.van-icon-volume-o:before\{content:/)
  assert.doesNotMatch(shell,/bell-o/)
})
