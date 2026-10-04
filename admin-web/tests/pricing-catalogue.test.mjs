import test from 'node:test'
import assert from 'node:assert/strict'
import { catalogueKind, cataloguePayload, canMaintain } from '../src/features/pricing/catalogue.ts'
test('only ADMIN receives catalogue maintenance controls', () => {
 for (const role of ['CUSTOMER_SERVICE', 'REPAIR_SHOP', 'OWNER']) assert.equal(canMaintain({ roles: [role] }), false)
 assert.equal(canMaintain(null), false)
 assert.equal(canMaintain({ roles: ['ADMIN'] }), true)
})
test('catalogue edits send typed IDs, trimmed identifiers and explicit enabled state', () => {
 assert.deepEqual(cataloguePayload('models', { brandId: '7', code: ' TEST-M ', name: ' 测试车型 ' }, false), { brandId: 7, code: 'TEST-M', name: '测试车型', enabled: false })
 assert.deepEqual(cataloguePayload('aliases', { partId: '2', name: ' 别名 ' }, true), { partId: 2, name: '别名' })
 assert.throws(() => cataloguePayload('models', { brandId: '1.5', code: 'X', name: 'N' }, true), /正整数/)
 assert.throws(() => cataloguePayload('parts', { internalCode: ' ', name: 'N' }, true), /必填/)
 assert.throws(() => cataloguePayload('sources', { code: 'X', name: 'N', kind: 'OTHER' }, true), /来源类型/)
})
test('unknown catalogue routes cannot select arbitrary API paths', () => {
 assert.equal(catalogueKind('parts'), 'parts')
 assert.throws(() => catalogueKind('toString'))
 assert.throws(() => catalogueKind('../admin/users'))
})
