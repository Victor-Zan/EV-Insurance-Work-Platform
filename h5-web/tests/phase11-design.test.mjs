import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { actionFor } from '../src/features/workorders/work-orders.ts'

const source = path => readFileSync(new URL(path, import.meta.url), 'utf8')

test('phase 11 shell exposes role-specific H5 navigation and dedicated pages', () => {
  const shell=source('../src/shared/ui/H5Shell.vue'),router=source('../src/router/index.ts'),app=source('../src/App.vue')
  assert.match(shell,/维修网点/);assert.match(shell,/车主/);assert.match(shell,/safe-area-inset-bottom/)
  assert.match(shell,/任务/);assert.match(shell,/案件/);assert.match(shell,/通知/);assert.match(shell,/投诉/);assert.match(shell,/我的/)
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
  assert.match(detail,/\{key:'photos',label:'维修照片'\}/);assert.match(detail,/\{key:'receipt',label:'收车评价'\}/)
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
