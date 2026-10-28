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

test('legacy quotation responses recover saved arrival photos beyond the first material page',async()=>{
  const calls=[],client={get:async(url,config)=>{
    calls.push({url,config})
    if(url==='/quotations/cases/case-1')return {data:{data:{caseId:'case-1',assignmentVersion:3}}}
    return {data:{data:{total:101,records:config.params.page===1?Array.from({length:100},()=>({category:'SHOP_QUOTE',state:'ACTIVE',contentType:'image/png'})):[{category:'ARRIVAL_PHOTO',state:'ACTIVE',contentType:'image/jpeg'}]}}}
  }}
  assert.equal((await quotationApi(client,'case-1').get()).arrivalPhotoUploaded,true)
  assert.deepEqual(calls.slice(1).map(call=>call.config.params),[{page:1,size:100},{page:2,size:100}])
  assert.equal(calls.slice(1).every(call=>call.url==='/materials/cases/case-1'),true)
})

test('an explicit server arrival decision is authoritative and never falls back to attachments',async()=>{
  for(const arrivalPhotoUploaded of [false,true]){
    const calls=[],client={get:async url=>{calls.push(url);return {data:{data:{arrivalPhotoUploaded,assignmentVersion:3}}}}}
    assert.equal((await quotationApi(client,'case-1').get()).arrivalPhotoUploaded,arrivalPhotoUploaded)
    assert.deepEqual(calls,['/quotations/cases/case-1'])
  }
})

test('legacy arrival recovery rejects wrong categories, inactive files, PDFs and missing assignments',async()=>{
  const records=[{category:'ARRIVAL_PHOTO',state:'SUPERSEDED',contentType:'image/png'},{category:'ARRIVAL_PHOTO',state:'ACTIVE',contentType:'application/pdf'},{category:'PROGRESS_PHOTO',state:'ACTIVE',contentType:'image/jpeg'}]
  for(const assignmentVersion of [undefined,3]){
    const calls=[],client={get:async url=>{calls.push(url);return {data:{data:url.startsWith('/quotations/')?{assignmentVersion}:{records,total:records.length}}}}}
    assert.equal((await quotationApi(client,'case-1').get()).arrivalPhotoUploaded,false)
    assert.equal(calls.length,assignmentVersion===undefined?1:2)
  }
})

test('failed material reads cannot report a legacy arrival photo as ready',async()=>{
  const client={get:async url=>{if(url.startsWith('/quotations/'))return {data:{data:{assignmentVersion:3}}};throw Error('材料状态读取失败')}}
  await assert.rejects(quotationApi(client,'case-1').get(),/材料状态读取失败/)
})
