import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'
const {descriptor}=parse(readFileSync(new URL('../src/features/funds/FundsAssessment.vue',import.meta.url),'utf8'))
function fixture(){
 let code=ts.transpileModule(compileScript(descriptor,{id:'funds-assessment-test'}).content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>'const {'+names.replace(/\s+as\s+/g,':')+'}=__vue;').replace(/import .*from ['"].*['"];?/g,'').replace('export default','return')
 code='const httpClient=__client,ElDialog={};const URL=__urls;'+code
 let quote={assessment:{id:'assessment-one',lossFileId:'loss-one',amount:'620.00',sourceCurrent:true},gate:{customerServiceConfirmationRecorded:true}},fileType='image/png',unmount,responder,created=0
 const calls=[],revoked=[]
 const client={async get(path){calls.push(path);if(responder)return responder(path);return path.startsWith('/quotations/')?{data:{data:quote}}:{data:{type:path.includes('/preview?')?'image/png':fileType},headers:{'x-page-count':'3'}}}}
 const component=new Function('__vue','__client','__urls',code)({...Vue,watch(){},onUnmounted(fn){unmount=fn}},client,{createObjectURL(){return 'blob:funds-'+created++},revokeObjectURL(url){revoked.push(url)}})
 const context=component.setup({caseId:'synthetic-funds-assessment'},{expose(){}})
 return {context,calls,revoked,unmount:()=>unmount(),setQuote(next){quote=next},pdf(){fileType='application/pdf'},respond(fn){responder=fn}}
}
test('shows the confirmed loss amount and its linked original; images and unmount release blob URLs',async()=>{
 const f=fixture();await f.context.load();assert.equal(f.context.confirmed.value,true);assert.equal(f.context.quote.value.assessment.amount,'620.00');assert.deepEqual(f.calls,['/quotations/cases/synthetic-funds-assessment','/materials/loss-one/download']);assert.equal(f.context.preview.value,f.context.original.value);f.unmount();assert.deepEqual(f.revoked,['blob:funds-0'])
})
test('PDF pages use the protected preview, retain original download and clean up prior pages',async()=>{
 const f=fixture();f.pdf();await f.context.load();assert.equal(f.context.pages.value,3);assert.notEqual(f.context.preview.value,f.context.original.value);assert.equal(f.calls.at(-1),'/materials/loss-one/preview?page=1')
 f.context.page.value=2;await f.context.load();assert.equal(f.calls.at(-1),'/materials/loss-one/preview?page=2');assert.deepEqual(f.revoked,['blob:funds-0','blob:funds-1']);f.unmount();assert.equal(f.revoked.length,4)
})
test('missing sources, unconfirmed amounts and failed downloads are explicit; older responses cannot replace newer data',async()=>{
 const f=fixture();f.setQuote({assessment:{amount:'620.00',sourceCurrent:false},gate:{customerServiceConfirmationRecorded:false}});await f.context.load();assert.equal(f.context.confirmed.value,false);assert.match(f.context.error.value,/未关联核损单/)
 f.respond(async()=>{throw Error('合成网络错误')});await f.context.load();assert.equal(f.context.error.value,'合成网络错误');assert.equal(f.context.preview.value,'')
 let resolve;f.respond(()=>new Promise(r=>{resolve=r}));const old=f.context.load();f.respond(async()=>({data:{data:{assessment:{lossFileId:undefined,amount:'800.00',sourceCurrent:false},gate:{customerServiceConfirmationRecorded:false}}}}));await f.context.load();resolve({data:{data:{assessment:{lossFileId:'old-file',amount:'1.00',sourceCurrent:true},gate:{customerServiceConfirmationRecorded:true}}}});await old;assert.equal(f.context.quote.value.assessment.amount,'800.00');assert.equal(f.context.preview.value,'')
})
