import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'

const { descriptor } = parse(readFileSync(new URL('../src/features/workorders/WorkOrderDetailView.vue', import.meta.url), 'utf8'))
const source = descriptor.scriptSetup.content.replace(/^import .*$/gm, '')
const { outputText } = ts.transpileModule(source + '\nreturn {order,reassignForm,reassignDialog,canSubmitReassign,openReassign,submitReassign,initialLoad,loadError,activeSection,managementUnavailable,hasManagementActions,deletionDrawer,processedDeletionLink}', {
  compilerOptions: { target: ts.ScriptTarget.ES2022 },
})

function setup(t, role = 'ADMIN', { status = 'ARRIVED', section, reviewDeletion, managementMissing, detailMissing, deletion = {} } = {}) {
  let latest = { id: 'synthetic-case', status, dispatchAddressConfirmed: true, currentAssignment: { shopId: 1, assignmentVersion: 4 } }
  const requests = []
  const errors = []
  const route = Vue.reactive({ params: { id: latest.id }, path: '/work-orders/synthetic-case', query: { section, reviewDeletion } })
  const api = {
    async detail() { if (detailMissing) throw new Error('工单不存在'); return latest },
    async history() { return { records: [], total: 0 } },
    async reassign(id, value) {
      requests.push({ id, value })
      latest = { ...latest, status: 'PENDING_ACCEPTANCE', currentAssignment: { shopId: value.shopId, assignmentVersion: 5 } }
      return latest
    },
  }
  const deps = {
    ref: Vue.ref, reactive: Vue.reactive, computed: Vue.computed, watch: Vue.watch, nextTick: Vue.nextTick,
    onMounted() {},
    onUnmounted() {},
    window: { innerWidth: 1440, scrollTo() {} },
    useRoute: () => route,
    useRouter: () => ({ async replace({ query }) { route.query = query } }),
    workOrderApi: () => api,
    currentUser: Vue.ref({ roles: [role] }),
    httpClient: { async get(path) {
      if (managementMissing && path.includes('/management')) throw new Error('记录不存在')
      if (path.endsWith('/management/deletion')) return { data: { data: deletion } }
      return { data: { data: {} } }
    } },
    safeCaseSection: (requested) => requested,
    localDateTimeInput() {},
    ElMessage: { success() {}, error(error) { errors.push(error) } },
  }
  const scope = Vue.effectScope()
  t.after(() => scope.stop())
  const view = scope.run(() => new Function(...Object.keys(deps), outputText)(...Object.values(deps)))
  view.order.value = latest
  return { ...view, requests, errors, route }
}

test('an administrator opens only the request identified by the approval link', async t => {
  const requestedAt = '2026-11-13T03:00:00Z'
  const deletion = { canReview: true, request: { at: requestedAt } }
  const current = setup(t, 'ADMIN', { reviewDeletion: requestedAt, deletion })
  const old = setup(t, 'ADMIN', { reviewDeletion: '2026-11-13T02:00:00Z', deletion })
  const staff = setup(t, 'CUSTOMER_SERVICE', { reviewDeletion: requestedAt, deletion })
  let opened = 0
  for (const view of [current, old, staff]) view.deletionDrawer.value = { show() { opened++ } }
  await Promise.all([current.initialLoad(), old.initialLoad(), staff.initialLoad()])
  await Vue.nextTick()
  assert.equal(opened, 1)
  assert.equal(current.route.query.reviewDeletion, undefined)
  assert.equal(old.processedDeletionLink.value, true)
  assert.equal(staff.processedDeletionLink.value, false)
})

test('administrator can confirm reassignment directly with both reasons and the current assignment version', async t => {
  const view = setup(t)
  view.openReassign()
  assert.equal(view.reassignDialog.value, true)
  Object.assign(view.reassignForm, { shopId: 2, reason: '缺少配件', transferDescription: ' 车辆仍在原网点，已安排交接。 ' })
  assert.equal(view.canSubmitReassign.value, true)
  await view.submitReassign()
  assert.deepEqual(view.requests, [{ id: 'synthetic-case', value: {
    shopId: 2, assignmentVersion: 4, reason: '缺少配件', vehicleNotArrivedConfirmed: false,
    transferDescription: '车辆仍在原网点，已安排交接。', confirmPossibleDuplicate: false,
  } }])
  assert.deepEqual(view.errors, [])
  assert.equal(view.reassignDialog.value, false)
  assert.equal(view.order.value.status, 'PENDING_ACCEPTANCE')
})

test('same shop, missing handover, stale assignments, and repair start cannot submit the drawer', async t => {
  const view = setup(t)
  view.openReassign()
  Object.assign(view.reassignForm, { shopId: 1, reason: '缺少配件', transferDescription: '已安排交接' })
  await view.submitReassign()
  Object.assign(view.reassignForm, { shopId: 2, transferDescription: ' ' })
  await view.submitReassign()
  view.reassignForm.transferDescription = '已安排交接'
  view.order.value.currentAssignment.assignmentVersion = 5
  await view.submitReassign()
  view.order.value.currentAssignment.assignmentVersion = 4
  view.order.value.status = 'REPAIRING'
  await view.submitReassign()
  assert.deepEqual(view.requests, [])
})

test('non-administrator cannot open the existing after-arrival reassignment operation', t => {
  const view = setup(t, 'CUSTOMER_SERVICE')
  view.openReassign()
  assert.equal(view.reassignDialog.value, false)
})

test('a missing management endpoint does not hide an existing completed case or its funds section', async t => {
  const view = setup(t, 'ADMIN', { status: 'COMPLETED', section: 'funds', managementMissing: true })
  await view.initialLoad()
  assert.equal(view.loadError.value, '')
  assert.equal(view.order.value.status, 'COMPLETED')
  assert.equal(view.activeSection.value, 'funds')
  assert.equal(view.managementUnavailable.value, true)
  assert.equal(view.hasManagementActions.value, false)
})

test('management menu counts only available operations, including a deletion request', async t => {
  const completed = setup(t, 'ADMIN', { status: 'COMPLETED' })
  const active = setup(t, 'ADMIN', { status: 'PENDING_ACCEPTANCE' })
  const deletionOnly = setup(t, 'ADMIN', { status: 'CANCELLED', deletion: { canRequest: true } })
  await Promise.all([completed.initialLoad(), active.initialLoad(), deletionOnly.initialLoad()])
  assert.equal(completed.managementUnavailable.value, false)
  assert.equal(completed.hasManagementActions.value, false)
  assert.equal(active.hasManagementActions.value, true)
  assert.equal(deletionOnly.hasManagementActions.value, true)
})

test('a missing work order still shows the detail error', async t => {
  const view = setup(t, 'ADMIN', { section: 'funds', detailMissing: true })
  await view.initialLoad()
  assert.equal(view.loadError.value, '工单不存在')
  assert.equal(view.order.value, undefined)
})
