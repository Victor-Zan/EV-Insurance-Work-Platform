import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import {compile} from '@vue/compiler-dom'
import ts from 'typescript'
import * as Vue from 'vue'
import * as money from '../src/features/quotation/quote-review.ts'
const {descriptor}=parse(readFileSync(new URL('../src/features/quotation/RepairAuthorizationReview.vue',import.meta.url),'utf8'))
function fixture(){
 let code=ts.transpileModule(compileScript(descriptor,{id:'authorization-price-test'}).content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>'const {'+names.replace(/\s+as\s+/g,':')+'}=__vue;').replace(/import .*from ['"].*['"];?/g,'').replace('export default','return')
 code='const {moneyCents,yuan}=__money;const httpClient=__client;const URL=__urls;'+code
 const props=Vue.reactive({caseId:'synthetic-authorization',quote:{formalQuote:{id:'formal-1',total:'30.00'},assessment:{id:'assessment-1',lossFileId:'loss-1',amount:'18.00',sourceCurrent:true}},readonly:false}),calls=[],revoked=[];let created=0,unmount,respond=async()=>({data:{type:'image/png'}})
 const client={async get(path){calls.push(path);return respond(path)}}
 const component=new Function('__vue','__money','__client','__urls',code)({...Vue,watch(){},onUnmounted(fn){unmount=fn}},money,client,{createObjectURL(){return 'blob:authorization-'+created++},revokeObjectURL(url){revoked.push(url)}})
 const context=component.setup(props,{expose(){}})
 return {context,props,calls,revoked,unmount(){unmount()},respond(fn){respond=fn}}
}
test('final price confirms on submission with loaded documents and exact adopted loss amount; formal total may differ',async()=>{
 const f=fixture();assert.throws(()=>f.context.confirmation(),/加载并核对/)
 await f.context.load();assert.deepEqual(f.calls,['/quotations/cases/synthetic-authorization/formal-quotes/formal-1/preview?page=1','/materials/loss-1/download'])
 assert.throws(()=>f.context.confirmation(),/有效确认金额/)
 f.context.amount.value='30.00';assert.throws(()=>f.context.confirmation(),/必须与最终核损单/)
 f.context.amount.value='18.001';assert.throws(()=>f.context.confirmation(),/有效确认金额/)
 f.context.amount.value='18';assert.deepEqual(f.context.confirmation(),{assessmentId:'assessment-1',lossFileId:'loss-1',amount:'18.00',originalReviewed:true})
 f.props.quote.assessment.sourceCurrent=false;assert.throws(()=>f.context.confirmation(),/加载并核对/)
 await f.context.load();assert.match(f.context.error.value,/修正核损/);assert.equal(f.context.amount.value,'')
})
test('failed or stale document downloads cannot enable confirmation and object URLs are cleaned',async()=>{
 const f=fixture();f.respond(async()=>{throw Error('下载失败')});await f.context.load();assert.match(f.context.error.value,/下载失败/);assert.throws(()=>f.context.confirmation(),/加载并核对/)
 f.respond(async()=>({data:{type:'image/png'}}));await f.context.load();assert.equal(f.context.error.value,'');assert.ok(f.context.pdf.value)
 const deferred=[];f.respond(()=>new Promise(resolve=>deferred.push(resolve)));const pending=f.context.load();f.unmount();deferred.forEach(resolve=>resolve({data:{type:'image/png'}}));await pending
 assert.equal(f.context.pdf.value,'');assert.equal(f.context.loss.value,'');assert.equal(f.revoked.length,2)
})
test('authorization review displays both originals and retains only the required amount input for read-only accounts',async()=>{
 const f=fixture();await f.context.load()
 const {code}=compile(descriptor.template.content,{mode:'function',prefixIdentifiers:true}),render=new Function('Vue',code)(Vue)
 function nodes(){const out=[];function visit(v){if(!v||typeof v!=='object')return;if(typeof v.type==='string')out.push(v);if(Array.isArray(v.children))v.children.forEach(visit)}visit(render(Vue.proxyRefs({...f.context,...f.props}),[]));return out}
 assert.equal(nodes().some(n=>n.type==='img'&&n.props.alt==='正式报价单原件'),true);assert.equal(nodes().some(n=>n.type==='img'&&n.props.alt==='最终核损单原件'),true)
 assert.equal(nodes().filter(n=>n.type==='input').length,1);assert.equal(nodes().some(n=>n.type==='input'&&n.props.type==='checkbox'),false)
 f.props.readonly=true;assert.equal(nodes().filter(n=>n.type==='input').every(n=>n.props.disabled),true)
})
test('PDF loss documents use real rendered pages with pagination while retaining the downloadable original',async()=>{
 const f=fixture();f.respond(async path=>({data:{type:path.endsWith('/download')?'application/pdf':'image/png'},headers:{'x-page-count':path.includes('/materials/')?'2':'3'}}));await f.context.load()
 assert.equal(f.context.pages.value,3);assert.equal(f.context.lossPages.value,2)
 assert.equal(f.calls.includes('/materials/loss-1/preview?page=1'),true);assert.notEqual(f.context.lossImage.value,f.context.loss.value)
 f.context.page.value=2;f.context.lossPage.value=2;f.context.amount.value='18';await f.context.load()
 assert.ok(f.calls.includes('/quotations/cases/synthetic-authorization/formal-quotes/formal-1/preview?page=2'));assert.ok(f.calls.includes('/materials/loss-1/preview?page=2'));assert.equal(f.context.amount.value,'')
})
