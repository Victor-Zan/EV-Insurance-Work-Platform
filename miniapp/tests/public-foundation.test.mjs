import test, { beforeEach, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdir, readFile } from 'node:fs/promises'
import path from 'node:path'
import { pathToFileURL, fileURLToPath } from 'node:url'
import { build } from 'esbuild'

// Compile the actual public services with the existing Vite toolchain. The transport is synthetic;
// these checks do not authenticate a real WeChat account or call a business server.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const output = path.join(root, '.task-log/s04-tests/services.mjs')
await mkdir(path.dirname(output), { recursive: true })
await build({ stdin: { contents: `export * as state from './src/services/session'; export * as client from './src/services/client'; export * as auth from './src/services/auth'; export * as upload from './src/services/upload'; export * as files from './src/services/files';`, resolveDir: root },
  bundle: true, platform: 'node', format: 'esm', target: 'es2020', external: ['vue'], outfile: output,
  define: { 'import.meta.env': JSON.stringify({ VITE_API_BASE_URL: 'https://api.example.test/api/v1' }) },
  plugins: [{ name: 'wechat-test-target', setup(builder) {
    builder.onLoad({ filter: /services[\/\\].*\.ts$/ }, async args => ({ loader: 'ts', contents: (await readFile(args.path, 'utf8'))
      .replace(/\/\/ #ifndef MP-WEIXIN[\s\S]*?\/\/ #endif/g, '')
      .replace(/\/\/ #ifdef H5[\s\S]*?\/\/ #endif/g, '') }))
  } }],
})
const { state, client, auth, upload, files } = await import(pathToFileURL(output).href)
const flush = () => new Promise(resolve => setImmediate(resolve))
const caseId = '00000000-0000-4000-8000-000000000001'
const fileId = '00000000-0000-4000-8000-000000000002'
let requests, uploads, downloads, deleted, previews, codeNumber
function transport(list, options) {
  const entry = { options, aborted: false }
  list.push(entry)
  return { abort() { entry.aborted = true; options.fail?.({}) }, onProgressUpdate(callback) { entry.progress = callback } }
}
function loginResult(role = 'OWNER', id = 1, shopId = null) {
  return { accessToken: `synthetic-test-session-${id}`, expiresAt: new Date(Date.now() + 60000).toISOString(),
    user: { id, username: 'synthetic', displayName: '合成账号', roles: [role], shopId, activated: true, enabled: true } }
}
function signIn(role = 'OWNER', id = 1, shopId = null) { state.acceptSession(loginResult(role, id, shopId), state.sessionGeneration()) }
function respond(entry, status, data, header = {}) { entry.options.success({ statusCode: status, data, header }) }
beforeEach(() => {
  requests = []; uploads = []; downloads = []; deleted = []; previews = []; codeNumber = 0
  globalThis.uni = {
    request: options => transport(requests, options), uploadFile: options => transport(uploads, options), downloadFile: options => transport(downloads, options),
    login: options => queueMicrotask(() => options.success({ code: `synthetic-code-${++codeNumber}` })),
    reLaunch: options => options.complete?.(), previewImage: options => { previews.push(options); options.success?.({}) },
    getFileSystemManager: () => ({ unlink: options => { deleted.push(options.filePath); options.success?.({}) } }),
  }
  auth.cancelAuthentication(); state.clearSession()
})
after(() => { auth.cancelAuthentication(); state.clearSession() })

test('login result must be active, uniquely scoped and current; /me cannot silently change identity', () => {
  for (const invalid of [
    { ...loginResult(), user: { ...loginResult().user, activated: false } },
    { ...loginResult(), user: { ...loginResult().user, roles: ['OWNER', 'REPAIR_SHOP'] } },
    loginResult('REPAIR_SHOP'), { ...loginResult(), expiresAt: 'expired' },
  ]) assert.throws(() => state.acceptSession(invalid, state.sessionGeneration()))
  const old = state.sessionGeneration(); state.clearSession()
  assert.throws(() => state.acceptSession(loginResult(), old)); assert.equal(state.currentSession(), null)
  signIn(); assert.equal(state.currentSession().user.role, 'OWNER')
  assert.throws(() => state.updateCurrentUser({ ...loginResult().user, roles: ['REPAIR_SHOP'], shopId: 9 }, state.sessionGeneration()))
  assert.equal(state.currentSession(), null)
})

test('401 expires the current session and aborts its other requests; late responses cannot restore it', async () => {
  signIn()
  const first = client.request('/work-orders'), second = client.request('/notifications')
  const checks = [assert.rejects(first, error => error.status === 401), assert.rejects(second, error => error.code === 'CANCELLED')]
  respond(requests[0], 401, { code: 'SESSION_INVALID' }); await Promise.all(checks)
  assert.equal(state.currentSession(), null); assert.equal(requests[1].aborted, true)
  respond(requests[1], 200, { code: 'SUCCESS', data: { private: 'old' } })
  assert.equal(state.currentSession(), null)
})

test('public credential failures and 403 keep the session; 429 honors cooldown without repeated network requests', async () => {
  signIn()
  let pending = client.request('/auth/wechat/bindings', { authenticated: false, method: 'POST' })
  let check = assert.rejects(pending, error => error.code === 'INVALID_CREDENTIALS')
  assert.equal(requests[0].options.header.Authorization, undefined)
  respond(requests[0], 401, { code: 'INVALID_CREDENTIALS' }); await check
  assert.ok(state.currentSession())
  pending = client.request('/forbidden'); check = assert.rejects(pending, error => error.status === 403)
  respond(requests[1], 403, { code: 'FORBIDDEN' }); await check; assert.ok(state.currentSession())
  pending = client.request('/limited'); check = assert.rejects(pending, error => error.status === 429 && error.retryAfter === 3)
  respond(requests[2], 429, { code: 'ACCOUNT_RATE_LIMIT' }, { 'Retry-After': '3' }); await check
  await assert.rejects(client.request('/limited'), error => error.status === 429)
  assert.equal(requests.length, 3)
})

test('immutable commands deduplicate clicks and retain key/body after uncertain writes; conflicts require new review', async () => {
  signIn()
  const draft = { expectedVersion: 1, amount: '12.50' }, command = client.createCommand('/repairs/cases/example/receipt', draft)
  const first = command.execute(), duplicate = command.execute(); assert.equal(first, duplicate)
  draft.amount = '99.00'; const check = assert.rejects(first, error => error.code === 'NETWORK')
  requests[0].options.fail({}); await check
  const second = command.execute(); assert.deepEqual(requests[1].options.data, { expectedVersion: 1, amount: '12.50' })
  assert.equal(requests[0].options.header['Idempotency-Key'], requests[1].options.header['Idempotency-Key'])
  const conflict = assert.rejects(second, error => error.status === 409)
  respond(requests[1], 409, { code: 'REPAIR_VERSION_CONFLICT' }); await conflict
  await assert.rejects(command.execute(), error => error.code === 'COMMAND_CONFLICT')
  assert.equal(requests.length, 2)
})

test('WeChat authentication uses fresh codes, blocks double clicks and never posts a selected role', async () => {
  const first = auth.loginWithWeChat()
  await assert.rejects(auth.loginWithWeChat(), error => error.code === 'BUSY'); await flush()
  assert.deepEqual(Object.keys(requests[0].options.data), ['code'])
  const rejected = assert.rejects(first); respond(requests[0], 503, { code: 'WECHAT_UNAVAILABLE' }); await rejected
  const second = auth.loginWithWeChat(); await flush()
  assert.notEqual(requests[0].options.data.code, requests[1].options.data.code)
  respond(requests[1], 200, { code: 'SUCCESS', data: loginResult() }); await second
  assert.equal(state.currentSession().user.role, 'OWNER')
})

test('navigation cancellation prevents late sign-in; invalid passwords/invitations never obtain a code', async () => {
  assert.equal(auth.validPassword('字'.repeat(24)), true); assert.equal(auth.validPassword('字'.repeat(25)), false)
  await assert.rejects(auth.bindExisting('account', '字'.repeat(30), true))
  await assert.rejects(auth.inspectInvitation('invalid')); assert.equal(codeNumber, 0)
  const pending = auth.loginWithWeChat(); await flush()
  const rejected = assert.rejects(pending, error => error.code === 'CANCELLED')
  auth.cancelAuthentication(); await rejected
  respond(requests[0], 200, { code: 'SUCCESS', data: loginResult() }); assert.equal(state.currentSession(), null)
})

test('invitation inspection does not activate; completion uses the same credential with explicit confirmation', async () => {
  const token = 'a'.repeat(43)
  const inspect = auth.inspectInvitation(token)
  assert.equal(codeNumber, 0); assert.deepEqual(requests[0].options.data, { token })
  respond(requests[0], 200, { code: 'SUCCESS', data: { usernameMasked: 'a***', displayNameMasked: '合***', role: 'OWNER', expiresAt: new Date(Date.now() + 60000).toISOString() } }); await inspect
  assert.equal(state.currentSession(), null)
  const activation = auth.activateInvitation(token, 'synthetic-pass', 'synthetic-pass', true); await flush()
  assert.equal(requests[1].options.data.confirmed, true); assert.equal(requests[1].options.data.token, token)
  assert.equal(requests[1].options.data.role, undefined)
  respond(requests[1], 200, { code: 'SUCCESS', data: loginResult() }); await activation
  assert.ok(state.currentSession())
})

test('/me validation is deduplicated, accepts CurrentUser shape and rejects account changes', async () => {
  signIn()
  const first = auth.revalidateSession(); assert.equal(first, auth.revalidateSession()); assert.equal(requests.length, 1)
  respond(requests[0], 200, { code: 'SUCCESS', data: { id: 1, username: 'synthetic', displayName: '新显示名', roles: ['OWNER'], authVersion: 2 } }); await first
  assert.equal(state.currentSession().user.displayName, '新显示名')
  const pending = auth.revalidateSession(), check = assert.rejects(pending)
  respond(requests[1], 200, { code: 'SUCCESS', data: { id: 2, roles: ['OWNER'] } }); await check
  assert.equal(state.currentSession(), null)
})

test('material queue limits concurrency, retains successes and retries the original file/key; owner upload is refused', async () => {
  const file = { path: '/synthetic/original.jpg', name: 'original.jpg', mimeType: 'image/jpeg', size: 100 }
  signIn(); assert.throws(() => upload.createMaterialUpload(caseId, 'ARRIVAL_PHOTO', 1, file))
  signIn('REPAIR_SHOP', 2, 9)
  const commands = [0, 1, 2].map(() => upload.createMaterialUpload(caseId, 'ARRIVAL_PHOTO', 1, file)), queue = new upload.MaterialUploadQueue()
  queue.enqueue(commands); assert.equal(uploads.length, 2)
  assert.deepEqual(uploads[0].options.formData, { category: 'ARRIVAL_PHOTO', assignmentVersion: '1' })
  respond(uploads[0], 200, JSON.stringify({ code: 'SUCCESS', data: { id: fileId } })); await flush(); assert.equal(uploads.length, 3)
  uploads[1].options.fail({}); respond(uploads[2], 200, JSON.stringify({ code: 'SUCCESS', data: { id: fileId } })); await flush()
  queue.retry(commands[1].idempotencyKey); assert.equal(uploads.length, 4)
  assert.equal(uploads[3].options.filePath, uploads[1].options.filePath)
  assert.equal(uploads[3].options.header['Idempotency-Key'], uploads[1].options.header['Idempotency-Key'])
  assert.equal(queue.entries.value.filter(entry => entry.state === 'uploaded').length, 2)
  state.clearSession(); assert.equal(queue.entries.value.length, 0); assert.equal(uploads[3].aborted, true); queue.dispose()
})

test('private file requests attach authorization and delete late or session-owned temporary files', async () => {
  signIn()
  const pending = files.downloadPrivateFile(fileId), rejected = assert.rejects(pending)
  assert.ok(downloads[0].options.header.Authorization)
  state.clearSession(); await rejected
  downloads[0].options.success({ statusCode: 200, tempFilePath: '/synthetic/late.jpg' }); assert.ok(deleted.includes('/synthetic/late.jpg'))
  signIn()
  const preview = files.previewPrivateImage(fileId)
  downloads[1].options.success({ statusCode: 200, tempFilePath: '/synthetic/preview.jpg' }); await preview
  assert.equal(previews.length, 1); assert.equal(deleted.includes('/synthetic/preview.jpg'), false)
  state.clearSession(); assert.ok(deleted.includes('/synthetic/preview.jpg'))
})
