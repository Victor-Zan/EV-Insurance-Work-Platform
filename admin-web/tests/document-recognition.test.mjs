import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'

// Exercise the actual component setup and its HTTP retry sequence, without an OCR supplier or DOM.
function setupRecognition(){
 const source=readFileSync(new URL('../src/features/materials/DocumentRecognition.vue',import.meta.url),'utf8')
 const {descriptor}=parse(source),script=compileScript(descriptor,{id:'recognition-test'})
 let code=ts.transpileModule(script.content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>'const {'+names.replace(/\s+as\s+/g,':')+'}=__vue;')
 code=code.replace(/import\s*\{\s*httpClient\s*\}\s*from\s*['"][^'"]+['"];?/g,'const httpClient=__client;').replace('export default','return')
 let stored={id:'job-1',fileId:'file-1',state:'FAILED',provider:'MOCK_V1',reviewVersion:0,confirmed:false,errorCode:'SYNTHETIC_FAILURE'},reviewLost=true,confirmLost=true
 const requests=[],commands=new Map(),snapshot=()=>({data:{data:structuredClone(stored)}})
 const client={
  async get(url){if(url.includes('/download'))return {data:new Blob(['synthetic'],{type:'application/pdf'})};if(url.startsWith('/materials/cases/'))return {data:{data:{records:[{id:'file-1',name:'synthetic.pdf',state:'ACTIVE',category:'LOSS_ASSESSMENT'}],total:1}}};if(url==='/ocr/configuration')return {data:{data:{provider:'MOCK_V1'}}};return snapshot()},
  async post(url,body,options){requests.push({url,body:structuredClone(body),key:options?.headers?.['Idempotency-Key']});if(url==='/ocr')return snapshot()
   if(url.endsWith('/review')){const key=options.headers['Idempotency-Key'];if(commands.has(key))return snapshot();stored={...stored,reviewVersion:body.expectedVersion+1,candidate:structuredClone(body.candidate),confirmed:false};commands.set(key,body);if(reviewLost){reviewLost=false;throw Error('lost review response')}return snapshot()}
   if(url.endsWith('/confirm')){assert.equal(body.expectedVersion,stored.reviewVersion);stored.confirmed=true;if(confirmLost){confirmLost=false;throw Error('lost confirm response')}return snapshot()}
   throw Error('Unexpected request')
  },
 }
 const component=new Function('__vue','__client',code)({...Vue,onMounted(){},onUnmounted(){}},client)
 const context=component.setup({caseId:'case-1',category:'LOSS_ASSESSMENT'},{expose(){},emit(){}})
 return {context,requests,stored:()=>stored}
}
test('explicit adoption confirms failed recognition without a checkbox and preserves lost review/confirm retries',async()=>{
 const {context,requests,stored}=setupRecognition();await context.load()
 await assert.rejects(context.confirm('18.00',{}),/请先识别原件/)
 await context.recognize('file-1');assert.equal(requests.some(r=>r.url.endsWith('/confirm')),false)
 const preview=context.preview.value;context.preview.value=''
 await assert.rejects(context.confirm('18.00',{}),/加载并查看原件/);context.preview.value=preview
 await assert.rejects(context.confirm('18.00',{}),/lost review response/)
 await assert.rejects(context.confirm('18.00',{}),/lost confirm response/)
 assert.deepEqual(await context.confirm('18.00',{}),{jobId:'job-1',revision:1})
 const reviews=requests.filter(r=>r.url.endsWith('/review'))
 assert.equal(reviews.length,2);assert.equal(reviews[0].key,reviews[1].key);assert.deepEqual(reviews[0].body,reviews[1].body)
 assert.equal(stored().state,'FAILED');assert.equal(stored().reviewVersion,1);assert.equal(stored().candidate.originalReviewed,true)
 assert.equal(requests.filter(r=>r.url==='/ocr').length,1)
 assert.deepEqual(await context.confirm('19.00',{}),{jobId:'job-1',revision:2})
 assert.equal(stored().candidate.adopted,'19.00');URL.revokeObjectURL(context.preview.value)
})
test('the independently deployed H5 recognition leaf retains the same privacy and retry behavior',()=>{
 const admin=readFileSync(new URL('../src/features/materials/DocumentRecognition.vue',import.meta.url),'utf8')
 const h5=readFileSync(new URL('../../h5-web/src/features/materials/DocumentRecognition.vue',import.meta.url),'utf8')
 assert.equal(h5,admin)
})
