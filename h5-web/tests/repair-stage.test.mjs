import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import {compile} from '@vue/compiler-dom'
import ts from 'typescript'
import * as Vue from 'vue'
import {actionFor,caseStatusLabel} from '../src/features/workorders/work-orders.ts'

function component(file,deps,props){
 const {descriptor}=parse(readFileSync(new URL('../src/features/'+file,import.meta.url),'utf8'))
 let code=ts.transpileModule(compileScript(descriptor,{id:'repair-stage-test'}).content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>'const {'+names.replace(/\s+as\s+/g,':')+'}=__vue;')
  .replace(/import .*from ['"].*['"];?/g,'').replace('export default','return')
 const names=Object.keys(deps)
 const instance=new Function('__vue','__deps','const {'+names.join(',')+'}=__deps;'+code)({...Vue,onMounted(){},onUnmounted(){}},deps)
 const state=instance.setup(props,{expose(){},emit(){}})
 function nodes(){
  const {code:template}=compile(descriptor.template.content,{mode:'function',prefixIdentifiers:true,expressionPlugins:['typescript']})
  const render=new Function('Vue',ts.transpileModule(template,{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText)(Vue),out=[]
  function visit(node){if(!node||typeof node!=='object')return;out.push(node);if(Array.isArray(node.children))node.children.forEach(visit)}
  visit(render(Vue.proxyRefs({...state,...props}),[]));return out
 }
 return {state,nodes}
}
function repair(status='REPAIRING',authorized=true,role='REPAIR_SHOP',review=null){
 const calls=[],props=Vue.reactive({caseId:'case-stage',refreshToken:0,receiptMode:false})
 let current={caseId:'case-stage',status,version:2,assignmentVersion:3,completion:null,receipt:null,review},fail=false
 const ui=component('repair/RepairPanel.vue',{
  Rate:'Rate',CompletionEvidence:'CompletionEvidence',PhotoPicker:'PhotoPicker',RecordList:'RecordList',label:value=>value,localTime:value=>value,
  currentUser:Vue.ref({roles:[role]}),httpClient:{},
  repairApi:()=>({get:async()=>current,history:async()=>({records:[],total:0}),post:async(op,body,key)=>{
   calls.push({op,body,key});if(fail)throw Error('网络中断');current={...current,status:'WAITING_OWNER_CONFIRMATION'};return current
  }}),
  quotationApi:()=>({get:async()=>({authorized,gate:{allowed:authorized},version:6,assignmentVersion:3}),post:async(op,body,key)=>{
   calls.push({op,body,key});current={...current,status:'REPAIRING'};return current
  }})
 },props)
 return {...ui,calls,fail(value){fail=value}}
}
test('repair task has only the completion action, then requires completion photos and keeps failed-submit idempotency',async()=>{
 const ui=repair();await ui.state.load()
 assert.equal(ui.nodes().filter(n=>n.type==='button').map(n=>n.children).join('|'),'维修完成')
 await ui.state.openCompletion()
 assert.equal(ui.nodes().some(n=>n.type==='PhotoPicker'),true)
 assert.equal(ui.nodes().some(n=>n.type==='textarea'),false)
 await assert.rejects(ui.state.submit('completion'),/完工照/)
 ui.state.selectedPhotos.value=['photo-current'];ui.fail(true)
 await assert.rejects(ui.state.submit('completion'),/网络中断/)
 ui.fail(false);await ui.state.submit('completion')
 assert.equal(ui.calls.length,2);assert.equal(ui.calls[0].key,ui.calls[1].key)
 assert.deepEqual(ui.calls[1].body,{expectedVersion:2,assignmentVersion:3,photoIds:['photo-current']})
})
test('old authorized cases start only after clicking completion; unconfirmed cases cannot start or finish',async()=>{
 const old=repair('WAITING_REPAIR_AUTHORIZATION');await old.state.load()
 assert.equal(old.calls.length,0);await old.state.openCompletion()
 assert.equal(old.calls[0].op,'start-repair')
 assert.deepEqual(old.calls[0].body,{expectedVersion:6,assignmentVersion:3})
 assert.equal(old.state.completionOpen.value,true)
 const waiting=repair('WAITING_REPAIR_AUTHORIZATION',false);await waiting.state.load();await waiting.state.openCompletion()
 assert.equal(waiting.calls.length,0);assert.equal(waiting.state.completionOpen.value,false)
})
test('old quote links follow the repair task while an explicit quote-records link remains historical',()=>{
 const props=Vue.reactive({}),actor=Vue.ref({roles:['REPAIR_SHOP']}),route=Vue.reactive({params:{id:'case-stage'},path:'/work-orders/case-stage',query:{section:'quotation'}})
 const ui=component('workorders/WorkOrderDetailView.vue',{
  FundsPanel:{},ComplaintPanel:{},RepairPanel:{},QuotationPanel:{},MaterialsPanel:{},
  useRoute:()=>route,useRouter:()=>({replace(){},push(){}}),Button:{},Cell:{},CellGroup:{},Field:{},NavBar:{},Skeleton:{},Tag:{},showSuccessToast(){},showToast(){},
  currentUser:actor,httpClient:{},actionFor,caseStatusLabel,workOrderApi:()=>({})
 },props)
 for(const status of ['WAITING_REPAIR_AUTHORIZATION','REPAIRING']){
  ui.state.order.value={status,workflowNode:'REPAIR'}
  assert.equal(ui.state.activeSection.value,'repair')
 }
 route.query.section='quote-records';assert.equal(ui.state.activeSection.value,'quote-records')
 ui.state.order.value={status:'ARRIVED',workflowNode:'SHOP_QUOTE'};route.query.section='quotation'
 assert.equal(ui.state.activeSection.value,'quotation')
 actor.value={roles:['OWNER']}
 ui.state.order.value={status:'WAITING_INSURER_PAYMENT',workflowNode:'FUNDS'};route.query.section='settlement'
 assert.equal(ui.state.activeSection.value,'receipt')
 assert.equal(ui.state.workflowNodes.value.some(node=>node.key==='FUNDS'),false)
 assert.equal(ui.state.displayStatus.value,'已收车')
 assert.equal(ui.state.workflowNodes.value[ui.state.workflowIndex.value].key,'COMPLETE')
})
test('owners select five stars or text only and failed review submissions keep their request key',async()=>{
 const ui=repair('WAITING_INSURER_PAYMENT',true,'OWNER');await ui.state.load()
 const stars=ui.nodes().find(node=>node.type==='Rate')
 assert.equal(stars.props.count,5);assert.equal(stars.props['allow-half'],false)
 assert.equal(ui.nodes().some(node=>node.children==='WAITING_INSURER_PAYMENT'),false)
 ui.state.score.value=5;ui.fail(true)
 await assert.rejects(ui.state.mutate('review'),/网络中断/)
 ui.fail(false);await ui.state.mutate('review')
 assert.equal(ui.calls[0].key,ui.calls[1].key)
 assert.equal(ui.calls[1].body.score,5)
 const textOnly=repair('COMPLETED',true,'OWNER');await textOnly.state.load()
 textOnly.state.reviewText.value='服务很好';await textOnly.state.mutate('review')
 assert.equal(textOnly.calls[0].body.score,null)
 assert.equal(textOnly.calls[0].body.text,'服务很好')
})
test('an existing owner review is read only and no longer offers the review editor',async()=>{
 const entry={text:'和',score:4},ui=repair('WAITING_INSURER_PAYMENT',true,'OWNER',{original:entry,current:entry})
 await ui.state.load()
 assert.equal(ui.nodes().find(node=>node.type==='Rate').props['model-value'],4)
 assert.equal('readonly' in ui.nodes().find(node=>node.type==='Rate').props,true)
 assert.equal(ui.nodes().some(node=>node.type==='textarea'),false)
 assert.equal(ui.nodes().some(node=>node.children==='评价已提交'),true)
})
test('repair shops load only their settlement summary, with no ledger or export controls',async()=>{
 const calls=[],ui=component('funds/FundsPanel.vue',{
  FundsImportPanel:'FundsImportPanel',currentUser:Vue.ref({roles:['REPAIR_SHOP']}),
  label:value=>value,localTime:value=>value,httpClient:{get:async(path)=>{
   calls.push(path);return {data:{data:{payable:{target:'80.00',net:'80.00',status:'SETTLED'}}}}
  }}
 },{caseId:'case-stage'})
 await ui.state.load()
 assert.deepEqual(calls,['/funds/cases/case-stage'])
 const buttons=ui.nodes().filter(node=>node.type==='button')
 assert.equal(buttons.some(node=>String(node.children).includes('导出')),false)
 assert.equal(ui.nodes().some(node=>node.type==='FundsImportPanel'),false)
})
test('owner status labels conceal internal financial stages',()=>{
 for(const status of ['WAITING_INSURER_PAYMENT','WAITING_SHOP_SETTLEMENT','COMPLETED'])
  assert.equal(caseStatusLabel(status,'OWNER'),'已收车')
 assert.equal(caseStatusLabel('WAITING_INSURER_PAYMENT','REPAIR_SHOP'),'待结算')
})
