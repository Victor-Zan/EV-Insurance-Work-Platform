import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'

function setup(roles,status='PENDING_DISPATCH'){
 const {descriptor}=parse(readFileSync(new URL('../src/features/workorders/DispatchLocation.vue',import.meta.url),'utf8'))
 let code=ts.transpileModule(compileScript(descriptor,{id:'dispatch-permissions'}).content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>`const {${names.replace(/\s+as\s+/g,':')}}=Vue;`)
 code=code.replace(/import .*from ['"].*['"];?/g,'').replace('export default','return')
 const calls=[],hooks=[],events=[]
 const currentUser=Vue.ref({roles}),httpClient={async get(path){calls.push(path);return {data:{data:{provider:'AMAP_WEB_V1',mock:false}}}}}
 code='const MockDispatchLocation={},ParkingLocationPanel={},AccidentDistrictReview={},ManualDispatchChoice={},AutomaticDispatchPanel={};'+code
 const component=new Function('Vue','currentUser','httpClient','workOrderApi',code)({...Vue,onMounted(fn){hooks.push(fn)}},currentUser,httpClient,()=>({}))
 const props=Vue.reactive({caseId:'synthetic-case',order:{status,version:1}})
 const state=component.setup(props,{expose(){},emit(...event){events.push(event)}})
 return {state,calls,hooks,props,events}
}
for(const role of ['ADMIN','CUSTOMER_SERVICE'])test(role+' loads real dispatch configuration and reaches automatic and manual map paths',async()=>{
 const f=setup([role]);await f.hooks[0]()
 assert.deepEqual(f.calls,['/maps/locations/configuration'])
 assert.equal(f.state.config.value.provider,'AMAP_WEB_V1')
 assert.equal(f.state.automatic.value,true)
 f.state.manualFlow.value=true;assert.equal(f.state.automatic.value,false)
 f.state.manualFlow.value=false;f.props.order.status='PENDING_ARRIVAL';assert.equal(f.state.automatic.value,false)
 assert.equal(f.state.config.value.mock,false)
})
for(const role of ['OWNER','REPAIR_SHOP'])test(role+' cannot load staff dispatch configuration',async()=>{
 const f=setup([role]);await f.hooks[0]()
 assert.deepEqual(f.calls,[]);assert.equal(f.state.automatic.value,false)
})
