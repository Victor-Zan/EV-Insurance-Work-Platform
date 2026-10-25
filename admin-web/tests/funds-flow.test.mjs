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
 code='const {httpClient,currentUser}=__deps;const {chinaBusinessTime,fundsDirection,fundsConfirmationIssue,fundsAmount}=__flow;const FundsImportPanel={},FundsAssessment={},ElDialog={};const label=x=>x,localTime=x=>x;'+code
 let value={version:2,stagedFlow:true,flowStage:'WAITING_INSURER_PAYMENT',receiveConfirmed:false,payConfirmed:false,canConfirmReceive:false,canConfirmPay:false,canRecordReceive:true,canRecordPay:false,canSetPayTarget:true,receivable:{target:'10.00',net:'5.00',status:'PARTIAL'},payable:{target:'8.00',net:'0.00',status:'UNPAID'}}
 const calls=[],events=[],ledger=[],commands=new Map(),failures=new Map();let afterEntry
 const clone=value=>structuredClone(value)
 function refreshFlags(){for(const key of ['receivable','payable']){const b=value[key];b.status=b.target==null?'UNSET':BigInt(b.target.replace('.',''))===BigInt(b.net.replace('.',''))?'SETTLED':b.net==='0.00'?'UNPAID':'PARTIAL'}value.canConfirmReceive=value.flowStage==='WAITING_INSURER_PAYMENT'&&value.receivable.status==='SETTLED';value.canConfirmPay=value.flowStage==='WAITING_SHOP_SETTLEMENT'&&value.payable.status==='SETTLED'}
 const deps={currentUser:Vue.ref({roles}),httpClient:{async get(path){return {data:{data:path.endsWith('/entries')?{records:clone(ledger),total:ledger.length}:path.endsWith('/targets')?{records:[],total:0}:clone(value)}}},async post(path,body,options){
  const key=options.headers['Idempotency-Key'],kind=path.split('/').at(-1),failure=failures.get(kind)
  calls.push({path,body:clone(body),key})
  if(commands.has(key))return clone(commands.get(key))
  if(failure){failures.delete(kind);if(failure==='before')throw Error('合成网络失败')}
  assert.equal(body.expectedVersion,value.version)
  if(kind==='targets'){const field=body.direction==='PAY'?'payable':'receivable';value[field]={...value[field],target:body.amount}}
  if(kind==='entries'){
   assert.equal(ledger.some(entry=>entry.transactionNo===body.transactionNo),false)
   ledger.unshift({id:'entry-'+ledger.length,...clone(body),reversed:false})
   const field=body.direction==='PAY'?'payable':'receivable',net=BigInt(value[field].net.replace('.',''))+BigInt(body.amount.replace('.',''))
   value[field]={...value[field],net:(net/100n)+'.'+String(net%100n).padStart(2,'0')}
  }
  if(kind==='confirmations')value={...value,flowStage:body.direction==='RECEIVE'?'WAITING_SHOP_SETTLEMENT':'COMPLETED',receiveConfirmed:true,payConfirmed:body.direction==='PAY'}
  value={...value,version:value.version+1};refreshFlags()
  const response={data:{data:kind==='entries'?{entryId:ledger[0].id,duplicate:false,funds:clone(value)}:clone(value)}};commands.set(key,clone(response))
  if(kind==='entries'&&afterEntry)value=afterEntry(value)
  if(failure==='after')throw Error('合成响应丢失')
  return response
 }}}

 const component=new Function('__vue','__deps','__flow',code)({...Vue,onMounted(){},watch(){}},deps,{...flow,chinaBusinessTime:()=>flow.chinaBusinessTime(new Date('2026-10-16T16:30:00Z'))})
 const context=component.setup({caseId:'synthetic-funds-case'},{emit(...args){events.push(args)},expose(){}})
 return {context,calls,events,ledger,afterEntry(fn){afterEntry=fn},failNext(kind,when='before'){failures.set(kind,when)},set(valueNext){value={...value,...valueNext}}}
}
function elements(f){
 const {code}=compile(descriptor.template.content,{mode:'function',prefixIdentifiers:true,expressionPlugins:['typescript']}),render=new Function('Vue',ts.transpileModule(code,{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText)(Vue),nodes=[]
 function visit(v){if(!v||typeof v!=='object')return;if(typeof v.type==='string')nodes.push(v);if(Array.isArray(v.children))v.children.forEach(visit)}visit(render(Vue.proxyRefs(f.context),[]));return nodes
}
const buttons=f=>elements(f).filter(node=>node.type==='button')
test('one explicit receipt action records the remaining amount then confirms the step and clears the old form',async()=>{
 const f=fixture();await f.context.load();assert.equal(f.context.direction.value,'RECEIVE');assert.equal(f.context.targetAmount.value,'10.00')
 await f.context.confirmStep();assert.match(f.context.confirmationError.value,/流水尚未结清/);assert.equal(f.calls.length,0)
 f.context.amount.value='5.00';f.context.number.value='RECEIVE-REFERENCE';f.context.time.value='2026-10-16T12:00';f.context.note.value='保险到账备注'
 await f.context.run(f.context.confirmEntry)
 assert.deepEqual(f.calls.map(c=>c.path.split('/').at(-1)),['entries','confirmations'])
 assert.deepEqual(f.calls[0].body,{expectedVersion:2,direction:'RECEIVE',transactionNo:'RECEIVE-REFERENCE',amount:'5.00',occurredAt:'2026-10-16T04:00:00.000Z',note:'保险到账备注'})
 assert.deepEqual(f.calls[1].body,{expectedVersion:3,direction:'RECEIVE'});assert.ok(f.calls[0].key);assert.notEqual(f.calls[0].key,f.calls[1].key)
 assert.ok(f.events.some(e=>e[0]==='changed'));assert.equal(f.context.direction.value,'PAY');assert.equal(f.context.data.value.flowStage,'WAITING_SHOP_SETTLEMENT')
 for(const field of ['amount','number','note'])assert.equal(f.context[field].value,'')
 assert.equal(f.context.time.value,'2026-10-17T00:30');assert.equal(f.context.confirmationError.value,'')
})

test('admin and shop render no funds confirmation write button',async()=>{
 for(const roles of [['ADMIN'],['REPAIR_SHOP']]){const f=fixture(roles);await f.context.load();assert.equal(buttons(f).some(n=>n.props?.class==='primary'),false);assert.equal(f.calls.length,0)}
})
test('two independent amount rows have right-hand confirmations, optional collapsed notes and China time',async()=>{
 const f=fixture();await f.context.load();const nodes=elements(f)
 assert.equal(nodes.some(n=>n.type==='select'),false);assert.equal(f.context.reason,undefined);assert.equal(f.context.time.value,'2026-10-17T00:30')
 assert.equal(nodes.filter(n=>n.type==='input'&&n.props.inputmode==='decimal').length,2)
 const rows=nodes.filter(n=>n.props?.class==='amount-row');assert.equal(rows.length,2)
 assert.equal(rows[0].children.at(-1).type,'button');assert.equal(rows[1].children.at(-1).type,'button')
 assert.equal(buttons(f).some(n=>/保存应[收付]金额|保存[到结][账算]流水|确认到账完成|确认结算并结案/.test(n.children)),false)
 assert.equal(nodes.find(n=>n.type==='details'&&n.props?.class==='optional-note').props.open,false)
 f.context.targetAmount.value='12.50';f.context.amount.value='2.50';await f.context.target()
 assert.equal(f.calls[0].body.amount,'12.50');assert.equal(f.calls[0].body.direction,'RECEIVE');assert.equal(f.calls[0].body.reason,'客服在保险到账步骤确认人工应收金额');assert.equal(f.context.amount.value,'2.50')
 f.context.number.value='SYNTHETIC-REFERENCE';await f.context.entry();assert.equal(f.calls[1].body.note,'');assert.equal(f.calls[1].body.occurredAt,'2026-10-16T16:30:00.000Z')
 f.set({flowStage:'WAITING_SHOP_SETTLEMENT',receiveConfirmed:true});await f.context.load();assert.equal(f.context.direction.value,'PAY')
 assert.ok(buttons(f).some(n=>n.children==='确认应付金额'));assert.ok(buttons(f).some(n=>n.children==='确认结算'))
 f.context.targetAmount.value='8.00';await f.context.target();assert.equal(f.calls[2].body.direction,'PAY')
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
test('missing payable or reference gives local feedback; payment confirmation saves its own ledger before closing',async()=>{
 const f=fixture();f.set({flowStage:'WAITING_SHOP_SETTLEMENT',receiveConfirmed:true,payable:{target:null,net:'0.00',status:'UNSET'}});await f.context.load();let focused=0;f.context.targetInput.value={focus(){focused++}}
 await f.context.confirmEntry();assert.match(f.context.confirmationError.value,/确认应付金额/);assert.equal(focused,1);assert.equal(f.calls.length,0)
 f.set({payable:{target:'8.00',net:'0.00',status:'UNPAID'}});await f.context.load();f.context.amount.value='8.00';await f.context.confirmEntry();assert.match(f.context.confirmationError.value,/凭证编号/);assert.equal(f.calls.length,0)
 f.context.number.value='PAY-TEST';await f.context.run(f.context.confirmEntry)
 assert.deepEqual(f.calls.map(c=>[c.path.split('/').at(-1),c.body.direction]),[['entries','PAY'],['confirmations','PAY']]);assert.equal(f.context.data.value.flowStage,'COMPLETED')
 assert.equal(f.ledger[0].amount,'8.00')
})

test('a fresh snapshot never silently confirms another version or the next money direction',async()=>{
 const f=fixture();await f.context.load();f.set({version:3,receivable:{target:'10.00',net:'10.00',status:'SETTLED'},canConfirmReceive:true});await f.context.confirmEntry();assert.equal(f.calls.length,0);assert.match(f.context.confirmationError.value,/已有更新/)
 f.set({version:4,flowStage:'WAITING_SHOP_SETTLEMENT',receiveConfirmed:true,payable:{target:'8.00',net:'8.00',status:'SETTLED'},canConfirmPay:true});await f.context.confirmEntry();assert.equal(f.calls.length,0);assert.equal(f.context.direction.value,'PAY');assert.match(f.context.confirmationError.value,/已有更新/)
 assert.equal(flow.fundsConfirmationIssue({...f.context.data.value,canConfirmPay:false}).field,'refresh')
 assert.equal(flow.fundsConfirmationIssue({...f.context.data.value,payable:{target:'0.00',net:'0.00',status:'SETTLED'},canConfirmPay:true}),undefined)
})

test('partial actual amounts stay in the same step and clear only the saved entry fields',async()=>{
 const f=fixture();await f.context.load();f.context.amount.value='2.00';f.context.number.value='PARTIAL-ONE';f.context.note.value='部分到账';f.context.noteOpen.value=true
 await f.context.run(f.context.confirmEntry)
 assert.equal(f.calls.length,1);assert.ok(f.calls[0].path.endsWith('/entries'));assert.equal(f.context.direction.value,'RECEIVE');assert.equal(f.context.balance.value.net,'7.00')
 assert.equal(f.context.amount.value,'');assert.equal(f.context.number.value,'');assert.equal(f.context.targetAmount.value,'10.00');assert.equal(f.context.noteOpen.value,false);assert.match(f.context.message.value,/流水已保存/)
})

test('already settled and zero-target flows only confirm, never manufacture another entry',async()=>{
 for(const target of ['10.00','0.00']){
  const f=fixture();f.set({receivable:{target,net:target,status:'SETTLED'},canConfirmReceive:true});await f.context.load()
  assert.equal(elements(f).filter(n=>n.type==='input'&&n.props.inputmode==='decimal')[1].props.disabled,true)
  f.context.amount.value='10.00';f.context.number.value='OLD-ALREADY-SAVED';await f.context.run(f.context.confirmEntry)
  assert.equal(f.calls.length,1);assert.ok(f.calls[0].path.endsWith('/confirmations'));assert.equal(f.ledger.length,0);assert.equal(f.context.direction.value,'PAY')
 }
})

test('an intervening revision after saving the entry cannot be silently confirmed',async()=>{
 const f=fixture();await f.context.load();f.context.amount.value='5.00';f.context.number.value='CONCURRENT-REVISION'
 f.afterEntry(value=>({...value,version:value.version+1}))
 await f.context.run(f.context.confirmEntry)
 assert.equal(f.ledger.length,1);assert.equal(f.calls.length,1);assert.match(f.context.confirmationError.value,/另有更新/);assert.equal(f.context.direction.value,'RECEIVE')
})

test('a failed step confirmation retries the same command without recording the saved receipt twice',async()=>{
 const f=fixture();await f.context.load();f.context.amount.value='5.00';f.context.number.value='SAFE-RETRY';f.failNext('confirmations')
 await f.context.run(f.context.confirmEntry);assert.equal(f.ledger.length,1);assert.equal(f.context.direction.value,'RECEIVE');assert.equal(f.context.messageError.value,true)
 assert.ok(elements(f).some(n=>n.props?.role==='alert'))
 await f.context.run(f.context.confirmEntry)
 const entries=f.calls.filter(c=>c.path.endsWith('/entries')),confirmations=f.calls.filter(c=>c.path.endsWith('/confirmations'))
 assert.equal(entries.length,1);assert.equal(confirmations.length,2);assert.equal(confirmations[0].key,confirmations[1].key);assert.equal(f.ledger.length,1);assert.equal(f.context.direction.value,'PAY')
})

test('an entry failure retains inputs and retry key, while a lost success reads back before confirming',async()=>{
 const f=fixture();await f.context.load();f.context.amount.value='5.00';f.context.number.value='ENTRY-RETRY';f.failNext('entries')
 await f.context.run(f.context.confirmEntry);assert.equal(f.context.amount.value,'5.00');assert.equal(f.context.number.value,'ENTRY-RETRY')
 await f.context.run(f.context.confirmEntry);assert.equal(f.calls[0].key,f.calls[1].key);assert.equal(f.ledger.length,1)
 const lost=fixture();await lost.context.load();lost.context.amount.value='5.00';lost.context.number.value='LOST-SUCCESS';lost.failNext('entries','after')
 await lost.context.run(lost.context.confirmEntry);assert.equal(lost.ledger.length,1)
 await lost.context.run(lost.context.confirmEntry);assert.match(lost.context.confirmationError.value,/已有更新/)
 await lost.context.run(lost.context.confirmEntry);assert.equal(lost.calls.filter(c=>c.path.endsWith('/entries')).length,1);assert.equal(lost.context.direction.value,'PAY')
})

test('unconfirmed target edits, invalid precision, zero/negative actuals and excess amounts cannot write a ledger',async()=>{
 const f=fixture();await f.context.load();f.context.targetAmount.value='12.00';f.context.targetDirty.value=true;f.context.amount.value='5.00';f.context.number.value='BLOCKED'
 await f.context.run(f.context.confirmEntry);assert.match(f.context.confirmationError.value,/目标金额已修改/);assert.equal(f.calls.length,0)
 f.context.targetAmount.value='10.00'
 for(const amount of ['0','-1','1.001','1e2','6.00']){f.context.amount.value=amount;await f.context.run(f.context.confirmEntry);assert.equal(f.calls.length,0);assert.ok(f.context.confirmationError.value||f.context.message.value)}
 assert.equal(flow.fundsAmount(' 00012.3 '),'12.30');assert.equal(flow.fundsAmount('999999999999999999999999999999999999.99'),'999999999999999999999999999999999999.99')
 assert.throws(()=>flow.fundsAmount('1'.repeat(37)),/有效金额/)
})

test('legacy non-staged receipt recording does not issue a workflow confirmation; an empty unpaid target can still be cleared',async()=>{
 const f=fixture();f.set({stagedFlow:false});await f.context.load();f.context.amount.value='5.00';f.context.number.value='LEGACY-ENTRY';await f.context.run(f.context.confirmEntry)
 assert.equal(f.calls.length,1);assert.ok(f.calls[0].path.endsWith('/entries'));assert.equal(f.ledger.length,1)
 assert.equal(f.context.confirmationError.value,'');assert.match(f.context.message.value,/累计 10.00 元/)
 f.set({stagedFlow:true,flowStage:'WAITING_SHOP_SETTLEMENT',receiveConfirmed:true,payable:{target:'8.00',net:'0.00',status:'UNPAID'}});await f.context.load();f.context.targetAmount.value='';await f.context.target();assert.equal(f.calls.at(-1).body.amount,null)
})
