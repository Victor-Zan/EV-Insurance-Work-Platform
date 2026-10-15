import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'
import * as Forms from '../src/features/materials/ocr-form.ts'
import {readChatFiles} from '../src/features/workorders/intake.ts'

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
  const result=body.text.includes('\n')?{fields:{claimNo:'TEXT-SYNTHETIC'},evidence:{ownerName:[{value:'候选甲'},{value:'候选乙'}]}}:{fields:{ownerName:body.text.split('：')[1]}}
  return {data:{data:result}}
 }}
 code=code.replace(/import\s*\{\s*readChatFiles\s*\}\s*from\s*['"][^'"]+['"];?/g,'const readChatFiles=__readChatFiles;')
 const component=new Function('__vue','__client','__forms','__readChatFiles',code)(Vue,client,Forms,readChatFiles)
 const context=component.setup({form,locked:false},{expose(){},emit(name,value){if(name==='merge')Object.assign(form,value);if(name==='blocked')blocked=value}})
 return {context,form,requests,blocked:()=>blocked}
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
