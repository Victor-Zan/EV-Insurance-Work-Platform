import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse } from '@vue/compiler-sfc'
import { compile } from '@vue/compiler-dom'
import * as Vue from 'vue'

// Exercise the real App template and Vue's transition lifecycle without a browser.
function mountApp({ manualLeave = false, loggedIn = true } = {}) {
  const node = (type, text = '') => ({ type, text, children: [], parent: null })
  const renderer = Vue.createRenderer({
    createElement: type => node(type), createText: text => node('text', text),
    createComment: text => node('comment', text),
    setText: (target, text) => { target.text = text },
    setElementText: (target, text) => { target.text = text; target.children = [] },
    parentNode: target => target.parent,
    nextSibling: target => target.parent?.children[target.parent.children.indexOf(target) + 1] ?? null,
    patchProp() {},
    insert(target, parent, anchor = null) {
      if (target.parent) target.parent.children.splice(target.parent.children.indexOf(target), 1)
      const index = anchor ? parent.children.indexOf(anchor) : -1
      parent.children.splice(index < 0 ? parent.children.length : index, 0, target)
      target.parent = parent
    },
    remove(target) {
      if (target.parent) target.parent.children.splice(target.parent.children.indexOf(target), 1)
      target.parent = null
    },
  })
  const route = Vue.reactive({ path: '/', query: {} })
  const page = Vue.shallowRef(() => Vue.h('main', '工作台'))
  const source = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
  const { descriptor } = parse(source)
  const { code } = compile(descriptor.template.content, { mode: 'function', prefixIdentifiers: true })
  const pendingLeaves = []
  const Transition = {
    props: ['mode'],
    setup: (props, { slots }) => () => Vue.h(Vue.BaseTransition, {
      mode: props.mode, onLeave: (_, done) => manualLeave ? pendingLeaves.push(done) : queueMicrotask(done),
    }, slots),
  }
  const render = new Function('Vue', code)({ ...Vue, Transition })
  const warnings = []
  const app = renderer.createApp({
    components: {
      RouterView: { setup: (_, { slots }) => () => slots.default({ Component: page.value }) },
      AppShell: { setup: (_, { slots }) => () => Vue.h('section', slots.default()) },
    },
    setup: () => ({ route, currentUser: loggedIn ? {} : null }),
    render,
  })
  app.config.warnHandler = warning => warnings.push(warning)
  const root = node('root')
  app.mount(root)
  function textContent(target) {
    return (target.type === 'comment' ? '' : target.text) + target.children.map(textContent).join('')
  }
  return {
    route,
    warnings,
    text: () => textContent(root),
    async navigate(path, component, query = {}) { route.path = path; route.query = query; page.value = component; await Vue.nextTick(); await Vue.nextTick() },
    async finishLeave() { pendingLeaves.splice(0).forEach(done => done()); await Vue.nextTick() },
    unmount: () => app.unmount(),
  }
}

const casesPage = () => [Vue.h('button', '返回案件队列'), Vue.h('main', '案件队列')]
const pricesPage = () => Vue.h('main', '价格候选')

test('sidebar navigation leaves a fragment-root case page without losing the next page', async () => {
  const app = mountApp()
  try {
    assert.match(app.text(), /工作台/)
    await app.navigate('/work-orders', casesPage)
    assert.match(app.text(), /案件队列/)
    await app.navigate('/pricing', pricesPage)
    assert.match(app.text(), /价格候选/)
    await app.navigate('/work-orders', casesPage)
    assert.match(app.text(), /案件队列/)
    assert.equal(app.warnings.filter(warning => warning.includes('non-element root')).length, 0)
  } finally { app.unmount() }
})

test('rapid route changes settle on the last selected sidebar entry', async () => {
  const app = mountApp({ manualLeave: true })
  try {
    await app.navigate('/work-orders', casesPage)
    await app.finishLeave()
    assert.match(app.text(), /案件队列/)
    await app.navigate('/pricing', pricesPage)
    await app.navigate('/notifications', () => Vue.h('main', '站内通知'))
    await app.finishLeave()
    assert.match(app.text(), /站内通知/)
    assert.doesNotMatch(app.text(), /价格候选/)
  } finally { app.unmount() }
})

test('same-path filter changes preserve page state instead of remounting', async () => {
  const app = mountApp()
  let mounts = 0
  const page = { setup() { mounts++; return () => Vue.h('main', `案件筛选：${app.route.query.status ?? '全部'}`) } }
  try {
    await app.navigate('/work-orders', page)
    await app.navigate('/work-orders', page, { status: 'DRAFT' })
    assert.match(app.text(), /案件筛选：DRAFT/)
    assert.equal(mounts, 1)
  } finally { app.unmount() }
})

test('the layout without a session also supports fragment-root route changes', async () => {
  const app = mountApp({ loggedIn: false })
  try {
    await app.navigate('/login', casesPage)
    await app.navigate('/connection-error', pricesPage)
    assert.match(app.text(), /价格候选/)
  } finally { app.unmount() }
})

test('a route without a resolved component shows loading feedback', async () => {
  const app = mountApp()
  try {
    await app.navigate('/work-orders', undefined)
    assert.match(app.text(), /正在加载页面/)
    await app.navigate('/work-orders', casesPage)
    assert.match(app.text(), /案件队列/)
  } finally { app.unmount() }
})
