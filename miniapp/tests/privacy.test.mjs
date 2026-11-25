import test, { beforeEach, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdir, readFile } from 'node:fs/promises'
import path from 'node:path'
import { pathToFileURL, fileURLToPath } from 'node:url'
import { build } from 'esbuild'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..'), output = path.join(root, '.task-log/s08-privacy/privacy.mjs')
await mkdir(path.dirname(output), { recursive: true })
await build({ stdin: { contents: `export * as state from './src/services/session';export * as privacy from './src/services/privacy';export {pickOriginalImages} from './src/services/pick-images';`, resolveDir: root }, bundle: true, platform: 'node', format: 'esm', target: 'es2015', external: ['vue'], outfile: output,
  define: { 'import.meta.env': JSON.stringify({}) }, plugins: [{ name: 'wechat-target', setup(builder) { builder.onLoad({ filter: /services[\/\\].*\.ts$/ }, async args => ({ loader: 'ts', contents: (await readFile(args.path, 'utf8')).replace(/\/\/ #ifndef MP-WEIXIN[\s\S]*?\/\/ #endif/g, '').replace(/\/\/ #ifdef H5[\s\S]*?\/\/ #endif/g, '') })) } }],
})
const { state, privacy, pickOriginalImages } = await import(pathToFileURL(output).href)
let config, choices, nativeListener, nativeRequest, resolves, host, deferSetting, contractFails
globalThis.uni = {
  onNeedPrivacyAuthorization(listener) { nativeListener = listener },
  getPrivacySetting(options) { if (deferSetting) deferSetting.options = options; else if (config === null) options.fail({}); else options.success(config) },
  requirePrivacyAuthorize(options) { nativeRequest = options; nativeListener(result => { resolves.push(result); if (result.event === 'agree') options.success({}); if (result.event === 'disagree') options.fail({}) }) },
  openPrivacyContract(options) { contractFails ? options.fail({}) : options.success({}) },
  chooseImage(options) { choices++; options.success({ tempFilePaths: ['synthetic.png'], tempFiles: [{ path: 'synthetic.png', size: 100 }] }) },
  getImageInfo(options) { options.success({ type: 'png', width: 10, height: 10 }) },
}
const tick = () => new Promise(resolve => setImmediate(resolve))
beforeEach(() => { if (host) privacy.privacyHost(host, false); state.clearSession(); host = Symbol('test-host'); privacy.privacyHost(host, true); config = { needAuthorization: true, privacyContractName: '合成平台隐私指引' }; choices = 0; nativeRequest = null; resolves = []; deferSetting = null; contractFails = false })
after(() => { privacy.privacyHost(host, false); state.clearSession() })
test('image choice waits for platform agreement and uses the native agreement button id; simultaneous requests share the gate', async () => {
  const first = pickOriginalImages(1), gate = privacy.ensureImagePrivacy(); assert.equal(choices, 0)
  assert.equal(privacy.privacyPrompt.value.name, '合成平台隐私指引')
  privacy.privacyExposed(); privacy.viewPrivacy(); privacy.agreePrivacy(); await gate
  const result = await first; assert.equal(result[0].mimeType, 'image/png'); assert.equal(choices, 1)
  assert.deepEqual(resolves.find(value => value.event === 'agree'), { event: 'agree', buttonId: privacy.privacyButtonId })
  assert.equal(privacy.privacyPrompt.value, null)
})
test('decline or page hide stops image selection, and late native success cannot revive cancelled work', async () => {
  let result = pickOriginalImages(1), rejected = assert.rejects(result, error => error.code === 'PRIVACY_DECLINED')
  const late = nativeRequest; privacy.cancelPrivacy('PRIVACY_DECLINED'); await rejected; late.success({}); await tick(); assert.equal(choices, 0)
  result = pickOriginalImages(1); rejected = assert.rejects(result); privacy.privacyHost(host, false); await rejected
  assert.equal(choices, 0); assert.equal(privacy.privacyPrompt.value, null)
})
test('platform already-approved state skips custom prompt, but unreadable or missing policy never grants permission', async () => {
  config.needAuthorization = false; await pickOriginalImages(1); assert.equal(choices, 1); assert.equal(nativeRequest, null)
  config = { needAuthorization: true, privacyContractName: '合成新指引' }
  const result = pickOriginalImages(1), rejected = assert.rejects(result)
  contractFails = true; privacy.viewPrivacy(); privacy.agreePrivacy(); assert.equal(choices, 1); assert.equal(privacy.privacyPrompt.value.canAgree, false)
  privacy.cancelPrivacy('PRIVACY_DECLINED'); await rejected
  config = { needAuthorization: false, privacyContractName: '' }; await assert.rejects(pickOriginalImages(1), error => error.code === 'PRIVACY_UNCONFIGURED'); assert.equal(choices, 1)
})
test('session changes cancel a pending settings check; callback failure cannot bypass the gate or break cleanup', async () => {
  deferSetting = {}; const result = pickOriginalImages(1), rejected = assert.rejects(result)
  state.clearSession(); await rejected; deferSetting.options.success(config); await tick(); assert.equal(choices, 0)
  deferSetting = null; config = null; await assert.rejects(pickOriginalImages(1), error => error.code === 'PRIVACY_UNAVAILABLE')
  config = { needAuthorization: true, privacyContractName: '合成平台隐私指引' }; const next = pickOriginalImages(1), denied = assert.rejects(next)
  nativeListener(() => { throw new Error('closed native prompt') }); assert.doesNotThrow(() => privacy.cancelPrivacy()); await denied; assert.equal(choices, 0)
})
test('a policy-view callback from an old account cannot alter the new privacy prompt', async () => {
  const original = uni.openPrivacyContract; let old
  uni.openPrivacyContract = options => { old = options }
  try {
    const first = pickOriginalImages(1), cancelled = assert.rejects(first); privacy.viewPrivacy(); state.clearSession(); await cancelled
    const next = pickOriginalImages(1), rejected = assert.rejects(next); assert.equal(privacy.privacyPrompt.value.canAgree, true)
    old.fail({}); assert.equal(privacy.privacyPrompt.value.canAgree, true); privacy.cancelPrivacy(); await rejected
    assert.equal(choices, 0)
  } finally { uni.openPrivacyContract = original }
})
