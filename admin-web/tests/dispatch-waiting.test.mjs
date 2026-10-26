import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import {compile} from '@vue/compiler-dom'
import ts from 'typescript'
import * as Vue from 'vue'

function render(order){
 const source=readFileSync(new URL('../src/features/workorders/DispatchWaitingPanel.vue',import.meta.url),'utf8'),{descriptor}=parse(source)
 let code=ts.transpileModule(compileScript(descriptor,{id:'dispatch-waiting-test'}).content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>`const {${names.replace(/\s+as\s+/g,':')}}=Vue;`).replace(/import\s*\{\s*localTime\s*\}\s*from[^\n]+/g,'const localTime=value=>value;').replace('export default','return')
 const component=new Function('Vue',code)(Vue),context=component.setup({order},{expose(){}})
 const compiled=compile(descriptor.template.content,{mode:'function',prefixIdentifiers:true})
 const vnode=new Function('Vue',compiled.code)(Vue)(Vue.proxyRefs({order,...context}),[])
 const nodes=[];let text='';function visit(v){if(typeof v==='string'){text+=v;return}if(!v||typeof v!=='object')return;if(Array.isArray(v)){v.forEach(visit);return}if(typeof v.type==='string')nodes.push(v);if(Array.isArray(v.children))visit(v.children);else if(typeof v.children==='string')text+=v.children}visit(vnode)
 return {nodes,text}
}
const order={businessNo:'SYNTHETIC-CASE',claimNo:'SYNTHETIC-CLAIM',ownerName:'合成车主',ownerPhone:'13800000000',accidentAddress:'合成事故路段',parkingAddress:'合成接车路段',status:'PENDING_ACCEPTANCE',currentAssignment:{shopId:1,shopName:'开发接单端',destinationShopId:13,destinationName:'模拟市民中心目的地',destinationAddress:'合成目的地地址',locationPrecision:'ROAD'}}
test('waiting screen shows selected destination, developer receiver, road precision and case details without dispatch controls',()=>{
 const result=render(order)
 for(const value of ['已派单','等待网点接单','模拟市民中心目的地','开发接单端','SYNTHETIC-CASE','合成事故路段','路段级粗略位置'])assert.ok(result.text.includes(value),value)
 assert.equal(result.nodes.some(n=>['input','select','button','canvas'].includes(n.type)),false)
})
test('older assignments retain their real receiving shop without inventing a destination',()=>{
 const result=render({...order,currentAssignment:{shopId:1,shopName:'原接单网点',shopAddress:'原网点地址'}})
 assert.ok(result.text.includes('原接单网点'));assert.ok(result.text.includes('原网点地址'));assert.equal(result.text.includes('模拟市民中心目的地'),false)
})
test('parent switches by persisted pending-acceptance status and applies the successful response before refreshing',()=>{
 const source=readFileSync(new URL('../src/features/workorders/WorkOrderDetailView.vue',import.meta.url),'utf8')
 assert.match(source,/waitingForShop=computed\(\(\)=>order\.value\?\.status==='PENDING_ACCEPTANCE'\)/)
 assert.match(source,/<WorkflowTimeline :workflow="workflow"\/><\/header><template v-if="waitingForShop">/ )
 assert.match(source,/<DispatchWaitingPanel :order="order"\/><\/template><template v-else>/)
 assert.match(source,/if\(updated\)order\.value=updated;await load\(\)/)
 assert.match(source,/@changed="dispatchChanged"/)
})
