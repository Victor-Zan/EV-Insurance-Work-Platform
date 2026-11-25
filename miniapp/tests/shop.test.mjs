import test, { beforeEach, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdir, readFile } from 'node:fs/promises'
import path from 'node:path'
import { pathToFileURL, fileURLToPath } from 'node:url'
import { build } from 'esbuild'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..'), output = path.join(root, '.task-log/s06-tests/shop.mjs')
await mkdir(path.dirname(output), { recursive: true })
await build({ stdin: { contents: `export * as state from './src/services/session'; export * as shop from './src/services/shop'; export * as ocr from './src/services/shop-ocr'; export * as money from './src/services/quote-money'; export * as files from './src/services/files';`, resolveDir: root },
  bundle: true, platform: 'node', format: 'esm', target: 'es2015', external: ['vue'], outfile: output,
  define: { 'import.meta.env': JSON.stringify({ VITE_API_BASE_URL: 'https://synthetic.example.test/api/v1' }) },
  plugins: [{ name: 'wechat-target', setup(builder) { builder.onLoad({ filter: /services[\/\\].*\.ts$/ }, async args => ({ loader: 'ts', contents: (await readFile(args.path, 'utf8')).replace(/\/\/ #ifdef H5[\s\S]*?\/\/ #endif/g, '') })) } }],
})
const { state, shop, ocr, money, files } = await import(pathToFileURL(output).href)
const id = '00000000-0000-4000-8000-000000000001', jobId = '00000000-0000-4000-8000-000000000002', otherId = '00000000-0000-4000-8000-000000000003'
let calls, uploads, downloads
function signIn(role = 'REPAIR_SHOP') { state.acceptSession({ accessToken: 'synthetic-not-a-real-token', expiresAt: new Date(Date.now() + 60000).toISOString(), user: { id: 1, roles: [role], shopId: role === 'REPAIR_SHOP' ? 7 : null, activated: true, enabled: true } }, state.sessionGeneration()) }
function transport(list, options) { const row = { options }; list.push(row); return { abort() { options.fail?.({}) }, onProgressUpdate() {} } }
function respond(index, data, status = 200) { calls[index].options.success({ statusCode: status, data: status === 200 ? { code: 'SUCCESS', data } : { code: 'STATE_CONFLICT' } }) }
const row = (extra = {}) => ({ id, shopId: 7, status: 'ARRIVED', businessNo: 'SYNTHETIC-SHOP', currentAssignment: { shopId: 7, assignmentVersion: 3, status: 'ACCEPTED' }, version: 99, ...extra })
const quote = (extra = {}) => ({ caseId: id, status: 'ARRIVED', version: 8, assignmentVersion: 3, arrivalPhotoUploaded: true, rawQuote: null, gate: {}, ...extra })
const job = (extra = {}) => ({ id: jobId, state: 'SUCCEEDED', provider: 'MOCK_V1', reviewVersion: 0, rerunsUsed: 0, pageNo: 1, confirmed: false, ...extra })
const lines = () => [{ description: '配件', quantity: '3', unitPrice: '0.10' }]
const flush = () => new Promise(resolve => setImmediate(resolve))
beforeEach(() => { calls = []; uploads = []; downloads = []; globalThis.uni = { request: options => transport(calls, options), uploadFile: options => transport(uploads, options), downloadFile: options => transport(downloads, options), getFileSystemManager: () => ({ unlink() {} }) }; state.clearSession() })
after(() => state.clearSession())

test('only a current shop can read or write; foreign shops and owner OCR downloads fail before transport', async () => {
  assert.throws(() => shop.shopApi.cases()); signIn('OWNER'); assert.throws(() => shop.shopApi.detail(id, 's06'))
  await assert.rejects(files.downloadQuoteOcrImage(id, jobId), error => error.status === 403)
  signIn(); assert.throws(() => shop.parseCase(row({ shopId: 8 })), error => error.status === 403)
  assert.throws(() => shop.parseCase(row({ currentAssignment: { shopId: 8, assignmentVersion: 3 } })))
  assert.equal(calls.length + downloads.length, 0)
})
test('money stays exact above safe integer range, validates integer quantities and NUMERIC(38,2) overflow', () => {
  assert.equal(money.lineAmount('3', '0.10'), '0.30')
  assert.equal(money.lineAmount('3', '9007199254740993.17'), '27021597764222979.51')
  assert.equal(money.lineAmount('2147483647', '0.01'), '21474836.47')
  assert.equal(money.quoteTotal([{ quantity: '1', unitPrice: '0.10' }, { quantity: '1', unitPrice: '0.20' }]), '0.30')
  for (const [quantity, price] of [['0', '1'], ['1.5', '1'], ['2147483648', '1'], ['1', '1.001'], ['1', '-1'], ['2', '9'.repeat(36)]]) assert.throws(() => money.lineAmount(quantity, price))
})
test('server arrival flag is authoritative and projections exclude markup, assessment and technical price snapshots', async () => {
  signIn(); const active = shop.parseCase(row()), context = shop.parseQuote(quote({ arrivalPhotoUploaded: false, formalQuote: { markup: 'secret' }, assessment: { amount: 'secret' } }))
  assert.equal(context.formalQuote, undefined); assert.equal(context.assessment, undefined)
  assert.throws(() => shop.rawQuoteCommand(active, context, lines()), error => error.status === 409)
  assert.throws(() => shop.parseQuote(quote({ arrivalPhotoUploaded: undefined })), error => error.code === 'BACKEND_VERSION')
  assert.throws(() => shop.rawQuoteCommand(active, shop.parseQuote(quote({ assignmentVersion: 2 })), lines()))
  const pending = shop.shopApi.quoteHistory(id, 2, 's06')
  assert.deepEqual(calls[0].options.data, { page: 2, size: 20, type: 'RAW' })
  respond(0, { page: 2, size: 20, total: 21, records: [{ id: jobId, version_no: 1, total: '0.30', created_at: '2026-11-03T04:00:00Z', markup: 'secret' }] })
  assert.equal((await pending).records[0].markup, undefined)
})
test('assignment commands freeze assignmentVersion, preserve unknown-result retry keys and handle scope loss after rejection', async () => {
  signIn(); const active = shop.parseCase(row({ status: 'PENDING_ACCEPTANCE', currentAssignment: { shopId: 7, assignmentVersion: 3, status: 'PENDING' } }))
  const command = shop.assignmentCommand(active, 'accept'), first = command.execute(); active.assignment.version = 99
  const check = assert.rejects(first, error => error.code === 'NETWORK'); calls[0].options.fail({}); await check
  const next = command.execute(); assert.deepEqual(calls[1].options.data, { assignmentVersion: 3 }); assert.equal(calls[0].options.header['Idempotency-Key'], calls[1].options.header['Idempotency-Key'])
  respond(1, row({ status: 'PENDING_ARRIVAL' })); assert.equal((await next).status, 'PENDING_ARRIVAL')
  const reject = shop.assignmentCommand(shop.parseCase(row({ status: 'PENDING_ACCEPTANCE', currentAssignment: { shopId: 7, assignmentVersion: 3, status: 'PENDING' } })), 'reject', '无法接单').execute()
  respond(2, { id, status: 'PENDING_DISPATCH', shopId: null, currentAssignment: null }); assert.equal(await reject, null)
})
test('OCR candidates preserve sources, refuse duplicates/historical mocks and require explicit re-recognition within limit', async () => {
  signIn(); const item = { description: '配件', quantity: '1', unitPrice: '2.00', unit: '件', lineAmount: '2.00', evidence: '原文' }, parsed = ocr.parseJob(job())
  const adopted = ocr.adoptCandidate([], item, parsed, 0, 'MOCK_V1')
  assert.equal(adopted[0].ocr.jobId, jobId); assert.throws(() => ocr.adoptCandidate(adopted, item, parsed, 0, 'MOCK_V1'))
  assert.throws(() => ocr.adoptCandidate([], item, parsed, 0, 'BAIDU_HANDWRITING_V1'))
  assert.match(ocr.sourceLabel(parsed, 'MOCK_V1'), /非真实识别/)
  assert.throws(() => ocr.ocrRetryCommand(id, ocr.parseJob(job({ rerunsUsed: 3 }))))
  assert.throws(() => ocr.ocrRetryCommand(id, ocr.parseJob(job({ state: 'RUNNING' }))))
  const retry = ocr.ocrRetryCommand(id, parsed), pending = retry.execute(), conflict = assert.rejects(pending, error => error.status === 409)
  respond(0, null, 409); await conflict; await assert.rejects(retry.execute()); assert.equal(calls.length, 1)
})
test('multi-image human snapshots complete before raw quote; retries keep original snapshots, versions and payload', async () => {
  signIn(); const draft = [
    { description: '配件A', quantity: '1', unitPrice: '1', ocr: { jobId, itemIndex: 0 } },
    { description: '配件B', quantity: '1', unitPrice: '2', ocr: { jobId: otherId, itemIndex: 1 } },
  ], command = ocr.preparedQuoteCommand(shop.parseCase(row()), shop.parseQuote(quote()), draft, [ocr.parseJob(job()), ocr.parseJob(job({ id: otherId }))], 'MOCK_V1')
  draft[0].unitPrice = '999'
  const first = command.execute(); assert.match(calls[0].options.url, /\/ocr\/.+\/apply$/)
  respond(0, job({ reviewVersion: 1, confirmed: true })); await flush()
  const secondKey = calls[1].options.header['Idempotency-Key'], failed = assert.rejects(first); calls[1].options.fail({}); await failed
  const retry = command.execute(); assert.equal(calls[2].options.header['Idempotency-Key'], secondKey)
  respond(2, job({ id: otherId, reviewVersion: 2, confirmed: true })); await flush()
  const payload = calls[3].options.data
  assert.equal(payload.expectedVersion, 8); assert.equal(payload.assignmentVersion, 3); assert.equal(payload.lines[0].unitPrice, '1.00')
  assert.equal(payload.lines[0].ocr.revision, 1); assert.equal(payload.lines[1].ocr.revision, 2)
  respond(3, quote({ status: 'QUOTE_REVIEWING', version: 9 })); assert.equal((await retry).version, 9)
  assert.equal(calls.length, 4)
})
test('OCR uploads retry the original file/key; session changes abort old writes and clear pending operations', async () => {
  signIn(); const command = ocr.ocrUploadCommand(shop.parseCase(row()), { path: '/synthetic.png', name: 'synthetic.png', size: 100, mimeType: 'image/png' }, 1, 'SHOP_QUOTE')
  const first = command.execute(), failed = assert.rejects(first); uploads[0].options.fail({}); await failed
  const retry = command.execute(); assert.equal(uploads[0].options.filePath, uploads[1].options.filePath); assert.equal(uploads[0].options.header['Idempotency-Key'], uploads[1].options.header['Idempotency-Key'])
  assert.deepEqual(uploads[1].options.formData, { assignmentVersion: '3', pageNo: '1', category: 'SHOP_QUOTE' })
  uploads[1].options.success({ statusCode: 200, data: JSON.stringify({ code: 'SUCCESS', data: job() }) }); await retry
  const pending = shop.shopApi.cases(), aborted = assert.rejects(pending)
  shop.shopPending.set(id, { title: '合成待确认', command }); ocr.ocrPending.set(id, command)
  state.clearSession(); await aborted; assert.equal(shop.shopPending.size, 0); assert.equal(ocr.ocrPending.size, 0)
  assert.throws(() => command.execute()); assert.equal(calls.length, 1)
})
