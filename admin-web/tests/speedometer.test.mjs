import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileScript } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'

const source = readFileSync(new URL('../src/shared/ui/WorkbenchSpeedometer.vue', import.meta.url), 'utf8')
const { descriptor } = parse(source)
function fixture(reduce = false) {
 let code = ts.transpileModule(compileScript(descriptor, { id: 'speedometer-test' }).content, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
 code = code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g, (_, names) => 'const {' + names.replace(/\s+as\s+/g, ':') + '}=__vue;')
 code = code.replace(/import artwork from ['"][^'"]+['"];?/, 'const artwork="speedometer.png";').replace('export default', 'return')
 const hooks = {}, frames = new Map(), listeners = new Set()
 let nextFrame = 0
 const media = { matches: reduce, addEventListener(_, fn) { listeners.add(fn) }, removeEventListener(_, fn) { listeners.delete(fn) } }
 const component = new Function('__vue', 'window', 'requestAnimationFrame', 'cancelAnimationFrame', code)(
  { ...Vue, onMounted(fn) { hooks.mount = fn }, onUnmounted(fn) { hooks.unmount = fn } },
  { matchMedia: () => media }, fn => { frames.set(++nextFrame, fn); return nextFrame }, id => frames.delete(id),
 )
 const context = component.setup({}, { expose() {} })
 hooks.mount()
 return { context, frames, listeners, unmount: hooks.unmount, tick(time) {
  assert.equal(frames.size, 1)
  const [id, callback] = frames.entries().next().value
  frames.delete(id); callback(time)
 }, reduce(value) { media.matches = value; listeners.forEach(fn => fn()) } }
}

test('speedometer digits and arc rise and fall together through a complete loop', () => {
 const f = fixture()
 for (const [time, speed, lit] of [[0, 0, 0], [2500, 30, 8], [5000, 60, 16], [7500, 30, 8], [10000, 0, 0]]) {
  f.tick(time)
  assert.equal(f.context.speed.value, speed)
  assert.equal(f.context.activeSegments.value, lit)
  assert.equal(f.context.digits.value.join(''), String(speed).padStart(2, '0'))
 }
 f.unmount()
 assert.equal(f.frames.size, 0)
 assert.equal(f.listeners.size, 0)
})

test('reduced motion keeps a matching static reading and cancels animation when changed', () => {
 const f = fixture(true)
 assert.equal(f.context.speed.value, 30)
 assert.equal(f.context.activeSegments.value, 8)
 assert.equal(f.frames.size, 0)
 f.reduce(false); assert.equal(f.frames.size, 1)
 f.tick(0); assert.equal(f.context.speed.value, 0)
 f.reduce(true)
 assert.equal(f.frames.size, 0)
 assert.equal(f.context.speed.value, 30)
 assert.equal(f.context.activeSegments.value, 8)
 f.unmount(); assert.equal(f.listeners.size, 0)
})
