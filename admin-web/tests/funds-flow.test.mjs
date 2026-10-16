import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import {compile} from '@vue/compiler-dom'
import ts from 'typescript'
import * as Vue from 'vue'
import * as flow from '../src/features/funds/funds-flow.ts'
const {descriptor}=parse(readFileSync(new URL('../src/features/funds/FundsPanel.vue',import.meta.url),'utf8'))
function fixture(roles=['CUSTOMER_SERVICE']){
 let code=ts.transpileModule(compileScript(descriptor,{id:'funds-flow-test'}).content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>'const {'+names.replace(/\s+as\s+/g,':')+'}=__vue;').replace(/import .*from ['"].*['"];?/g,'').replace('export default','return')
 code='const {httpClient,currentUser}=__deps;const {chinaBusinessTime,fundsDirection,fundsConfirmationIssue}=__flow;const FundsImportPanel={},FundsAssessment={},ElDialog={};const label=x=>x,localTime=x=>x;'+code
 let value={version:2,stagedFlow:true,flowStage:'WAITING_INSURER_PAYMENT',receiveConfirmed:false,payConfirmed:false,canConfirmReceive:false,canConfirmPay:false,receivable:{target:'10.00',net:'5.00',status:'PARTIAL'},payable:{target:'8.00',net:'0.00',status:'UNPAID'}}
 const calls=[],events=[]
 const deps={currentUser:Vue.ref({roles}),httpClient:{async get(path){return {data:{data:path.endsWith('/entries')||path.endsWith('/targets')?{records:[],total:0}:value}}},async post(path,body,options){calls.push({path,body,key:options.headers['Idempotency-Key']});if(path.endsWith('/confirmations'))value={...value,version:3,flowStage:'WAITING_SHOP_SETTLEMENT',receiveConfirmed:true,canConfirmReceive:false};return {}}}}
 const component=new Function('__vue','__deps','__flow',code)({...Vue,onMounted(){},watch(){}},deps,{...flow,chinaBusinessTime:()=>flow.chinaBusinessTime(new Date('2026-10-16T16:30:00Z'))})
 const context=component.setup({caseId:'synthetic-funds-case'},{emit(...args){events.push(args)},expose(){}})
 return {context,calls,events,set(valueNext){value={...value,...valueNext}}}
}
function elements(f){
 const {code}=compile(descriptor.template.content,{mode:'function',prefixIdentifiers:true,expressionPlugins:['typescript']}),render=new Function('Vue',ts.transpileModule(code,{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText)(Vue),nodes=[]
 function visit(v){if(!v||typeof v!=='object')return;if(typeof v.type==='string')nodes.push(v);if(Array.isArray(v.children))v.children.forEach(visit)}visit(render(Vue.proxyRefs(f.context),[]));return nodes
}
const buttons=f=>elements(f).filter(node=>node.type==='button')
test('funds stays at receive until explicit confirmation; partial entries cannot confirm; confirmation refreshes parent and selects pay',async()=>{
 const f=fixture();await f.context.load();assert.equal(f.context.direction.value,'RECEIVE')
 let confirm=buttons(f).find(n=>n.props?.class==='primary');assert.equal(confirm.props.disabled,false)
 await f.context.confirmStep();assert.match(f.context.confirmationError.value,/流水尚未结清/);assert.equal(f.calls.length,0)
 f.set({canConfirmReceive:true,receivable:{target:'10.00',net:'10.00',status:'SETTLED'}});await f.context.load()
 confirm=buttons(f).find(n=>n.props?.class==='primary');assert.equal(confirm.props.disabled,false);assert.equal(f.calls.length,0)
 f.context.amount.value='10.00';f.context.number.value='RECEIVE-REFERENCE';f.context.time.value='2026-10-16T12:00';f.context.note.value='保险到账备注'
 await f.context.confirmStep();assert.deepEqual(f.calls[0].body,{expectedVersion:2,direction:'RECEIVE'});assert.ok(f.calls[0].key);assert.deepEqual(f.events,[['changed']]);assert.equal(f.context.direction.value,'PAY');assert.equal(f.context.data.value.flowStage,'WAITING_SHOP_SETTLEMENT')
 for(const field of ['amount','number','note'])assert.equal(f.context[field].value,'')
 assert.equal(f.context.time.value,'2026-10-17T00:30')
 assert.equal(f.context.confirmationError.value,'')
})
test('admin and shop render no funds confirmation write button',async()=>{
 for(const roles of [['ADMIN'],['REPAIR_SHOP']]){const f=fixture(roles);await f.context.load();assert.equal(buttons(f).some(n=>n.props?.class==='primary'),false);assert.equal(f.calls.length,0)}
})
test('direction follows the server stage, with no selector or basis field; notes are optional and time defaults to China today',async()=>{
 const f=fixture();await f.context.load();assert.equal(elements(f).some(node=>node.type==='select'),false);assert.equal(f.context.reason,undefined);assert.equal(f.context.time.value,'2026-10-17T00:30')
 f.context.amount.value='10.00';await f.context.target();assert.equal(f.calls[0].body.direction,'RECEIVE');assert.equal(f.calls[0].body.reason,'客服在保险到账步骤保存人工应收金额')
 f.context.number.value='SYNTHETIC-REFERENCE';await f.context.entry();assert.equal(f.calls[1].body.note,'');assert.equal(f.calls[1].body.occurredAt,'2026-10-16T16:30:00.000Z')
 f.set({flowStage:'WAITING_SHOP_SETTLEMENT',receiveConfirmed:true});await f.context.load();assert.equal(f.context.direction.value,'PAY');await f.context.target();assert.equal(f.calls[2].body.direction,'PAY')
 f.set({flowStage:'COMPLETED',payConfirmed:true});await f.context.load();assert.equal(f.context.direction.value,undefined);assert.equal(buttons(f).some(n=>n.props?.class==='primary'),false)
})
test('legacy ledger remains usable and reversal keeps an explicit, separate correction reason',async()=>{
 assert.equal(flow.fundsDirection({stagedFlow:false,flowStage:'COMPLETED',receivable:{status:'SETTLED'}}),'PAY');assert.equal(flow.fundsDirection({stagedFlow:true,flowStage:'CANCELLED'}),undefined)
 const f=fixture();await f.context.load();f.context.openReversal({id:'entry-one',amount:'5.00',occurredAt:'2026-10-16T01:00:00Z'});await assert.rejects(f.context.reverse(),/冲销原因/);assert.equal(f.calls.length,0)
 f.context.reversalReason.value='合成冲销核对';await f.context.reverse();assert.match(f.calls[0].path,/entry-one\/reverse$/);assert.equal(f.calls[0].body.reason,'合成冲销核对');assert.equal(f.context.reversal.value,undefined)
})
test('China date crosses midnight independently of host timezone',()=>{
 assert.equal(flow.chinaBusinessTime(new Date('2026-10-16T15:59:00Z')),'2026-10-16T23:59');assert.equal(flow.chinaBusinessTime(new Date('2026-10-16T16:00:00Z')),'2026-10-17T00:00')
})
test('missing payable and payment ledger give local feedback without closing; valid settled payment submits the exact displayed version',async()=>{
 const f=fixture();f.set({flowStage:'WAITING_SHOP_SETTLEMENT',receiveConfirmed:true,payable:{target:null,net:'0.00',status:'UNSET'}});await f.context.load();let focused=0;f.context.amountInput.value={focus(){focused++}}
 await f.context.confirmStep();assert.match(f.context.confirmationError.value,/保存应付金额/);assert.equal(focused,1);assert.equal(f.calls.length,0)
 f.set({payable:{target:'8.00',net:'0.00',status:'UNPAID'}});await f.context.load();await f.context.confirmStep();assert.match(f.context.confirmationError.value,/保存结算流水/);assert.equal(f.calls.length,0)
 f.set({payable:{target:'8.00',net:'8.00',status:'SETTLED'},canConfirmPay:true});await f.context.load();await f.context.confirmStep();assert.deepEqual(f.calls[0].body,{expectedVersion:2,direction:'PAY'})
})
test('a fresh snapshot never silently confirms another version or the next money direction',async()=>{
 const f=fixture();await f.context.load();f.set({version:3,receivable:{target:'10.00',net:'10.00',status:'SETTLED'},canConfirmReceive:true});await f.context.confirmStep();assert.equal(f.calls.length,0);assert.match(f.context.confirmationError.value,/已有更新/)
 f.set({version:4,flowStage:'WAITING_SHOP_SETTLEMENT',receiveConfirmed:true,payable:{target:'8.00',net:'8.00',status:'SETTLED'},canConfirmPay:true});await f.context.confirmStep();assert.equal(f.calls.length,0);assert.equal(f.context.direction.value,'PAY');assert.match(f.context.confirmationError.value,/已有更新/)
 assert.equal(flow.fundsConfirmationIssue({...f.context.data.value,canConfirmPay:false}).field,'refresh')
 assert.equal(flow.fundsConfirmationIssue({...f.context.data.value,payable:{target:'0.00',net:'0.00',status:'SETTLED'},canConfirmPay:true}),undefined)
})
