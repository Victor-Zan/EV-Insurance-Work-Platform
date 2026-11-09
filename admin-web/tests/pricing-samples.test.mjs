import test from 'node:test'
import assert from 'node:assert/strict'
import { importDateRange, sampleFields, sampleReview } from '../src/features/pricing/samples.ts'
import { cataloguePayload } from '../src/features/pricing/catalogue.ts'

test('review prefers current confirmed values and retains precision as strings', () => {
  const result = sampleReview({ version: 4, status: 'CONFIRMED', amount: '12345678901234.56', region: '', original: { amount: '9.90', region: '深圳' } })
  assert.equal(result.amount, '12345678901234.56')
  assert.equal(result.region, '')
  assert.equal(result.expectedVersion, 4)
  assert.equal(result.reason, '')
})
test('capture and review leave the system import date read-only and omit legacy fields', () => {
  const result = sampleReview({ version: 0, status: 'PENDING', importedDate: '2026-11-09', original: { quality: '原装', priceDate: '2025-06-13' } })
  for (const field of ['quality', 'priceDate', 'importedDate']) {
    assert.ok(!Object.hasOwn(result, field))
    assert.ok(!sampleFields.some(([key]) => key === field))
  }
})
test('unmapped evidence remains available for review without guessing price nature', () => {
  const result = sampleReview({ version: 0, status: 'PENDING', original: { amount: '金额待核对', unit: '套', priceNature: 'UNKNOWN' } })
  assert.equal(result.amount, '金额待核对')
  assert.equal(result.partId, null)
  assert.equal(result.modelId, null)
  assert.equal(result.priceNature, 'UNCLASSIFIED')
})
test('catalogue keeps old callers compatible and validates optional part metadata', () => {
  assert.deepEqual(cataloguePayload('parts', { internalCode: 'P', name: '配件' }, true), { internalCode: 'P', name: '配件', enabled: true })
  const result = cataloguePayload('parts', { internalCode: 'P', name: '配件', unit: ' 个 ', oeCode: 'OE-1', manufacturerCode: '' }, true)
  assert.equal(result.unit, '个')
  assert.equal(result.manufacturerCode, '')
  assert.throws(() => cataloguePayload('parts', { internalCode: 'P', name: '配件', unit: '长'.repeat(25) }, true), /过长/)
})
test('date presets use China days and clamp month and year boundaries', () => {
  const now = new Date('2024-05-30T16:20:00Z')
  assert.deepEqual(importDateRange('ALL', now), {})
  assert.deepEqual(importDateRange('WEEK', now), { from: '2024-05-25', to: '2024-05-31' })
  assert.deepEqual(importDateRange('MONTH', now), { from: '2024-04-30', to: '2024-05-31' })
  assert.deepEqual(importDateRange('QUARTER', now), { from: '2024-02-29', to: '2024-05-31' })
  assert.deepEqual(importDateRange('YEAR', new Date('2024-02-29T00:00:00Z')), { from: '2023-02-28', to: '2024-02-29' })
})
