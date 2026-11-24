import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'

const settle=async()=>{await Vue.nextTick();await new Promise(resolve=>setImmediate(resolve));await Vue.nextTick()}
const entry={id:22,name:'【待核验】合成维修门店',address:'广东省深圳市龙岗区合成地址10号',districtId:7,district:'龙岗区',contactPhone:null}
const choice={token:'synthetic-location',candidate:{address:entry.address,point:{latitude:22.6,longitude:114.2},status:'REQUIRES_CONFIRMATION',mock:false}}
function mount(t,post=async()=>({choices:[choice]}),overrides={}){
 const calls=[],events=[],client={get:async(...args)=>{calls.push(['get',...args]);return {data:{data:{records:[entry],total:2718}}}},post:async(...args)=>{calls.push(['post',...args]);return {data:{data:await post(...args)}}}}
 const {descriptor}=parse(readFileSync(new URL('../src/features/workorders/DirectoryDispatchPanel.vue',import.meta.url),'utf8'))
 let code=ts.transpileModule(compileScript(descriptor,{id:'directory-test'}).content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>`const {${names.replace(/\s+as\s+/g,':')}}=Vue;`)
 code=code.replace(/import\s*\{\s*httpClient\s*\}\s*from[^\n]+/g,'const httpClient=client;')
 code=code.replace(/import\s*\{\s*ElMessageBox\s*\}\s*from[^\n]+/g,'const ElMessageBox={};')
 code=code.replace(/import\s*\{\s*ApiError\s*\}\s*from[^\n]+/g,'class ApiError extends Error{};')
 code=code.replace(/import\s+AmapLocationMap\s+from[^\n]+/g,'const AmapLocationMap={};').replace('export default','return')
 code=code.replace(/import\s+AccidentDistrictReview\s+from[^\n]+/g,'const AccidentDistrictReview={};')
 const component=new Function('Vue','client',code)(Vue,client);component.render=()=>null
 const props=Vue.reactive({caseId:'synthetic-case',planId:'synthetic-plan',pickupReady:true,accidentReady:true,disabled:false,...overrides})
 const renderer=Vue.createRenderer({createComment:()=>({}),insert(){},remove(){},parentNode:()=>null,nextSibling:()=>null})
 const app=renderer.createApp({render:()=>Vue.h(component,{...props,onChanged:order=>events.push(order)})});app.mount({});t.after(()=>app.unmount())
 return {state:app._instance.subTree.component.setupState,calls,events,props}
}
test('cloud candidates remain browsable with zero cooperative shops and no pickup plan',async t=>{
 const f=mount(t,undefined,{planId:undefined,pickupReady:false,accidentReady:false});await settle()
 assert.equal(f.state.total,2718);assert.equal(f.state.rows[0].id,22);assert.equal(f.calls.length,1);assert.equal(f.calls[0][0],'get');assert.equal(f.state.canDispatch,false)
 f.state.query='龙岗';await f.state.search(2);assert.equal(f.calls.at(-1)[2].params.page,2);assert.equal(f.calls.at(-1)[2].params.query,'龙岗')
})
test('choosing a shop only resolves location; explicit verification is required before one final dispatch',async t=>{
 const done={id:'synthetic-case',status:'PENDING_ACCEPTANCE'},f=mount(t,async path=>path.endsWith('/dispatch')?done:{choices:[choice]});await settle()
 await f.state.choose(entry);await settle();assert.equal(f.state.canDispatch,false);await f.state.dispatch();assert.equal(f.calls.filter(c=>c[1].endsWith('/dispatch')).length,0)
 f.state.verified=true;assert.equal(f.state.canDispatch,true);await f.state.dispatch();assert.equal(f.events.length,1);assert.equal(f.events[0].status,'PENDING_ACCEPTANCE')
 const sent=f.calls.find(c=>c[1].endsWith('/dispatch'));assert.equal(sent[2].sourceShopId,22);assert.equal(sent[2].verified,true);assert.equal(sent[2].planId,'synthetic-plan')
})
test('ambiguous or corrected position clears verification; stale pickup plan cannot submit',async t=>{
 const f=mount(t,async()=>({choices:[choice,{...choice,token:'other-location'}]}));await settle();await f.state.choose(entry);await settle()
 assert.equal(f.state.token,'');assert.equal(f.state.canDispatch,false);f.state.token=choice.token;await settle();f.state.verified=true;assert.equal(f.state.canDispatch,true)
 f.state.pick({latitude:22.7,longitude:114.3});assert.equal(f.state.canDispatch,false);assert.equal(f.state.verified,false)
 f.props.planId='replacement-plan';await settle();assert.equal(f.state.selected,undefined);assert.equal(f.state.canDispatch,false)
})
test('uncertain submission retries use the same idempotency key and never silently choose another shop',async t=>{
 const f=mount(t,async path=>{if(path.endsWith('/dispatch'))throw Error('synthetic connection error');return {choices:[choice]}});await settle();await f.state.choose(entry);await settle();f.state.verified=true
 await f.state.dispatch();await f.state.dispatch();const attempts=f.calls.filter(c=>c[1].endsWith('/dispatch'));assert.equal(attempts.length,2);assert.equal(attempts[0][3].headers['Idempotency-Key'],attempts[1][3].headers['Idempotency-Key']);assert.equal(f.state.selected.id,22);assert.equal(f.events.length,0)
})


test('the final action states exactly which prerequisite is missing after shop verification',async t=>{
 const f=mount(t,undefined,{accidentReady:false});await settle();await f.state.choose(entry);await settle();f.state.verified=true
 assert.equal(f.state.canDispatch,false);assert.equal(f.state.blockingReasons.length,1);assert.match(f.state.blockingReasons[0],/事故区县尚未保存/)
 f.props.accidentReady=true;await settle();assert.equal(f.state.canDispatch,true)
 f.props.pickupReady=false;await settle();assert.equal(f.state.canDispatch,false);assert.match(f.state.blockingReasons[0],/接车位置/)
})
