import test from 'node:test'
import assert from 'node:assert/strict'
import {formalPayload,quotationApi} from '../src/features/quotation/quotation.ts'
test('markup payload preserves decimal strings beyond JavaScript integer precision and excludes the other mode',()=>{
  const fixed=formalPayload(2,'raw-1','FIXED_AMOUNT','9007199254740993.00')
  assert.equal(fixed.fixedAmount,'9007199254740993.00');assert.equal('percentage' in fixed,false)
  const rate=formalPayload(3,'raw-1','PERCENTAGE','10.00')
  assert.equal(rate.percentage,'10.00');assert.equal('fixedAmount' in rate,false)
})
test('quotation mutations carry case scope, original decimal payload and a stable caller-supplied idempotency key',async()=>{
  const calls=[],client={post:async(url,body,config)=>{calls.push({url,body,config});return {data:{data:{version:2}}}}}
  const body={expectedVersion:1,amount:'0.01'},api=quotationApi(client,'case-1')
  const result=await api.post('assessments',body,'retry-key')
  assert.equal(result.version,2);assert.equal(calls[0].url,'/quotations/cases/case-1/assessments');assert.deepEqual(calls[0].body,body);assert.equal(calls[0].config.headers['Idempotency-Key'],'retry-key')
})
test('historical external quote export uses the authenticated API client and exact quote ID as a blob',async()=>{
  const calls=[],payload=new Blob(['external_line_amount\n0.04']),client={get:async(url,config)=>{calls.push({url,config});return {data:payload}}}
  const result=await quotationApi(client,'case-1').export('historical-quote-1')
  assert.equal(result,payload);assert.equal(calls[0].url,'/quotations/cases/case-1/formal-quotes/historical-quote-1/export');assert.equal(calls[0].config.responseType,'blob')
})
