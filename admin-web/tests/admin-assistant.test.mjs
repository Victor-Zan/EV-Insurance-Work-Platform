import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileScript } from '@vue/compiler-sfc'
import { compile } from '@vue/compiler-dom'
import ts from 'typescript'
import * as Vue from 'vue'
import { readHistory, writeHistory } from '../src/features/assistant/history.ts'

const storage = () => {
  const values = new Map()
  return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value) }
}
const { descriptor } = parse(readFileSync(new URL('../src/features/assistant/AdminAssistant.vue', import.meta.url), 'utf8'))
function fixture(store = storage(), userId = 1, reduce = false, api = {
  status: async () => ({ enabled: true }), chat: async () => ({ ai: true, answer: '有两张工单。' }),
}) {
  let code = ts.transpileModule(compileScript(descriptor, { id: 'assistant-test' }).content, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
  code = code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g, (_, names) => 'const {' + names.replace(/\s+as\s+/g, ':') + '}=__vue;')
  code = code.replace(/import .*from ['"].*['"];?/g, '').replace('export default', 'return')
  code = 'const {readHistory,writeHistory}=__history;const ArrowUp="ArrowUp",ChatLineSquare="ChatLineSquare",EditPen="EditPen",Fold="Fold",Expand="Expand";' + code
  const hooks = {}, timers = new Map(), listeners = new Map()
  let next = 0
  const media = { matches: reduce, addEventListener: (_, fn) => listeners.set('motion', fn), removeEventListener: () => listeners.delete('motion') }
  const document = { hidden: false, addEventListener: (_, fn) => listeners.set('visibility', fn), removeEventListener: () => listeners.delete('visibility') }
  const scope = Vue.effectScope()
  const component = new Function('__vue', '__history', 'localStorage', 'window', 'document', 'setTimeout', 'clearTimeout', 'assistantApi', 'statusLabels', code)(
    { ...Vue, onMounted: fn => { hooks.mount = fn }, onUnmounted: fn => { hooks.unmount = fn } },
    { readHistory, writeHistory }, store, { matchMedia: () => media }, document,
    fn => { timers.set(++next, fn); return next }, id => timers.delete(id), api, { PENDING_DISPATCH: '待派单' },
  )
  const context = scope.run(() => component.setup({ userId }, { expose() {} }))
  hooks.mount()
  return { context, store, timers, listeners, tick() {
    assert.equal(timers.size, 1)
    const [id, callback] = timers.entries().next().value
    timers.delete(id); callback()
  }, reduce(value) { media.matches = value; listeners.get('motion')() }, hide(value) { document.hidden = value; listeners.get('visibility')() }, unmount() { hooks.unmount(); scope.stop() } }
}
function render(template, context) {
  const { code } = compile(template, { mode: 'function', prefixIdentifiers: true, expressionPlugins: ['typescript'] })
  const renderFn = new Function('Vue', ts.transpileModule(code, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText)({ ...Vue, resolveComponent: name => name })
  return renderFn(Vue.proxyRefs(context), [])
}
function elements(vnode) {
  if (!vnode || typeof vnode !== 'object') return []
  if (Array.isArray(vnode)) return vnode.flatMap(elements)
  return [vnode, ...elements(vnode.children)]
}

test('draft history survives reload and is isolated by administrator account', () => {
  const store = storage(), f = fixture(store)
  assert.deepEqual(f.context.history.value.drafts, [])
  f.context.updateDraft('测试草稿：查询本月业绩')
  const id = f.context.history.value.activeId
  f.unmount()
  const restored = fixture(store)
  assert.equal(restored.context.history.value.activeId, id)
  assert.equal(restored.context.draftText.value, '测试草稿：查询本月业绩')
  const other = fixture(store, 2)
  assert.equal(other.context.draftText.value, '')
  assert.deepEqual(other.context.drafts.value, [])
  other.context.updateDraft('另一个账号的草稿')
  assert.equal(readHistory(store, 1).drafts[0].text, '测试草稿：查询本月业绩')
  restored.unmount(); other.unmount()
})

test('new conversations and switching preserve drafts without manufacturing messages', async () => {
  const f = fixture()
  f.context.updateDraft('第一份草稿')
  const first = f.context.history.value.activeId
  f.context.newConversation(); await Vue.nextTick()
  assert.equal(f.context.draftText.value, '')
  assert.equal(f.context.history.value.drafts.length, 1)
  f.context.updateDraft('第二份草稿')
  f.context.selectConversation(first)
  assert.equal(f.context.draftText.value, '第一份草稿')
  assert.equal(readHistory(f.store, 1).activeId, first)
  f.context.updateDraft('')
  assert.equal(f.context.draftText.value, '')
  assert.equal(f.context.history.value.drafts.length, 2)
  f.unmount()
})

test('invalid local history and unavailable storage show an honest failure', () => {
  const f = fixture({ getItem: () => '{broken', setItem() { throw Error('quota') } })
  assert.match(f.context.storageError.value, /无法读取/)
  f.context.updateDraft('仍能在当前页面编辑')
  assert.equal(f.context.draftText.value, '仍能在当前页面编辑')
  assert.match(f.context.storageError.value, /保存失败/)
  f.unmount()
  const store = storage()
  writeHistory(store, 1, { activeId: 'old', drafts: [{ id: 'one', text: '草稿', updatedAt: 1 }] })
  assert.equal(readHistory(store, 1).activeId, null)
  writeHistory(store, 1, { activeId: null, drafts: [{ id: 'one', text: 'a', updatedAt: 1 }, { id: 'one', text: 'b', updatedAt: 2 }] })
  assert.throws(() => readHistory(store, 1), /无法读取/)
})

test('idle animation types, deletes and advances without changing the composer', () => {
  const f = fixture(), target = f.context.examples[0]
  for (let i = 0; i < target.length; i++) f.tick()
  assert.equal(f.context.animatedText.value, target)
  f.tick()
  for (let i = 0; i < target.length; i++) f.tick()
  assert.equal(f.context.animatedText.value, '')
  f.tick(); f.tick()
  assert.equal(f.context.animatedText.value, f.context.examples[1][0])
  assert.equal(f.context.draftText.value, '')
  assert.deepEqual(f.context.history.value.drafts, [])
  f.unmount(); assert.equal(f.timers.size, 0); assert.equal(f.listeners.size, 0)
})

test('focus, drafts, hidden pages and reduced motion stop animation and resume safely', () => {
  const f = fixture()
  f.tick(); f.context.focused.value = true
  assert.equal(f.timers.size, 0)
  f.context.focused.value = false; assert.equal(f.timers.size, 1)
  f.context.updateDraft('暂停动画'); assert.equal(f.timers.size, 0)
  f.context.updateDraft(''); assert.equal(f.timers.size, 1)
  f.hide(true); assert.equal(f.timers.size, 0)
  f.hide(false); assert.equal(f.timers.size, 1)
  f.reduce(true); assert.equal(f.timers.size, 0); assert.equal(f.context.animatedText.value, f.context.examples[0])
  f.reduce(false); assert.equal(f.timers.size, 1)
  f.unmount()
  const reduced = fixture(storage(), 1, true)
  assert.equal(reduced.timers.size, 0); assert.equal(reduced.context.animatedText.value, reduced.context.examples[0])
  reduced.unmount()
})

test('send stays disabled until configured and failed requests preserve the draft', async () => {
  const calls = []
  const f = fixture(storage(), 1, false, {
    status: async () => ({ enabled: false }),
    chat: async message => { calls.push(message); throw new Error('服务不可用') },
  })
  f.context.updateDraft('有多少工单')
  await Vue.nextTick()
  const nodes = elements(render(descriptor.template.content, f.context))
  assert.equal(nodes.find(node => node.props?.['aria-label'] === '发送').props.disabled, true)
  const before = JSON.stringify(f.context.history.value)
  let prevented = false
  await nodes.find(node => node.type === 'form').props.onSubmit({ preventDefault() { prevented = true } })
  assert.ok(prevented); assert.equal(JSON.stringify(f.context.history.value), before)
  assert.deepEqual(calls, [])
  f.context.enabled.value = true
  await f.context.send()
  assert.deepEqual(calls, ['有多少工单'])
  assert.equal(f.context.error.value, '服务不可用')
  assert.equal(f.context.draftText.value, '有多少工单')
  f.unmount()
})

test('a submitted question stores one actual reply in its original conversation', async () => {
  const f = fixture()
  await Vue.nextTick()
  f.context.updateDraft('有多少工单')
  const id = f.context.history.value.activeId
  const first = f.context.send()
  assert.equal(f.context.busy.value, true)
  await f.context.send()
  f.context.newConversation()
  await first
  assert.equal(f.context.turns.value.length, 0)
  f.context.selectConversation(id)
  assert.equal(f.context.draftText.value, '')
  assert.deepEqual(readHistory(f.store, 1).drafts[0].turns, [
    { message: '有多少工单', reply: { ai: true, answer: '有两张工单。' } },
  ])
  f.unmount()
})

test('Enter sends once, preserves IME and Shift+Enter, and shows the actual model after the server event', async () => {
  let finish, reportModel
  const calls = []
  const f = fixture(storage(), 1, false, {
    status: async () => ({ enabled: true }),
    chat: (message, _signal, onModel) => {
      calls.push(message)
      reportModel = onModel
      return new Promise(resolve => { finish = resolve })
    },
  })
  await Vue.nextTick()
  f.context.updateDraft('怎么提交网点报价')
  const nodes = () => elements(render(descriptor.template.content, f.context))
  const textarea = () => nodes().find(node => node.type === 'textarea')
  const press = (options = {}) => {
    let prevented = false
    textarea().props.onKeydown({ key: 'Enter', preventDefault() { prevented = true }, ...options })
    return prevented
  }
  assert.equal(press({ shiftKey: true }), false)
  assert.equal(press({ isComposing: true }), false)
  textarea().props.onCompositionstart()
  assert.equal(press(), false)
  textarea().props.onCompositionend()
  assert.deepEqual(calls, [])
  assert.equal(press(), true)
  assert.equal(press({ repeat: true }), true)
  assert.deepEqual(calls, ['怎么提交网点报价'])
  assert.equal(nodes().find(node => node.props?.role === 'status').children[1].children, '正在查询平台资料…')
  reportModel('glm-4-flash-250414')
  assert.equal(nodes().find(node => node.props?.role === 'status').children[1].children, '正在调用 glm-4-flash-250414 生成回答…')
  finish({ answer: '先上传到店照。', ai: true, model: 'glm-4-flash-250414' })
  await Vue.nextTick()
  assert.equal(f.context.busy.value, false)
  assert.equal(f.context.turns.value[0].reply.model, 'glm-4-flash-250414')
  f.unmount()
})

test('home mounts the assistant only for admins and remounts when identity changes', () => {
  const home = readFileSync(new URL('../src/views/HomeView.vue', import.meta.url), 'utf8')
  const tag = home.match(/<AdminAssistant\b[^>]+\/>/)[0]
  for (const roles of [['CUSTOMER_SERVICE'], ['REPAIR_SHOP'], ['OWNER']]) {
    assert.notEqual(render(tag, { currentUser: { id: 1, roles } }).type, 'AdminAssistant')
  }
  assert.notEqual(render(tag, { currentUser: null }).type, 'AdminAssistant')
  for (const id of [1, 2]) {
    const node = render(tag, { currentUser: { id, roles: ['ADMIN'] } })
    assert.equal(node.type, 'AdminAssistant'); assert.equal(node.key, id); assert.equal(node.props['user-id'], id)
  }
  assert.ok(home.indexOf(tag) < home.indexOf('aria-labelledby="todos-title"'))
})
