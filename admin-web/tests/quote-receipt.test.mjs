import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import {compile} from '@vue/compiler-dom'
import ts from 'typescript'
import * as Vue from 'vue'
import * as reviewHelpers from '../src/features/quotation/quote-review.ts'

const source=readFileSync(new URL('../src/features/quotation/QuotationPanel.vue',import.meta.url),'utf8')
const {descriptor}=parse(source)
function fixture(dev=true,roles=['CUSTOMER_SERVICE']){
 let code=ts.transpileModule(compileScript(descriptor,{id:'quote-receipt-test'}).content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>'const {'+names.replace(/\s+as\s+/g,':')+'}=__vue;')
 code=code.replace(/import .*from ['"].*['"];?/g,'').replace('export default','return').replaceAll('import.meta.env.DEV','__dev')
 code='const RepairAuthorizationReview={},QuoteReviewWorkbench={},QuoteLines={},RecordList={},DocumentRecognition={},RecognitionSource={},ElDrawer={};const {reviewLines,reviewPayload,reviewCalculation}=__helpers;const {httpClient,currentUser,quotationApi,formalPayload}=__deps;const {setTimeout,clearTimeout}=__timers;'+code
 const flow={currentNode:'SHOP_QUOTE',canAct:false,actorRole:'REPAIR_SHOP',materials:[{category:'SHOP_QUOTE',present:true,shopUploaded:false},{category:'ARRIVAL_PHOTO',present:false,shopUploaded:false}],nodes:[{key:'SHOP_QUOTE',label:'网点报价'}]}
 const props=Vue.reactive({caseId:'synthetic-receipt-case',workflow:flow}),events=[],calls=[],timers=new Map(),hooks={};let nextFlow=flow,resolveGet,nextQuote={version:0,rawQuote:null,gate:{},status:'ARRIVED'}
 const document={hidden:false}
 const httpClient={async get(path){calls.push(['get',path]);return resolveGet?await resolveGet():{data:{data:nextFlow}}}}
 const deps={httpClient,currentUser:Vue.ref({roles}),quotationApi:()=>({get:async()=>nextQuote,post:async()=>{calls.push(['post']);throw Error('Unexpected write')}}),formalPayload(){throw Error('Unexpected formal quote')}}
 const component=new Function('__vue','__deps','__dev','__timers','document','__helpers',code)({...Vue,onMounted(){},onActivated(){},onDeactivated(fn){hooks.deactivated=fn},onUnmounted(fn){hooks.unmounted=fn}},deps,dev,{setTimeout(fn,delay){const id=Symbol();timers.set(id,{fn,delay});return id},clearTimeout(id){timers.delete(id)}},document,reviewHelpers)
 const context=component.setup(props,{expose(){},emit(...event){events.push(event)}})
 return {context,props,events,calls,timers,hooks,document,setFlow(value){nextFlow=value},setQuote(value){nextQuote=value},deferGet(fn){resolveGet=fn}}
}
function elements(f){
 const {code}=compile(descriptor.template.content,{mode:'function',prefixIdentifiers:true,expressionPlugins:['typescript']}),render=new Function('Vue',ts.transpileModule(code,{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText)(Vue)
 const nodes=[]
 function visit(v){if(!v||typeof v!=='object')return;if(typeof v.type==='string')nodes.push(v);if(Array.isArray(v.children))v.children.forEach(visit)}
 visit(render(Vue.proxyRefs({...f.context,workflow:f.props.workflow}),[]));return nodes
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
