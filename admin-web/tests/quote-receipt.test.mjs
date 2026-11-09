import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import {compile} from '@vue/compiler-dom'
import ts from 'typescript'
import * as Vue from 'vue'
import * as reviewHelpers from '../src/features/quotation/quote-review.ts'
import {formalPayload} from '../src/features/quotation/quotation.ts'

const source=readFileSync(new URL('../src/features/quotation/QuotationPanel.vue',import.meta.url),'utf8')
const {descriptor}=parse(source)
function fixture(dev=true,roles=['CUSTOMER_SERVICE']){
 let code=ts.transpileModule(compileScript(descriptor,{id:'quote-receipt-test'}).content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>'const {'+names.replace(/\s+as\s+/g,':')+'}=__vue;')
 code=code.replace(/import .*from ['"].*['"];?/g,'').replace('export default','return').replaceAll('import.meta.env.DEV','__dev')
 code='const RepairAuthorizationReview={},QuoteReviewWorkbench={},QuoteLines={},RecordList={},RecognitionSource={},ElDialog={},ElButton={};const {reviewLines,reviewPayload,reviewCalculation,moneyCents,yuan}=__helpers;const {httpClient,currentUser,quotationApi,formalPayload}=__deps;const {setTimeout,clearTimeout}=__timers;'+code
 const flow={currentNode:'SHOP_QUOTE',canAct:false,actorRole:'REPAIR_SHOP',materials:[{category:'SHOP_QUOTE',present:true,shopUploaded:false},{category:'ARRIVAL_PHOTO',present:false,shopUploaded:false}],nodes:[{key:'SHOP_QUOTE',label:'网点报价'}]}
 const props=Vue.reactive({caseId:'synthetic-receipt-case',workflow:flow}),events=[],calls=[],timers=new Map(),hooks={};let nextFlow=flow,resolveGet,nextQuote={version:0,rawQuote:null,gate:{},status:'ARRIVED'},postReply=async()=>{throw Error('Unexpected write')}
 const document={hidden:false}
 const httpClient={async get(path){calls.push(['get',path]);return resolveGet?await resolveGet():{data:{data:nextFlow}}}}
 const deps={httpClient,currentUser:Vue.ref({roles}),quotationApi:()=>({get:async()=>nextQuote,post:async(...args)=>{calls.push(['post',...args]);return postReply(...args)}}),formalPayload}
 const component=new Function('__vue','__deps','__dev','__timers','document','__helpers',code)({...Vue,onMounted(){},onActivated(){},onDeactivated(fn){hooks.deactivated=fn},onUnmounted(fn){hooks.unmounted=fn}},deps,dev,{setTimeout(fn,delay){const id=Symbol();timers.set(id,{fn,delay});return id},clearTimeout(id){timers.delete(id)}},document,reviewHelpers)
 const context=component.setup(props,{expose(){},emit(...event){events.push(event)}})
 return {context,props,events,calls,timers,hooks,document,setFlow(value){nextFlow=value},setQuote(value){nextQuote=value},deferGet(fn){resolveGet=fn},respondPost(fn){postReply=fn}}
}
function elements(f){
 const {code}=compile(descriptor.template.content,{mode:'function',prefixIdentifiers:true,expressionPlugins:['typescript']}),render=new Function('Vue',ts.transpileModule(code,{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText)(Vue)
 const nodes=[]
 function visit(v){if(!v||typeof v!=='object')return;if(typeof v.type==='string')nodes.push(v);if(Array.isArray(v.children))v.children.forEach(visit)}
 visit(render(Vue.proxyRefs({...f.context,...f.props}),[]));return nodes
}
test('waiting task shows shop provenance receipts, no upload controls; preview cannot submit or change workflow',async()=>{
 const f=fixture();await f.context.load()
 assert.equal(f.context.shopReceipts.value[0].received,false)
 f.props.workflow.materials[0].shopUploaded=true;assert.equal(f.context.shopReceipts.value[0].received,true)
 let nodes=elements(f);assert.equal(nodes.some(n=>n.type==='input'),false)
 f.context.openReviewPreview();assert.equal(f.context.editingNode.value,'QUOTE_REVIEW');assert.equal(f.props.workflow.currentNode,'SHOP_QUOTE')
 nodes=elements(f);assert.equal(nodes.find(n=>n.type==='QuoteReviewWorkbench').props.readonly,true)
 assert.equal(nodes.some(n=>n.type==='button'&&n.props?.class==='primary'),false)
 await assert.rejects(f.context.proceed(),/只读预览/);assert.equal(f.calls.length,0);assert.equal(f.events.some(e=>e[0]==='changed'),false)
})
test('production and non-customer-service accounts cannot open development preview',()=>{
 for(const f of [fixture(false),fixture(true,['ADMIN'])]){f.context.openReviewPreview();assert.equal(f.context.reviewPreview.value,false)}
})
function assessmentFixture(node='INSURER_ASSESSMENT'){
 const f=fixture();Object.assign(f.props,{claimNo:'SYNTH-CLAIM',insuranceCompany:'测试保险',vehicleVin:'SYNTH-VIN'})
 Object.assign(f.props.workflow,{currentNode:node,status:node==='REPAIR'?'WAITING_REPAIR_AUTHORIZATION':'WAITING_INSURER_ASSESSMENT',canAct:true})
 f.setQuote({version:2,status:f.props.workflow.status,rawQuote:{id:'raw',total:'400.00',lines:[]},formalQuote:{id:'formal',total:'560.60',lines:[]},gate:{}})
 return f
}
test('assessment shows exactly two decimal inputs, no confirmation dialog, and one final confirmation',async()=>{
 const f=assessmentFixture();await f.context.load();await Vue.nextTick()
 const nodes=elements(f),inputs=nodes.filter(n=>n.type==='input'&&n.props.readonly!==undefined)
 assert.deepEqual(inputs.map(n=>n.props.value),['SYNTH-CLAIM','测试保险','SYNTH-VIN'])
 assert.equal(nodes.filter(n=>n.type==='details'&&n.props.open).length,2)
 assert.equal(nodes.filter(n=>n.type==='input'&&n.props.inputmode==='decimal').length,2)
 assert.equal(source.includes('<ElDialog'),false);assert.equal(source.includes('记录保险确认'),false)
 assert.equal(nodes.find(n=>n.type==='button'&&n.props.class==='primary').children,'确认并进入维修')
 assert.equal(f.calls.some(c=>c[0]==='post'),false);assert.equal(f.context.confirmedAmount.value,'')
 f.context.assessment.value='560.6';f.context.confirmedAmount.value='500.00';f.context.dirty.value=true
 f.respondPost(async()=>({version:5,status:'REPAIRING',gate:{allowed:true},authorized:true}))
 await f.context.run(f.context.proceed)
 const posts=f.calls.filter(c=>c[0]==='post');assert.equal(posts.length,1);assert.equal(posts[0][1],'assessment-confirmation')
 assert.deepEqual(posts[0][2],{expectedVersion:2,amount:'560.60',confirmedAmount:'500.00',originalReviewed:true})
 assert.equal(f.context.data.value.status,'REPAIRING');assert.equal(f.context.dirty.value,false)
 assert.ok(f.events.some(e=>e[0]==='changed'))
})
test('assessment rejects invalid prices and preserves independent prices and key after failure',async()=>{
 const f=assessmentFixture();await f.context.load();await Vue.nextTick()
 for(const invalid of ['','-1','1.001','1e2','9'.repeat(37)]){
  f.context.assessment.value=invalid;f.context.confirmedAmount.value='560.60';await f.context.run(f.context.proceed)
  assert.ok(f.context.assessmentIssue.value)
  f.context.assessment.value='560.60';f.context.confirmedAmount.value=invalid;await f.context.run(f.context.proceed)
  assert.ok(f.context.assessmentIssue.value)
 }
 f.context.confirmedAmount.value='560.61';assert.equal(f.context.assessmentIssue.value,'')
 assert.equal(f.calls.some(c=>c[0]==='post'),false)
 f.context.assessment.value=' 560.60 ';f.context.confirmedAmount.value='560.6';f.context.dirty.value=true
 f.respondPost(async()=>{throw Error('保存失败，请重试')});await f.context.run(f.context.proceed)
 assert.equal(f.context.assessment.value,' 560.60 ');assert.equal(f.context.confirmedAmount.value,'560.6');assert.equal(f.context.dirty.value,true)
 f.respondPost(async()=>({version:5,status:'REPAIRING',gate:{}}));await f.context.run(f.context.proceed)
 const posts=f.calls.filter(c=>c[0]==='post');assert.equal(posts.length,2);assert.equal(posts[0][3],posts[1][3])
})
test('assessment refuses workflow and quote version changes, supports zero and pending legacy authorizations',async()=>{
 const f=assessmentFixture('REPAIR');f.setQuote({version:2,status:'WAITING_REPAIR_AUTHORIZATION',assessment:{amount:'0.00'},gate:{insurerConfirmationRecorded:true}})
 await f.context.load();await Vue.nextTick();assert.equal(f.context.assessment.value,'0.00');assert.equal(f.context.confirmedAmount.value,'')
 f.context.confirmedAmount.value='0';f.context.dirty.value=true
 f.setFlow({...f.props.workflow,currentNode:'REPAIR',canAct:false});await f.context.run(f.context.proceed)
 assert.match(f.context.message.value,/任务已变化/);assert.equal(f.calls.some(c=>c[0]==='post'),false)
 f.setFlow(f.props.workflow);f.setQuote({version:3,status:'WAITING_REPAIR_AUTHORIZATION',assessment:{amount:'5.00'},gate:{}});await f.context.load()
 await f.context.run(f.context.proceed);assert.match(f.context.message.value,/依据已变化/);assert.equal(f.context.assessment.value,'0.00')
 f.context.dirty.value=false;await f.context.load();assert.equal(f.context.assessment.value,'5.00');assert.equal(f.context.confirmedAmount.value,'')
 f.context.assessment.value='0';f.context.confirmedAmount.value='0.00'
 f.respondPost(async(_path,body)=>{assert.equal(body.amount,'0.00');assert.equal(body.expectedVersion,3);return {version:6,gate:{},status:'REPAIRING'}})
 await f.context.run(f.context.proceed);assert.equal(f.context.data.value.status,'REPAIRING')
})
test('busy assessment suppresses duplicate clicks and read-only accounts have no final confirmation',async()=>{
 const f=assessmentFixture();await f.context.load();await new Promise(r=>setImmediate(r));f.context.assessment.value='1';f.context.confirmedAmount.value='1.00'
 let resolve;f.respondPost(()=>new Promise(r=>{resolve=r}));const pending=f.context.run(f.context.proceed)
 await new Promise(r=>setImmediate(r));await f.context.run(f.context.proceed)
 assert.equal(f.calls.filter(c=>c[0]==='post').length,1);resolve({version:5,gate:{},status:'REPAIRING'});await pending
 const reader=fixture(true,['ADMIN']);Object.assign(reader.props.workflow,{currentNode:'INSURER_ASSESSMENT',canAct:false});await reader.context.load()
 assert.equal(elements(reader).some(n=>n.type==='button'&&n.props?.class==='primary'),false)
 assert.equal(elements(reader).filter(n=>n.type==='input'&&n.props.inputmode==='decimal').every(n=>n.props.disabled),true)
})

test('unsaved review edits cannot be silently submitted against a newer raw quotation',async()=>{
 const f=fixture();f.props.workflow.currentNode='QUOTE_REVIEW';f.props.workflow.canAct=true
 f.setQuote({version:1,rawQuote:{id:'raw-first',lines:[{description:'原始项目',quantity:1,unitPrice:'10.00'}]}});await f.context.load()
 f.context.dirty.value=true;f.context.reviewedLines.value[0].unitPrice='12.00'
 f.setQuote({version:2,rawQuote:{id:'raw-second',lines:[{description:'新版项目',quantity:2,unitPrice:'20.00'}]}});await f.context.load()
 assert.equal(f.context.reviewedLines.value[0].unitPrice,'12.00');assert.equal(f.context.reviewStale.value,true)
 await assert.rejects(f.context.proceed(),/报价版本已变化/);assert.equal(f.calls.some(c=>c[0]==='post'),false)
 f.context.dirty.value=false;await f.context.load();assert.equal(f.context.reviewStale.value,false);assert.equal(f.context.reviewedLines.value[0].description,'新版项目')
 f.setQuote({version:3,rawQuote:{id:'raw-second',lines:[]},formalQuote:{rawQuoteId:'raw-second',mode:'PERCENTAGE',markupValue:'7.50',reviewedLines:[{description:'已保存核对项',quantity:3,unitPrice:'48.25'}]}});await f.context.load()
 assert.equal(f.context.markup.value,'7.50');assert.equal(f.context.reviewedLines.value[0].description,'已保存核对项');assert.equal(f.context.dirty.value,false)
})
test('receipt polling refreshes changed materials or submitted quote; hidden, stopped and stale polls cannot update',async()=>{
 const f=fixture();await f.context.pollReceipt();assert.equal(f.events.length,0);assert.equal([...f.timers.values()][0].delay,8000)
 f.context.stopReceiptPolling();f.document.hidden=true;await f.context.pollReceipt();assert.equal(f.calls.length,1)
 f.context.stopReceiptPolling();f.document.hidden=false;f.setFlow({...f.props.workflow,currentNode:'QUOTE_REVIEW'});await f.context.pollReceipt();assert.deepEqual(f.events.at(-1),['changed'])
 f.context.stopReceiptPolling();f.events.length=0;f.setFlow({...f.props.workflow,materials:[]});await f.context.pollReceipt();assert.deepEqual(f.events.at(-1),['changed'])
 f.context.stopReceiptPolling();let resolve;f.deferGet(()=>new Promise(r=>{resolve=r}));const pending=f.context.pollReceipt();f.hooks.deactivated();f.events.length=0
 resolve({data:{data:{currentNode:'QUOTE_REVIEW'}}});await pending;assert.equal(f.events.length,0);assert.equal(f.timers.size,0)
})
test('shop-quote task never renders the shared material uploader, while other task uploads remain',()=>{
 const parent=readFileSync(new URL('../src/features/workorders/WorkOrderDetailView.vue',import.meta.url),'utf8')
 const opening=parent.match(/<section v-if="[^\n]+?data-reading="待补材料">/)[0]
 const {code}=compile(opening+'</section>',{mode:'function',prefixIdentifiers:true}),render=new Function('Vue',code)(Vue)
 const workflow={currentNode:'SHOP_QUOTE',materials:[{category:'SCENE_PHOTO',canUpload:true}]}
 assert.notEqual(render({workflow,activeSection:'quotation'},[]).type,'section')
 for(const node of ['QUOTE_REVIEW','INSURER_ASSESSMENT']){workflow.currentNode=node;assert.notEqual(render({workflow,activeSection:'quotation'},[]).type,'section')}
 workflow.currentNode='REPAIR';assert.equal(render({workflow,activeSection:'repair'},[]).type,'section')
})

test('formal review submits exactly the selected markup mode and restores fixed-amount history',async()=>{
 for(const mode of ['FIXED_AMOUNT','PERCENTAGE']){
  const f=fixture();Object.assign(f.props.workflow,{currentNode:'QUOTE_REVIEW',canAct:true})
  f.setQuote({version:1,rawQuote:{id:'raw-markup',lines:[{description:'核对项目',quantity:1,unitPrice:'460.00'}]}})
  await f.context.load()
  const workbench=elements(f).find(n=>n.type==='QuoteReviewWorkbench')
  workbench.props.onMode(mode)
  assert.equal(f.context.markup.value,'0.00');assert.equal(f.context.dirty.value,true)
  workbench.props.onValue(mode==='FIXED_AMOUNT'?'69.00':'15.00')
  f.respondPost(async()=>({version:2,rawQuote:{id:'raw-markup',lines:[]},gate:{}}))
  await f.context.proceed()
  const body=f.calls.find(call=>call[0]==='post')[2]
  assert.equal(body.mode,mode);assert.equal(body.reviewedLines[0].unitPrice,'460.00')
  if(mode==='FIXED_AMOUNT'){assert.equal(body.fixedAmount,'69.00');assert.equal('percentage' in body,false)}
  else{assert.equal(body.percentage,'15.00');assert.equal('fixedAmount' in body,false)}
 }
 const f=fixture()
 f.setQuote({version:3,rawQuote:{id:'raw-fixed',lines:[]},formalQuote:{rawQuoteId:'raw-fixed',mode:'FIXED_AMOUNT',markupValue:'69.00',reviewedLines:[]}})
 await f.context.load()
 assert.equal(f.context.mode.value,'FIXED_AMOUNT');assert.equal(f.context.markup.value,'69.00')
})

test('assessment identifiers follow saved case facts through read-only fields without OCR replacement',async()=>{
 const f=fixture();Object.assign(f.props,{claimNo:'CASE-123',insuranceCompany:'案件保险公司'})
 Object.assign(f.props.workflow,{currentNode:'INSURER_ASSESSMENT',status:'WAITING_INSURER_ASSESSMENT'})
 await f.context.load();await Vue.nextTick()
 const values=()=>elements(f).filter(node=>node.type==='input'&&node.props.readonly!==undefined).map(node=>node.props.value)
 assert.deepEqual(values(),['CASE-123','案件保险公司','未填写'])
 f.context.assessment.value='470.00';f.props.claimNo='UPDATED-CASE';await Vue.nextTick()
 assert.deepEqual(values(),['UPDATED-CASE','案件保险公司','未填写'])
 assert.equal(f.context.assessment.value,'470.00')
 assert.equal(elements(f).some(node=>node.type==='DocumentRecognition'),false)
})
