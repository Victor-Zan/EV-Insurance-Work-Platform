import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import { decodeStartupImage, prepareStartupImages } from '../src/startup-readiness.ts'

class Image extends EventTarget {
  complete = false
  naturalWidth = 0
  decode = async () => {}
  loaded() { this.complete = true; this.naturalWidth = 320; this.dispatchEvent(new Event('load')) }
}

test('a loaded image is not ready until its decode finishes', async () => {
  const image = new Image()
  let finish
  image.decode = () => new Promise(resolve => { finish = resolve })
  let ready = false
  const pending = decodeStartupImage(image).then(() => { ready = true })
  image.loaded()
  await Promise.resolve()
  assert.equal(ready, false)
  finish()
  await pending
  assert.equal(ready, true)
})

test('cached images still require successful decode; broken cached images fail', async () => {
  const image = new Image()
  image.complete = true
  await assert.rejects(decodeStartupImage(image), /图片不可用/)
  image.naturalWidth = 320
  image.decode = async () => { throw new Error('decode failed') }
  await assert.rejects(decodeStartupImage(image), /decode failed/)
})

test('image progress tracks actual completions and cannot claim page readiness', async () => {
  const first = new Image(), second = new Image(), values = []
  const pending = prepareStartupImages([first, second], value => values.push(value))
  assert.deepEqual(values, [])
  second.loaded()
  await new Promise(resolve => setImmediate(resolve))
  assert.deepEqual(values, [70])
  first.loaded()
  await pending
  assert.equal(values.at(-1), 90)
  assert.ok(values.every(value => value < 100))
})

test('network image failure rejects the readiness gate', async () => {
  const image = new Image(), values = []
  const pending = prepareStartupImages([image], value => values.push(value))
  image.dispatchEvent(new Event('error'))
  await assert.rejects(pending, /图片加载失败/)
  assert.deepEqual(values, [])
})

function watchdog() {
  const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8')
  const script = html.match(/<script>([\s\S]*?)<\/script>/)[1]
  const handlers = {}, parts = {}, screen = { dataset: {}, querySelector: selector => parts[selector] ??= { hidden: true, addEventListener: (event, fn) => { handlers[event] = fn } } }
  let timeout, cleared = false, reloads = 0
  vm.runInNewContext(script, {
    document: { getElementById: () => screen }, window: { addEventListener: (event, fn) => { handlers[event] = fn } },
    setTimeout: (fn, ms) => { assert.equal(ms, 45000); timeout = fn; return 1 },
    clearTimeout: () => { cleared = true }, location: { reload: () => { reloads++ } },
  })
  return { screen, parts, handlers, timeout, cleared: () => cleared, reloads: () => reloads }
}

test('entry download timeout exposes retry even when no application module ran', () => {
  const gate = watchdog()
  gate.timeout()
  assert.equal(gate.screen.dataset.state, 'failed')
  assert.equal(gate.parts.button.hidden, false)
  assert.equal(gate.parts['.startup-title'].textContent, '页面准备未完成')
  gate.handlers.click()
  assert.equal(gate.reloads(), 1)
})

test('successful readiness cancels the watchdog without displaying a false failure', () => {
  const gate = watchdog()
  gate.handlers['startup-ready']()
  assert.equal(gate.cleared(), true)
  assert.equal(gate.screen.dataset.state, undefined)
})
