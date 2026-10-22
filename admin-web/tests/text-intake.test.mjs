import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'
import * as Forms from '../src/features/materials/ocr-form.ts'
import * as Intake from '../src/features/workorders/intake.ts'
import {BlobWriter,TextReader,ZipWriter} from '@zip.js/zip.js'
import {mergeIntakeDraft} from '../src/features/workorders/intake-values.ts'

function intake(){
 const {descriptor}=parse(readFileSync(new URL('../src/features/workorders/TextFormIntake.vue',import.meta.url),'utf8'))
 let code=ts.transpileModule(compileScript(descriptor,{id:'text-test'}).content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>'const {'+names.replace(/\s+as\s+/g,':')+'}=__vue;')
 code=code.replace(/import\s*\{\s*httpClient\s*\}\s*from\s*['"][^'"]+['"];?/g,'const httpClient=__client;')
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"][^'"]*ocr-form['"];?/g,(_,names)=>'const {'+names+'}=__forms;').replace('export default','return')
 const form=Vue.reactive(Forms.blankOcrDraft()),requests=[];let blocked=false
 const client={async post(url,body){
  requests.push({url,body:structuredClone(body)})
  if(url.endsWith('/draft'))return {data:{data:{id:'synthetic-draft',status:'DRAFT'}}}
  if(body.text.includes('标的车牌'))return {data:{data:{fields:{vehiclePlate:'粤BD27391',surveyorName:'合成'},evidence:{surveyorName:[{value:'合成',requiresConfirmation:true},{value:'另名'}]}}}}
  const result=body.text.includes('\n')?{fields:{claimNo:'TEXT-SYNTHETIC'},evidence:{ownerName:[{value:'候选甲'},{value:'候选乙'}]}}:{fields:{ownerName:body.text.split('：')[1]}}
  return {data:{data:result}}
 }}
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"][^'"]*intake['"];?/g,(_,names)=>'const {'+names+'}=__intake;')
 code=code.replace(/import\s*\{\s*mergeIntakeDraft\s*\}\s*from\s*['"][^'"]+['"];?/g,'const mergeIntakeDraft=__mergeIntakeDraft;')
 const component=new Function('__vue','__client','__forms','__intake','__mergeIntakeDraft',code)(Vue,client,Forms,Intake,mergeIntakeDraft)
 const props=Vue.reactive({form,locked:false,photos:[]})
 const context=component.setup(props,{expose(){},emit(name,value){if(name==='merge')Object.assign(form,value);if(name==='blocked')blocked=value;if(name==='photos')props.photos.push(...value)}})
 return {context,form,props,requests,blocked:()=>blocked}
}
test('ambiguous text needs an explicit choice; retained original and adopted China time stay aligned',async()=>{
 const {context,form,requests}=intake();const original='车主姓名：候选甲\n车主姓名：候选乙'
 context.text.value=original;context.changed();await context.extract();assert.equal(form.ownerName,'');assert.deepEqual(context.ambiguities.value[0].values,['候选甲','候选乙'])
 context.confirmed.value=true;await assert.rejects(context.createReviewedDraft(form),/冲突/)
 await context.selectAmbiguity('ownerName','候选乙');assert.equal(form.ownerName,'候选乙');assert.equal(context.text.value,original)
 form.accidentAt='2026-10-14T09:30:00';await context.createReviewedDraft(form)
 const body=requests.find(r=>r.url.endsWith('/draft')).body;assert.equal(body.text,original);assert.equal(body.draft.ownerName,'候选乙');assert.equal(body.draft.accidentAt,'2026-10-14T01:30:00.000Z');assert.equal(requests.some(r=>r.url.includes('/ocr')),false)
})
test('TXT reading blocks creation immediately and releases the busy state after import',async()=>{
 const {context,form,blocked}=intake();let release
 const file={name:'synthetic.txt',size:40,arrayBuffer:()=>new Promise(resolve=>{release=resolve})}
 const reading=context.importText({target:{files:[file],hasAttribute:()=>false}});assert.equal(blocked(),true);assert.equal(context.busy.value,true)
 await assert.rejects(context.createReviewedDraft(form),/读取或提取中/)
 release(new TextEncoder().encode('车主姓名：候选甲').buffer);await reading;assert.equal(context.text.value,'车主姓名：候选甲');assert.equal(context.busy.value,false);assert.equal(blocked(),true)
})

test('TXT and folder imports immediately extract the insured plate and surveyor name without a separate name confirmation',async()=>{
 for(const folder of [false,true]){
  const {context,form,requests}=intake()
  const file=new File(['标的车牌：粤BD27391\n·车险查勘合成'],'synthetic.txt')
  await context.importText({target:{files:[file],hasAttribute:()=>folder}})
  assert.equal(form.vehiclePlate,'粤BD27391');assert.equal(form.surveyorName,'合成');assert.deepEqual(context.ambiguities.value,[])
  assert.equal(requests.filter(row=>row.url.endsWith('/extract')).length,1)
 }
})

test('ZIP imports photos into the shared list and extracts text without OCR',async()=>{
 const {context,props,requests}=intake(),writer=new ZipWriter(new BlobWriter(),{useWebWorkers:false})
 await writer.add('聊天/记录.txt',new TextReader('标的车牌：粤BD27391'))
 await writer.add('聊天/照片.jpg',new TextReader('synthetic-image'))
 const input={files:[new File([await writer.close()],'聊天.zip')],hasAttribute:()=>false,value:'selected'}
 await context.importText({target:input})
 assert.equal(props.photos.length,1);assert.equal(props.photos[0].name,'照片.jpg')
 assert.equal(requests.length,1);assert.ok(requests[0].url.endsWith('/extract'));assert.equal(input.value,'')
 assert.match(context.importStatus.value,/聊天.zip.*1张/)
})
test('separate images append without changing reviewed text, and reject invalid or excessive batches atomically',async()=>{
 const {context,props,requests}=intake()
 context.text.value='已核对文字';context.source.value='已核对文字';context.confirmed.value=true
 const input={files:[new File(['image'],'first.jpg')],value:'selected'}
 context.importPhotos({target:input});assert.equal(props.photos.length,1);assert.equal(input.value,'')
 assert.equal(context.confirmed.value,true);assert.equal(context.text.value,'已核对文字');assert.equal(requests.length,0)
 input.files=[new File(['image'],'second.png'),new File(['bad'],'bad.pdf')];context.importPhotos({target:input})
 assert.equal(props.photos.length,1);assert.match(context.photoError.value,/JPG\/PNG/)
 input.files=Array.from({length:20},()=>new File(['image'],'extra.jpg'));context.importPhotos({target:input})
 assert.equal(props.photos.length,1);assert.match(context.photoError.value,/最多20张/)
 input.files=[new File(['image'],'second.png')];context.importPhotos({target:input})
 assert.equal(props.photos.length,2);assert.equal(context.photoError.value,'')
})
test('failed package validation preserves the old text, confirmation and photos, and can be retried',async()=>{
 const {context,props,requests,blocked}=intake()
 context.text.value='已核对文字';context.source.value='已核对文字';context.confirmed.value=true
 props.photos=Array.from({length:20},()=>new File(['image'],'existing.jpg'))
 const input={files:[new File(['标的车牌：粤BD27391'],'a.txt'),new File(['image'],'new.jpg')],hasAttribute:()=>true}
 await context.importText({target:input})
 assert.match(context.message.value,/最多20张/);assert.equal(context.text.value,'已核对文字');assert.equal(context.confirmed.value,true)
 assert.equal(props.photos.length,20);assert.equal(requests.length,0);assert.equal(blocked(),false)
 props.photos.pop();await context.importText({target:input})
 assert.equal(props.photos.length,20);assert.equal(context.message.value,'');assert.equal(requests.length,1);assert.equal(blocked(),true)
})
test('locked controls do not import text or photos',async()=>{
 const {context,props,requests}=intake();props.locked=true
 await context.importText({target:{files:[new File(['text'],'a.txt')]}})
 context.importPhotos({target:{files:[new File(['image'],'a.jpg')]}})
 assert.equal(props.photos.length,0);assert.equal(context.text.value,'');assert.equal(requests.length,0)
})
