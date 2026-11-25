import test, { beforeEach, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdir, readFile } from 'node:fs/promises'
import path from 'node:path'
import { pathToFileURL, fileURLToPath } from 'node:url'
import { build } from 'esbuild'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..'), output = path.join(root, '.task-log/s07-tests/repair.mjs')
await mkdir(path.dirname(output), { recursive: true })
await build({ stdin: { contents: `export * as state from './src/services/session'; export * as shop from './src/services/shop'; export * as repair from './src/services/shop-repair'; export * as owner from './src/services/owner';`, resolveDir: root }, bundle: true, platform: 'node', format: 'esm', target: 'es2015', external: ['vue'], outfile: output,
  define: { 'import.meta.env': JSON.stringify({ VITE_API_BASE_URL: 'https://synthetic.example.test/api/v1' }) },
  plugins: [{ name: 'wechat-target', setup(builder) { builder.onLoad({ filter: /services[\/\\].*\.ts$/ }, async args => ({ loader: 'ts', contents: (await readFile(args.path, 'utf8')).replace(/\/\/ #ifdef H5[\s\S]*?\/\/ #endif/g, '') })) } }],
})
const { state, shop, repair, owner } = await import(pathToFileURL(output).href)
const id = '00000000-0000-4000-8000-000000000001', photoId = '00000000-0000-4000-8000-000000000002', entryId = '00000000-0000-4000-8000-000000000003'
let calls
const signIn = (role = 'REPAIR_SHOP') => state.acceptSession({ accessToken: 'synthetic-not-a-real-token', expiresAt: new Date(Date.now() + 60000).toISOString(), user: { id: 1, roles: [role], shopId: role === 'REPAIR_SHOP' ? 7 : null, activated: true, enabled: true } }, state.sessionGeneration())
const row = () => shop.parseCase({ id, shopId: 7, status: 'REPAIRING', currentAssignment: { shopId: 7, assignmentVersion: 3, status: 'ACCEPTED' } })
const rawRepair = (extra = {}) => ({ caseId: id, version: 7, assignmentVersion: 3, status: 'REPAIRING', completion: null, receipt: null, review: null, ...extra })
const quote = (extra = {}) => shop.parseQuote({ caseId: id, version: 22, assignmentVersion: 3, status: 'REPAIRING', arrivalPhotoUploaded: true, authorized: true, gate: { allowed: true }, ...extra })
const photo = (category = 'COMPLETION_PHOTO') => ({ id: photoId, version: 2, category, contentType: 'image/png' })
const entry = () => ({ id: entryId, assignmentVersion: 3, createdAt: '2026-11-03T00:00:00Z', photos: [photo()] })
function respond(n, data, status = 200) { calls[n].success({ statusCode: status, data: status === 200 ? { code: 'SUCCESS', data } : { code: 'REPAIR_VERSION_CONFLICT' } }) }
beforeEach(() => { calls = []; globalThis.uni = { request(options) { calls.push(options); return { abort() { options.fail({}) } } }, getFileSystemManager: () => ({ unlink() {} }) }; state.clearSession() })
after(() => state.clearSession())
test('repair requires current accepted assignment and server authorization; neither owner nor old assignment can write', () => {
  signIn('OWNER'); assert.throws(() => repair.repairCommand({}, {}, {}, 'completion', '', [], [])); signIn()
  const active = row(), data = repair.parseRepair(rawRepair())
  for (const context of [quote({ authorized: false }), quote({ gate: { allowed: false } }), quote({ assignmentVersion: 2 })]) assert.throws(() => repair.repairCommand(active, data, context, 'completion', '', [photoId], [photo()]))
  assert.throws(() => repair.repairCommand(active, repair.parseRepair(rawRepair({ assignmentVersion: 2 })), quote(), 'progress', '说明', [], []))
  assert.equal(calls.length, 0)
})
test('completion requires distinct current-category photos; immutable retry uses repair version not quotation version', async () => {
  signIn(); const active = row(), data = repair.parseRepair(rawRepair()), context = quote()
  for (const ids of [[], [photoId, photoId]]) assert.throws(() => repair.repairCommand(active, data, context, 'completion', '', ids, [photo()]))
  assert.throws(() => repair.repairCommand(active, data, context, 'completion', '', [photoId], [photo('PROGRESS_PHOTO')]))
  const ids = [photoId], command = repair.repairCommand(active, data, context, 'completion', '', ids, [photo()])
  ids.length = 0; data.version = 99
  let pending = command.execute(); const failed = assert.rejects(pending, e => e.code === 'NETWORK'); calls[0].fail({}); await failed
  pending = command.execute(); assert.deepEqual(calls[1].data, { expectedVersion: 7, assignmentVersion: 3, photoIds: [photoId] }); assert.equal(calls[0].header['Idempotency-Key'], calls[1].header['Idempotency-Key'])
  respond(1, rawRepair({ version: 8, status: 'WAITING_OWNER_CONFIRMATION', completion: entry() })); const saved = await pending
  assert.equal(saved.completion.photos[0].version, 2)
  const serialized = JSON.stringify(saved); assert.ok(!serialized.includes('priceSnapshot'))
})
test('progress permits zero photos but requires text, preserves note and rejects stale completion snapshots', async () => {
  signIn(); const active = row(), data = repair.parseRepair(rawRepair())
  assert.throws(() => repair.repairCommand(active, data, quote(), 'progress', ' ', [], []))
  const command = repair.repairCommand(active, data, quote(), 'progress', ' 已完成拆检 ', [], []), pending = command.execute()
  assert.equal(calls[0].data.note, '已完成拆检'); respond(0, rawRepair({ version: 8 })); await pending
  assert.throws(() => repair.parseRepair(rawRepair({ completion: { ...entry(), assignmentVersion: 2 } })))
  assert.throws(() => repair.parseRepair(rawRepair({ completion: { ...entry(), photos: [photo('PROGRESS_PHOTO')] } })))
  const history = repair.shopRepairApi.history(active, 2, 's07')
  assert.deepEqual(calls[1].data, { page: 2, size: 20 }); respond(1, { page: 2, size: 20, total: 21, records: [{ ...entry(), assignmentVersion: 2 }] }); await assert.rejects(history)
})
test('settlement and reviews whitelist public fields, owner cannot request funds, logout cancels and clears uncertain commands', async () => {
  signIn(); const active = row(), query = repair.shopRepairApi.settlement(active, 's07')
  respond(0, { caseId: id, currency: 'CNY', unit: 'yuan', payable: { target: null, net: '0.00', status: 'UNSET' }, receivable: { target: 'private' }, transactionNo: 'private', canRecordPay: true })
  assert.deepEqual(await query, { caseId: id, target: null, net: '0.00', status: 'UNSET' })
  assert.equal(repair.parseReview({ id: '17', text: '公开纠正', score: 4, reason: 'private' }).reason, undefined)
  signIn('OWNER'); await assert.rejects(repair.shopRepairApi.settlement(active, 's07')); signIn()
  const command = repair.repairCommand(active, repair.parseRepair(rawRepair()), quote(), 'progress', '已检查', [], [])
  repair.repairPending.set(id, { command, title: '追加进度', note: '已检查', photoIds: [], category: 'PROGRESS_PHOTO' })
  const pending = command.execute(), cancelled = assert.rejects(pending); state.clearSession(); await cancelled
  assert.equal(repair.repairPending.size, 0); await assert.rejects(command.execute())
})
test('shop completion and owner receipt share response versions while owner gets no quotation or settlement projection', async () => {
  signIn('OWNER'); const read = owner.ownerApi.repair(id, 's07')
  respond(0, rawRepair({ version: 8, status: 'WAITING_OWNER_CONFIRMATION', completion: entry(), payable: { target: 'secret' } }))
  const data = await read; assert.ok(owner.canReceive(data)); assert.equal(data.payable, undefined)
  const pending = owner.receiptCommand(data).execute(); assert.deepEqual(calls[1].data, { expectedVersion: 8 })
  respond(1, rawRepair({ version: 9, status: 'WAITING_INSURER_PAYMENT', completion: entry(), receipt: { confirmedBy: 'OWNER', createdAt: '2026-11-03T00:00:00Z', withdrawn: false } }))
  const received = await pending; assert.ok(owner.canReview(received)); assert.equal(owner.ownerStatus(received.status), '已收车')
})
