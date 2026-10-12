import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'

const source=path=>readFileSync(new URL(path,import.meta.url),'utf8')

test('historical archive is a staff-only route separate from live work orders',()=>{
  const routes=source('../src/router/index.ts')
  const view=source('../src/features/historical/HistoricalCasesView.vue')
  assert.match(routes,/path: '\/historical-cases'.*roles: \['ADMIN', 'CUSTOMER_SERVICE'\]/)
  assert.match(view,/\/historical-cases\/imports/)
  assert.match(view,/\/confirm/)
  assert.match(view,/仅有效且未重复的行会归档/)
  assert.doesNotMatch(view,/\/work-orders\/drafts|\/work-orders\/.*\/submit/)
})

test('stage twelve downloads use authenticated document endpoints',()=>{
  const quote=source('../src/features/quotation/quotation.ts')
  const orders=source('../src/features/workorders/work-orders.ts')
  assert.match(quote,/formal-quotes\/\$\{quoteId\}\/pdf/)
  assert.match(orders,/\/funds\/reconciliation\/export/)
  assert.match(orders,/responseType:'blob'/)
})
