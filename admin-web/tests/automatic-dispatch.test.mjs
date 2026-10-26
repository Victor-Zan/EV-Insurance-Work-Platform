import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'

const settle=async()=>{await Vue.nextTick();await new Promise(resolve=>setImmediate(resolve));await Vue.nextTick()}
const row=(id,metres)=>({shopId:id,name:`合成测试网点${id}`,address:`合成地址${id}号`,status:'READY',metres,seconds:60})
const ready=()=>({id:'synthetic-plan',status:'READY',message:'合成比较完成',source:'ACCIDENT',inputAddress:'广州市天河区测试入口10号',expiresAt:new Date(Date.now()+120000).toISOString(),location:{address:'合成准确入口',point:{latitude:23,longitude:113}},selectedShopId:1,recommendation:{queryId:'synthetic-query',complete:true,shops:{page:1,size:20,total:2,records:[row(1,1000),row(2,2000)]}}})
function mount(t,post,options={}){
 const calls=[],events=[],storage=options.storage??new Map()
 Object.defineProperty(globalThis,'sessionStorage',{value:{getItem:k=>storage.get(k)??null,setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)},configurable:true})
 const client={get:async(...args)=>{calls.push(['get',...args]);return {data:{data:args[0]==='/maps/simulation-shops'?{page:1,size:20,total:2,records:[{...row(1,null),status:'NOT_CALCULATED'},{...row(2,null),status:'NOT_CALCULATED'}]}:ready()}}},post:async(...args)=>{calls.push(['post',...args]);return {data:{data:await post(...args)}}}}
 const {descriptor}=parse(readFileSync(new URL('../src/features/workorders/AutomaticDispatchPanel.vue',import.meta.url),'utf8'))
 let code=ts.transpileModule(compileScript(descriptor,{id:'automatic-test'}).content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>`const {${names.replace(/\s+as\s+/g,':')}}=Vue;`)
 code=code.replace(/import\s*\{\s*httpClient,\s*currentUser\s*\}\s*from[^\n]+/g,'const httpClient=client,currentUser={value:{id:99}};')
 code=code.replace(/import\s*\{\s*ElMessageBox\s*\}\s*from[^\n]+/g,'const ElMessageBox={prompt:async()=>{throw Error("No unexpected prompt")}};')
 code=code.replace(/import\s*\{\s*ApiError\s*\}\s*from[^\n]+/g,'class ApiError extends Error{}')
 code=code.replace(/import\s*\{\s*mapStatus\s*\}\s*from[^\n]+/g,'const mapStatus=x=>x;')
 code=code.replace(/import\s+AmapLocationMap\s+from[^\n]+/g,'const AmapLocationMap={};').replace('export default','return')
 const component=new Function('Vue','client',code)(Vue,client);component.render=()=>null
 const renderer=Vue.createRenderer({createComment:()=>({}),insert(){},remove(){},parentNode:()=>null,nextSibling:()=>null})
 const props=Vue.reactive({caseId:'synthetic-case',simulationDestinations:options.simulationDestinations??false,order:{version:1,status:'PENDING_DISPATCH',accidentAddress:'广州市天河区测试入口10号',...options.order}})
 const app=renderer.createApp(component,{...props,onChanged:value=>events.push(['changed',value])});app.mount({});t.after(()=>app.unmount())
 return {state:app._instance.setupState,calls,events,storage}
}
test('accepted address starts once; enter followed by blur and repeated same input do not repeat geocoding',async t=>{
 const f=mount(t,async()=>ready());await settle();await f.state.prepare();await f.state.prepare()
 assert.equal(f.calls.length,1);assert.equal(f.calls[0][2].corrected,false);assert.equal(f.state.selected,1);assert.equal(f.state.canDispatch,true)
 assert.equal(f.calls.some(c=>/dispatch-location|accident-district|\/dispatch$/.test(c[1])),false)
})
test('empty first address waits for completed input and preserves original accident source',async t=>{
 const f=mount(t,async()=>ready(),{order:{accidentAddress:''}});await settle();assert.equal(f.calls.length,0)
 f.state.changeAddress('');await f.state.prepare();assert.equal(f.calls.length,0)
 f.state.changeAddress('广州市天河区测试入口20号');await f.state.prepare();await f.state.prepare();assert.equal(f.calls.length,1);assert.equal(f.calls[0][2].corrected,false)
})
test('changed pickup rejects a late old result and keeps failed input for explicit retry',async t=>{
 let finish;const f=mount(t,()=>new Promise(resolve=>{finish=resolve}));await settle()
 f.state.modifyPickup();f.state.changeAddress('深圳市福田区合成入口20号');finish(ready());await settle()
 assert.equal(f.state.plan,undefined);assert.equal(f.state.selected,undefined);assert.equal(f.state.address,'深圳市福田区合成入口20号');assert.equal(f.state.canDispatch,false)
})
test('partial comparison never preselects a nearest shop and failed plans require explicit retry',async t=>{
 let count=0;const f=mount(t,async()=>{count++;return count===1?{...ready(),status:'PARTIAL',selectedShopId:null}:{...ready(),status:'NEEDS_REVIEW',selectedShopId:null,recommendation:null}});await settle()
 assert.equal(f.state.selected,undefined);assert.equal(f.state.canDispatch,false);f.state.choose(row(2,2000));assert.equal(f.state.selected,2)
 await f.state.prepare(true);assert.equal(f.state.selected,undefined);await f.state.prepare();assert.equal(count,2)
})
test('same valid plan restores a chosen shop across remount without reverting to nearest',async t=>{
 const f=mount(t,async()=>ready());await settle();f.state.choose(row(2,2000));assert.equal(f.state.selected,2)
 const next=mount(t,async()=>ready(),{storage:f.storage});await settle();assert.equal(next.state.selected,2);assert.equal(next.state.selectedShop.shopId,2)
})
test('one final action carries current manual choice and a stable idempotency key; expired plan is blocked',async t=>{
 let finish;const f=mount(t,async(path)=>path.endsWith('/dispatch')?new Promise(resolve=>{finish=resolve}):ready());await settle()
 f.state.choose(row(2,2000));const pending=f.state.dispatch();await f.state.dispatch();assert.equal(f.calls.length,2)
 const submission=f.calls[1];assert.equal(submission[1],'/maps/cases/synthetic-case/automatic-dispatch/synthetic-plan/dispatch');assert.equal(submission[2].shopId,2);assert.equal(submission[3].headers['Idempotency-Key'],'automatic-dispatch-synthetic-plan')
 const dispatched={id:'synthetic-case',status:'PENDING_ACCEPTANCE'};finish(dispatched);await pending;assert.deepEqual(f.events,[['changed',dispatched]])
 f.state.plan={...ready(),expiresAt:new Date(Date.now()-1000).toISOString()};f.state.now=Date.now();assert.equal(f.state.canDispatch,false)
})

test('simulation catalog is visible before geocoding and map selection follows the always-visible list',async t=>{
 let finish;const f=mount(t,()=>new Promise(resolve=>{finish=resolve}),{simulationDestinations:true});await settle()
 assert.equal(f.state.visibleShops.records.length,2);assert.equal(f.state.visibleShops.records[0].metres,null)
 finish({...ready(),simulation:true});await settle();f.state.chooseFromMap(2);assert.equal(f.state.selected,2);assert.equal(f.state.selectedShop.shopId,2)
 const source=readFileSync(new URL('../src/features/workorders/AutomaticDispatchPanel.vue',import.meta.url),'utf8')
 assert.match(source,/<AmapLocationMap auto-load/);assert.doesNotMatch(source,/<details class="map-correction"|v-if="changingShop/)
})
test('rough location preserves its accuracy and selected shop while allowing dispatch',async t=>{
 const f=mount(t,async()=>({...ready(),status:'APPROXIMATE',approximate:true,simulation:true}));await settle()
 assert.equal(f.state.selected,1);assert.equal(f.state.plan.location.point.longitude,113);assert.equal(f.state.canDispatch,true)
 f.state.changeAddress('广州市天河区补充后的事故地点20号');await f.state.prepare();assert.equal(f.calls.at(-1)[2].corrected,false);assert.equal(f.calls.at(-1)[2].address,'广州市天河区补充后的事故地点20号')
})
test('correcting a map point preserves accident-address edit mode unless the vehicle was explicitly moved',async t=>{
 const f=mount(t,async()=>ready());await settle();f.state.changeAddress('广州市天河区已补充事故入口30号');f.state.pickPoint({latitude:23.1,longitude:113.1});await f.state.prepare()
 assert.equal(f.calls.at(-1)[2].corrected,false);assert.deepEqual(f.calls.at(-1)[2].point,{latitude:23.1,longitude:113.1})
})
test('a late previous-plan response cannot clear a newer manual shop selection',async t=>{
 let count=0,oldFinish,newFinish
 const f=mount(t,async()=>{count++;if(count===1)return ready();return new Promise(resolve=>{if(count===2)oldFinish=resolve;else newFinish=resolve})});await settle()
 const old=f.state.prepare(true);f.state.changeAddress('广州市天河区新的事故入口40号');const current=f.state.prepare();newFinish({...ready(),id:'new-plan'});await current;f.state.choose(row(2,2000))
 oldFinish({...ready(),id:'old-plan'});await old;assert.equal(f.state.plan.id,'new-plan');assert.equal(f.state.selected,2)
})

 test('a missing location or an uncalculated destination never opens dispatch',async t=>{
 const f=mount(t,async()=>({...ready(),location:null,status:'APPROXIMATE',approximate:true}));await settle();assert.equal(f.state.canDispatch,false)
 f.state.plan=ready();f.state.selectedShop={...row(1,null),status:'NOT_CALCULATED'};assert.equal(f.state.canDispatch,false)
})
