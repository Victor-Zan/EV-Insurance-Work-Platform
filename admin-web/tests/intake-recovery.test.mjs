import test from 'node:test'
import assert from 'node:assert/strict'
import {saveIntake,submitReviewedDraft,openSavedCase} from '../src/features/workorders/intake-save.ts'
import {identityPatch,plateCheck,surveyorPatch,surveyorText,mergeIntakeDraft} from '../src/features/workorders/intake-values.ts'
import {blankOcrDraft} from '../src/features/materials/ocr-form.ts'

const draft={id:'synthetic-intake',status:'DRAFT'},formal={...draft,status:'PENDING_DISPATCH'}
test('plate extraction cannot silently replace a frame number already typed into the combined input',()=>{
 const current={...blankOcrDraft(),vehicleVin:'LHGCM82633A027391'}
 const result=mergeIntakeDraft(current,{vehiclePlate:'粤B8Z71M'})
 assert.equal(result.merged.vehicleVin,current.vehicleVin);assert.equal(result.merged.vehiclePlate,'');assert.deepEqual(result.conflicts,[{field:'vehiclePlate',incoming:'粤B8Z71M'}])
})
function fixture(){
 const calls=[],saved=[],stages=[]
 const api={async detail(){calls.push('detail');return draft},async update(){calls.push('update');return draft},async submit(id,confirmed,reason,key){calls.push({op:'submit',id,confirmed,reason,key});return formal}}
 const options={api,form:blankOcrDraft(),formal:true,submitKey:'stable-submit-key',async create(){calls.push('create');return draft},async upload(){calls.push('upload')},saved(order){saved.push(order)},stage(stage){stages.push(stage)},async confirmDuplicate(){calls.push('confirm');return '合成核对原因'}}
 return {api,options,calls,saved,stages}
}
test('one identifier input maps either plate or frame and surveyor contact stays split in storage',()=>{
 assert.deepEqual(identityPatch('粤 b·8z71m'),{vehiclePlate:'粤B8Z71M',vehicleVin:''})
 for(const input of ['LHGCM82633A027391','123456789012345'])assert.deepEqual(identityPatch(input),{vehiclePlate:'',vehicleVin:input})
 assert.deepEqual(identityPatch('CUSTOM-273918','VIN'),{vehiclePlate:'',vehicleVin:'CUSTOM-273918'})
 assert.deepEqual(surveyorPatch('合成查勘 + 13926018473'),{surveyorName:'合成查勘',surveyorPhone:'13926018473'})
 assert.deepEqual(surveyorPatch('13926018473'),{surveyorName:'',surveyorPhone:'13926018473'})
 assert.equal(surveyorText({...blankOcrDraft(),surveyorPhone:'13926018473'}),'13926018473')
 assert.equal(surveyorText({...blankOcrDraft(),surveyorName:'合成查勘',surveyorPhone:'13926018473'}),'合成查勘 13926018473')
 const manual=mergeIntakeDraft({...blankOcrDraft(),surveyorName:'手改姓名'},{surveyorName:'识别姓名'})
 assert.equal(manual.merged.surveyorName,'手改姓名');assert.deepEqual(manual.conflicts,[])
 for(const input of ['粤B8Z71M','京AD27391','沪A12345F'])assert.equal(plateCheck(input).review,false)
 assert.equal(plateCheck('北京2739186').acceptable,true);assert.equal(plateCheck('北京2739186').review,true)
 assert.equal(plateCheck('无牌???').acceptable,false)
})
test('a committed submission with a lost response reads server status and never sends a second POST',async()=>{
 const f=fixture();f.api.submit=async()=>{f.calls.push('submit');throw Error('响应丢失')};f.api.detail=async()=>{f.calls.push('detail');return formal}
 assert.deepEqual(await saveIntake(f.options),formal);assert.deepEqual(f.calls,['create','upload','submit','detail']);assert.deepEqual(f.saved,[draft,formal])
})
test('retrying a known draft that is already formal opens its record without updating or uploading again',async()=>{
 const f=fixture();f.options.pending=draft;f.api.detail=async()=>{f.calls.push('detail');return formal}
 assert.deepEqual(await saveIntake(f.options),formal);assert.deepEqual(f.calls,['detail'])
})
test('an actual upload failure retains the draft and stops before submission',async()=>{
 const f=fixture();f.options.upload=async()=>{throw Error('存储不可用')}
 await assert.rejects(saveIntake(f.options),/存储不可用/);assert.deepEqual(f.saved,[draft]);assert.deepEqual(f.stages,['DRAFT','PHOTOS']);assert.equal(f.calls.includes('create'),true);assert.equal(f.calls.some(c=>typeof c==='object'&&c.op==='submit'),false)
})
test('a failed submission with the draft still present propagates the real failure and reuses its key',async()=>{
 const f=fixture();let failed=true
 f.api.submit=async(id,confirmed,reason,key)=>{f.calls.push({op:'submit',key});if(failed){failed=false;throw Error('提交不可用')}return formal}
 await assert.rejects(saveIntake(f.options),/提交不可用/);f.options.pending=draft;assert.deepEqual(await saveIntake(f.options),formal)
 assert.equal(f.calls.filter(c=>c==='create').length,1);assert.deepEqual(f.calls.filter(c=>typeof c==='object').map(c=>c.key),['stable-submit-key','stable-submit-key'])
})
test('a lost response after duplicate confirmation also reconciles the committed result',async()=>{
 const f=fixture();let attempts=0
 f.api.submit=async(id,confirmed,reason,key)=>{f.calls.push({op:'submit',confirmed,reason,key});if(++attempts===1)throw Object.assign(Error('疑似重复'),{code:'POSSIBLE_DUPLICATE'});throw Error('确认后响应丢失')}
 f.api.detail=async()=>formal
 assert.deepEqual(await submitReviewedDraft(f.api,draft.id,'stable-submit-key',f.options.confirmDuplicate),formal)
 const writes=f.calls.filter(c=>typeof c==='object');assert.equal(writes.length,2);assert.equal(writes[1].confirmed,true);assert.equal(writes[1].reason,'合成核对原因');assert.equal(writes[0].key,writes[1].key)
})
test('a stale detail chunk triggers a GET navigation to the saved case and cannot turn into a submission error',async()=>{
 const calls=[]
 await openSavedCase({async push(path){calls.push(path);throw Error('Failed to fetch dynamically imported module')},resolve(path){return {href:path}}},draft.id,href=>calls.push({reload:href}))
 assert.deepEqual(calls,['/work-orders/synthetic-intake',{reload:'/work-orders/synthetic-intake'}])
})
