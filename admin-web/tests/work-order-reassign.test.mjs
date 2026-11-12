import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'

const { descriptor } = parse(readFileSync(new URL('../src/features/workorders/WorkOrderDetailView.vue', import.meta.url), 'utf8'))
const source = descriptor.scriptSetup.content.replace(/^import .*$/gm, '')
const { outputText } = ts.transpileModule(source + '\nreturn {order,reassignForm,reassignDialog,canSubmitReassign,openReassign,submitReassign}', {
  compilerOptions: { target: ts.ScriptTarget.ES2022 },
})

function setup(t, role = 'ADMIN') {
  let latest = { id: 'synthetic-case', status: 'ARRIVED', dispatchAddressConfirmed: true, currentAssignment: { shopId: 1, assignmentVersion: 4 } }
  const requests = []
  const errors = []
  const api = {
    async detail() { return latest },
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
    useRoute: () => ({ params: { id: latest.id } }),
    useRouter: () => ({}),
    workOrderApi: () => api,
    currentUser: Vue.ref({ roles: [role] }),
    httpClient: { async get() { return { data: { data: {} } } } },
    localDateTimeInput() {},
    ElMessage: { success() {}, error(error) { errors.push(error) } },
  }
  const scope = Vue.effectScope()
  t.after(() => scope.stop())
  const view = scope.run(() => new Function(...Object.keys(deps), outputText)(...Object.values(deps)))
  view.order.value = latest
  return { ...view, requests, errors }
}

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
