import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse } from '@vue/compiler-sfc'
import ts from 'typescript'
import { effectScope, nextTick, ref, watch } from 'vue'

const { descriptor } = parse(readFileSync(new URL('../src/views/FundsWorkbenchView.vue', import.meta.url), 'utf8'))
const script = descriptor.scriptSetup.content.replace(/^import .*$/gm, '')
const { outputText } = ts.transpileModule(script + '\nreturn { query, status, receive, pay, page, rows, busy, load }', {
  compilerOptions: { target: ts.ScriptTarget.ES2022 },
})

test('funds filters apply automatically and stale responses cannot overwrite the current page', async t => {
  const scope = effectScope()
  t.after(() => scope.stop())
  const requests = []
  const client = {
    get(_path, { params }) {
      return new Promise(resolve => requests.push({
        params,
        respond(caseId) {
          resolve({ data: { data: { records: [{ caseId }], total: 1 } } })
        },
      }))
    },
  }
  const view = scope.run(() => new Function('ref', 'watch', 'onBeforeUnmount', 'httpClient', outputText)(
    ref, watch, () => {}, client,
  ))
  view.page.value = 2
  void view.load()
  view.status.value = 'COMPLETED'
  await nextTick()
  assert.equal(requests.length, 3)
  assert.equal(requests[2].params.page, 1)
  assert.equal(requests[2].params.status, 'COMPLETED')
  view.query.value = 'EVR'
  await nextTick()
  view.query.value = 'EVR-20261029'
  await nextTick()
  assert.equal(requests.length, 3)
  requests[2].respond('outdated')
  await nextTick()
  assert.deepEqual(view.rows.value, [])
  await new Promise(resolve => setTimeout(resolve, 300))
  assert.equal(requests.length, 4)
  assert.equal(requests[3].params.query, 'EVR-20261029')
  requests[3].respond('matching')
  await nextTick()
  assert.equal(view.rows.value[0].caseId, 'matching')
  assert.equal(view.busy.value, false)
  view.query.value = ''
  view.receive.value = 'SETTLED'
  view.pay.value = 'SETTLED'
  await nextTick()
  assert.equal(requests.length, 5)
  assert.equal(requests[4].params.query, undefined)
  assert.equal(requests[4].params.receivableStatus, 'SETTLED')
  assert.equal(requests[4].params.payableStatus, 'SETTLED')
})
