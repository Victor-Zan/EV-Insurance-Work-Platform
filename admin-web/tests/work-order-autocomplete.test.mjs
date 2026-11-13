import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse } from '@vue/compiler-sfc'
import ts from 'typescript'
import { ref } from 'vue'

function setupComponent(name, dependencies, exposed) {
  const source = readFileSync(new URL(`../src/shared/ui/${name}.vue`, import.meta.url), 'utf8')
  const script = parse(source).descriptor.scriptSetup.content.replace(/^import .*$/gm, '')
  const { outputText } = ts.transpileModule(`${script}\nreturn { ${exposed} }`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  })
  return new Function(...Object.keys(dependencies), outputText)(...Object.values(dependencies))
}

test('autocomplete waits for two characters, ignores stale matches, and emits only on selection', async () => {
  const draft = { id: 'draft', status: 'DRAFT' }
  const order = { id: 'case', businessNo: 'EVR-20261026-000007', claimNo: 'C-1026', status: 'COMPLETED' }
  const pending = []
  const selected = []
  const view = setupComponent('WorkOrderAutocomplete', {
    ref, defineProps: () => ({ formalOnly: true }), defineEmits: () => (...args) => selected.push(args),
    httpClient: {}, workOrderApi: () => ({
      list: (...args) => new Promise(resolve => pending.push({ args, resolve })),
    }),
  }, 'input, invalidate, suggest, select')
  let options
  const show = items => { options = items }
  await view.suggest('1', show)
  assert.deepEqual(options, [])
  assert.equal(pending.length, 0)

  view.input.value = '10'
  const stale = view.suggest('10', show)
  view.input.value = '1026'
  view.invalidate()
  const current = view.suggest('1026', show)
  assert.deepEqual(pending[1].args, [1, '', '1026', false])
  pending[1].resolve({ records: [draft, order] })
  await current
  pending[0].resolve({ records: [] })
  await stale
  assert.deepEqual(options, [{ value: order.businessNo, order }])
  assert.deepEqual(selected, [])
  view.select(options[0])
  assert.deepEqual(selected, [['select', order]])

  const matches = []
  const picker = setupComponent('CasePicker', {
    ref, defineProps: () => ({}), defineEmits: () => (...args) => matches.push(args),
  }, 'select, selectedLabel')
  picker.select(order)
  assert.deepEqual(matches, [['update:modelValue', order.id]])
  assert.equal(picker.selectedLabel.value, `${order.businessNo} · ${order.claimNo}`)
})
