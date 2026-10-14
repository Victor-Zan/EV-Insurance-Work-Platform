import test from 'node:test'
import assert from 'node:assert/strict'
import { ocrIntakeApi } from '../src/features/materials/ocr-intake.ts'
import { blankOcrDraft } from '../src/features/materials/ocr-form.ts'

test('upload starts the existing private intake without a case ID and preserves file bytes plus caller retry key',async()=>{
  const calls=[],file=new File(['SYNTHETIC IMAGE BYTES'],'synthetic.png',{type:'image/png'})
  const api=ocrIntakeApi({post:async(path,body,config)=>{calls.push({path,body,config});return {data:{data:{id:'job',state:'QUEUED'}}}}})
  await api.upload(file,'stable-upload-key');await api.upload(file,'stable-upload-key')
  assert.deepEqual(calls.map(call=>call.path),['/ocr/intake','/ocr/intake'])
  for(const call of calls){assert.ok(call.body instanceof FormData);assert.deepEqual([...call.body.keys()],['file']);assert.equal(await call.body.get('file').text(),'SYNTHETIC IMAGE BYTES');assert.equal(call.config.headers['Idempotency-Key'],'stable-upload-key')}
})

test('original creation form saves human snapshot then confirms then invokes existing linked draft endpoint; never auto submits',async()=>{
  const calls=[],job={id:'job',intakeId:'source',state:'SUCCEEDED',reviewVersion:0,candidate:{fields:{ownerName:'识别姓名'},items:[{quantity:''}]}}
  const client={post:async(path,body,config)=>{calls.push({path,body,config});return {data:{data:path.endsWith('/draft')?{id:'draft',status:'DRAFT'}:{...job,reviewVersion:1}}}}}
  const api=ocrIntakeApi(client),form={...blankOcrDraft(),ownerName:'手工修正',accidentRegionId:'3'}
  assert.equal((await api.createReviewedDraft(job,form,{ownerName:'KEEP_CURRENT'})).id,'draft')
  assert.deepEqual(calls.map(call=>call.path),['/ocr/job/review','/ocr/job/confirm','/ocr/job/draft'])
  assert.equal(calls[0].body.candidate.origin,'HUMAN_REVISION');assert.equal(calls[0].body.candidate.draft.ownerName,'手工修正')
  assert.equal(calls[0].body.candidate.items[0].quantity,'');assert.equal(calls[0].body.candidate.formMergeDecisions.ownerName,'KEEP_CURRENT')
  assert.equal(calls[1].body.expectedVersion,1);assert.deepEqual(calls[2].body.draft,calls[0].body.candidate.draft)
})
test('a lost review response reuses same review body and idempotency key on click retry',async()=>{
  const calls=[];let fail=true
  const client={post:async(path,body,config)=>{calls.push({path,body,config});if(fail){fail=false;throw new Error('offline')};return {data:{data:{id:'draft',reviewVersion:2}}}}}
  const api=ocrIntakeApi(client),job={id:'job',intakeId:'source',state:'SUCCEEDED',reviewVersion:1},form=blankOcrDraft()
  await assert.rejects(api.createReviewedDraft(job,form),/offline/)
  await api.createReviewedDraft(job,form)
  assert.equal(calls[0].config.headers['Idempotency-Key'],calls[1].config.headers['Idempotency-Key'])
  assert.deepEqual(calls[0].body,calls[1].body)
})
test('failed/pending and case-file OCR tasks cannot create pre-case drafts',async()=>{
  let calls=0;const api=ocrIntakeApi({post:async()=>{calls++;throw new Error('must not call')}})
  for(const job of [{state:'RUNNING',intakeId:'source'},{state:'FAILED',intakeId:'source'},{state:'SUCCEEDED'}])await assert.rejects(api.createReviewedDraft(job,blankOcrDraft()))
  assert.equal(calls,0)
})
