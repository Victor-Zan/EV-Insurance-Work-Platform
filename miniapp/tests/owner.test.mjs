import test, { beforeEach, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'
import { pathToFileURL, fileURLToPath } from 'node:url'
import { build } from 'esbuild'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const output = path.join(root, '.task-log/s05-tests/owner.mjs')
await mkdir(path.dirname(output), { recursive: true })
await build({ stdin: { contents: `export * as state from './src/services/session'; export * as owner from './src/services/owner';`, resolveDir: root },
  bundle: true, platform: 'node', format: 'esm', target: 'es2020', external: ['vue'], outfile: output,
  define: { 'import.meta.env': JSON.stringify({ VITE_API_BASE_URL: 'https://synthetic.example.test/api/v1' }) } })
const { state, owner } = await import(pathToFileURL(output).href)
const id = '00000000-0000-4000-8000-000000000001', photoId = '00000000-0000-4000-8000-000000000002'
const recordId = '00000000-0000-4000-8000-000000000003', otherId = '00000000-0000-4000-8000-000000000004'
let calls
function signIn(role = 'OWNER') { state.acceptSession({ accessToken: 'synthetic-test-token', expiresAt: new Date(Date.now() + 60000).toISOString(), user: { id: 1, roles: [role], shopId: role === 'OWNER' ? null : 9, enabled: true, activated: true } }, state.sessionGeneration()) }
function respond(index, data, status = 200) { calls[index].success({ statusCode: status, data: status === 200 ? { code: 'SUCCESS', data } : { code: 'STATE_CONFLICT' } }) }
const photo = (extra = {}) => ({ id: photoId, version: 1, category: 'COMPLETION_PHOTO', contentType: 'image/png', ...extra })
const repair = (extra = {}) => ({ caseId: id, status: 'WAITING_OWNER_CONFIRMATION', version: 7, completion: { id: recordId, photos: [photo()], createdAt: '2026-11-02T04:00:00Z' }, receipt: null, review: null, ...extra })
const caseRow = (extra = {}) => ({ id, businessNo: 'SYNTHETIC-001', status: 'REPAIRING', workflowNode: 'REPAIR', ...extra })
const page = (records, number = 1, total = records.length) => ({ page: number, size: 20, total, records })
beforeEach(() => { calls = []; globalThis.uni = { request: options => { calls.push(options); return { abort() { options.fail?.({}) } } } }; state.clearSession() })
after(() => state.clearSession())

test('no login or shop identity can read owner endpoints or construct write commands', () => {
  assert.throws(() => owner.ownerApi.cases(), error => error.status === 403)
  signIn('REPAIR_SHOP'); assert.throws(() => owner.ownerApi.detail(id, 'test'))
  assert.throws(() => owner.receiptCommand(repair())); assert.throws(() => owner.complaintCommand(caseRow(), '问题', [], []))
  assert.equal(calls.length, 0)
})
test('case pagination/search use existing API; public projection drops money and internal fields', async () => {
  signIn(); const result = owner.ownerApi.cases(2, '  SYNTHETIC  ')
  assert.match(calls[0].url, /\/work-orders$/); assert.deepEqual(calls[0].data, { page: 2, size: 20, query: 'SYNTHETIC' })
  respond(0, page([caseRow({ status: 'WAITING_SHOP_SETTLEMENT', workflowNode: 'FUNDS', profit: 'secret', currentAssignment: { internal: true } })], 2, 25))
  const data = await result; assert.equal(data.page, 2); assert.equal(data.total, 25)
  assert.equal(owner.ownerStatus(data.records[0].status), '已收车'); assert.equal(owner.workflowIndex(data.records[0]), 7)
  assert.equal(data.records[0].profit, undefined); assert.equal(data.records[0].currentAssignment, undefined)
  assert.equal(owner.workflow.some(node => node[0] === 'FUNDS'), false)
})
test('material, history, notification and review corrections expose only public fields and actual server ID types', async () => {
  signIn(); let result = owner.ownerApi.photos(id, 1, 'test')
  respond(0, page([photo(), photo({ id: otherId, category: 'SCENE_PHOTO' }), photo({ state: 'VOID' }), photo({ contentType: 'application/pdf' })]))
  assert.equal((await result).records.length, 1)
  result = owner.ownerApi.history(id, 1, 'test'); respond(1, page([{ toStatus: 'REPAIRING', action: 'SECRET_TECHNICAL_ACTION', actorId: 9 }, { toStatus: 'WAITING_SHOP_SETTLEMENT' }]))
  assert.deepEqual((await result).records, [{ label: '维修中', createdAt: '' }])
  result = owner.ownerApi.notices(); respond(2, page([{ id: 1, caseId: id, kind: 'UNKNOWN', title: 'SECRET_FINANCIAL_TITLE' }]))
  assert.equal((await result).records[0].title, '案件有新进展')
  result = owner.ownerApi.repair(id, 'test'); respond(3, repair({ review: { eligibleForCurrentRating: false, original: { id: recordId, score: 5, text: '原评价' }, current: { id: '12', text: '公开纠正', score: 3, reason: 'SECRET_REASON' } } }))
  const data = await result; assert.equal(data.review.current.id, '12'); assert.equal(data.review.current.reason, undefined); assert.equal(data.review.eligible, false)
})
test('receipt keeps repair version and original idempotency after uncertain delivery; conflict blocks retry', async () => {
  signIn(); const value = repair(), command = owner.receiptCommand(value)
  const first = command.execute(); value.version = 99
  const rejected = assert.rejects(first, error => error.code === 'NETWORK'); calls[0].fail({}); await rejected
  const retry = command.execute(); assert.deepEqual(calls[1].data, { expectedVersion: 7 })
  assert.equal(calls[1].header['Idempotency-Key'], calls[0].header['Idempotency-Key'])
  const conflict = assert.rejects(retry, error => error.status === 409); respond(1, null, 409); await conflict
  await assert.rejects(command.execute(), error => error.status === 409); assert.equal(calls.length, 2)
})
test('review is optional, once only; text-only and score-only are valid; version is repair version', async () => {
  signIn(); const value = repair({ status: 'WAITING_INSURER_PAYMENT', receipt: { withdrawn: false } })
  assert.equal(owner.canReview(value), true)
  for (const [text, score] of [['', null], ['', 0], ['', 6], ['', 1.5], ['字'.repeat(2001), 5]]) assert.throws(() => owner.reviewCommand(value, text, score))
  let command = owner.reviewCommand(value, '  公开评价  ', null), pending = command.execute()
  assert.deepEqual(calls[0].data, { expectedVersion: 7, text: '公开评价', score: null })
  respond(0, repair({ ...value, review: { eligibleForCurrentRating: true, original: { id: recordId, text: '公开评价', score: null }, current: { id: recordId, text: '公开评价', score: null } } }))
  const result = await pending; assert.equal(owner.canReview(result), false); assert.throws(() => owner.reviewCommand(result, '', 5))
  command = owner.reviewCommand(value, '', 5); pending = command.execute(); assert.equal(calls[1].data.text, null)
  respond(1, repair(value)); await pending
  assert.equal(owner.canReview(repair({ ...value, receipt: { withdrawn: true } })), false)
})
test('complaints reference only existing authorized photos and cannot alter repair workflow', async () => {
  signIn(); const row = caseRow(), visible = [photo()]
  for (const ids of [[otherId], [photoId, photoId], Array(21).fill(photoId)]) assert.throws(() => owner.complaintCommand(row, '问题', ids, visible))
  assert.throws(() => owner.complaintCommand(row, '', [], visible)); assert.throws(() => owner.complaintCommand(caseRow({ status: 'CANCELLED' }), '问题', [], visible))
  const pending = owner.complaintCommand(row, ' 原始反馈 ', [photoId], visible).execute()
  assert.match(calls[0].url, /\/complaints\/cases\//); assert.deepEqual(calls[0].data, { description: '原始反馈', photoIds: [photoId] })
  respond(0, { id: recordId, caseId: id, status: 'SUBMITTED', description: '原始反馈', photos: [photo()], internalNote: 'secret' })
  assert.equal((await pending).internalNote, undefined); assert.equal(row.status, 'REPAIRING')
})
test('foreign-case responses and stale sessions cannot update owner state; pending writes clear on logout', async () => {
  signIn(); const result = owner.ownerApi.detail(id, 'test'), mismatch = assert.rejects(result, /不匹配/)
  respond(0, caseRow({ id: otherId })); await mismatch
  const command = owner.receiptCommand(repair()); let pending = command.execute(), check = assert.rejects(pending, /不匹配/)
  respond(1, repair({ caseId: otherId })); await check; assert.equal(owner.ownerRevision.value, 0)
  owner.pendingOwnerActions.set(id, { kind: 'receipt', command, text: '', score: null, photoIds: [] })
  pending = owner.ownerApi.cases(); check = assert.rejects(pending)
  state.clearSession(); await check; assert.equal(owner.pendingOwnerActions.size, 0)
  await assert.rejects(command.execute()); assert.equal(owner.displayTime('2026-11-02T04:00:00Z'), '2026-11-02 12:00')
})
