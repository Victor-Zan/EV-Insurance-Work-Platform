import test from 'node:test'
import assert from 'node:assert/strict'
import { versionPayload, validDate, chinaToday, filterPayload, scopeLabel, mayConfirm, PRICE_READ_ROLES, PRICE_WRITE_ROLES } from '../src/features/pricing/prices.ts'
import { guard } from '../src/shared/auth/guard.ts'
import { AuthSession } from '../src/shared/auth/session.ts'
test('price amounts remain decimal strings at the maximum precision; zero, negative and rounding are rejected', () => {
 const form = { amount: '9999999999999999.99', sourceId: '1', effectiveFrom: '2026-01-01', effectiveTo: '' }
 assert.equal(versionPayload(form).amount, form.amount)
 assert.equal(versionPayload(form).effectiveTo, null)
 for (const amount of ['0', '0.00', '-1', '1.001', '10000000000000000.00', '1e3']) assert.throws(() => versionPayload({ ...form, amount }))
 assert.throws(() => versionPayload({ ...form, effectiveTo: '2025-12-31' }))
})
test('natural dates are strict, inclusive boundaries retain their values and China date is timezone specific', () => {
 assert.equal(validDate('2024-02-29'), true); assert.equal(validDate('2026-02-29'), false); assert.equal(validDate('2026-02-30'), false)
 assert.equal(chinaToday(new Date('2026-03-31T16:30:00Z')), '2026-04-01')
 assert.deepEqual(versionPayload({ amount: '0.01', sourceId: '2', effectiveFrom: '2026-01-01', effectiveTo: '2026-01-01' }), { amount: '0.01', sourceId: 2, effectiveFrom: '2026-01-01', effectiveTo: '2026-01-01' })
})
test('filters preserve explicit scope and identifiers rather than picking one candidate', () => {
 assert.deepEqual(filterPayload({ internalCode: ' P ', alias: '', modelId: '7', scope: 'REGION', queryDate: '2026-01-31' }, 2), { page: 2, size: 20, internalCode: 'P', modelId: 7, scope: 'REGION', queryDate: '2026-01-31' })
 assert.equal(scopeLabel({ scope: 'NATIONAL' }), '全国通用')
 assert.match(scopeLabel({ scope: 'SHOP', shopId: 2, shopName: '测试网点' }), /测试网点/)
 assert.throws(() => filterPayload({ regionId: '-1' }, 1))
})
test('import needs an actual preview and explicit confirmation; an invalid batch can be recorded as FAILED', () => {
 const preview = { id: 'test-preview', errorRows: 1 }
 assert.equal(mayConfirm(null, true, false), false); assert.equal(mayConfirm(preview, false, false), false)
 assert.equal(mayConfirm(preview, true, true), false); assert.equal(mayConfirm(preview, true, false), true)
})
test('pricing routes permit customer service operations and keep admin settings and H5 roles excluded', async () => {
 const memory = new Map(), storage = { getItem: key => memory.get(key) ?? null, setItem: (key, value) => memory.set(key, value), removeItem: key => memory.delete(key) }
 const session = new AuthSession(storage); const user = { id: 1, username: 'test-cs', displayName: '测试客服', roles: ['CUSTOMER_SERVICE'] }
 session.set({ accessToken: 'test-token', expiresAt: new Date(Date.now() + 60000).toISOString(), user })
 assert.equal(await guard(session, '/pricing', PRICE_READ_ROLES, async () => user), true)
 for (const path of ['/pricing/new', '/pricing/records/1/new', '/pricing/import', '/pricing/batches', '/pricing/batches/1'])
  assert.equal(await guard(session, path, PRICE_WRITE_ROLES, async () => user), true)
 assert.equal(await guard(session, '/users', ['ADMIN'], async () => user), '/forbidden')
 for (const role of ['REPAIR_SHOP', 'OWNER']) {
  const h5User = { ...user, roles: [role] }
  for (const [path, roles] of [['/pricing', PRICE_READ_ROLES], ['/pricing/import', PRICE_WRITE_ROLES]]) {
   assert.throws(() => session.set({ accessToken: 'test-token', expiresAt: new Date(Date.now() + 60000).toISOString(), user: h5User }), /不能登录当前端/)
   session.set({ accessToken: 'test-token', expiresAt: new Date(Date.now() + 60000).toISOString(), user })
   assert.equal(await guard(session, path, roles, async () => h5User), '/login')
   assert.equal(session.valid(), false)
  }
 }
 session.clear()
})
