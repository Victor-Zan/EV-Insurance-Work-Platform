import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'

function mount(client,props={}){
  const source=readFileSync(new URL('../src/features/materials/ParkingLocationPanel.vue',import.meta.url),'utf8')
  const {descriptor}=parse(source)
  let code=ts.transpileModule(compileScript(descriptor,{id:'parking-test'}).content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText
  code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>`const {${names.replace(/\s+as\s+/g,':')}}=Vue;`)
  code=code.replace(/import\s*\{\s*mapStatus\s*\}\s*from[^\n]+/g,'const mapStatus=()=>"";')
  code=code.replace(/import\s*\{\s*httpClient\s*\}\s*from[^\n]+/g,'const httpClient=client;').replace('export default','return')
  const component=new Function('Vue','client',code)(Vue,client);component.render=()=>null
  const renderer=Vue.createRenderer({createComment:()=>({}),insert(){},remove(){},parentNode:()=>null,nextSibling:()=>null})
  const app=renderer.createApp(component,props);app.mount({})
  return {state:app._instance.setupState,unmount:()=>app.unmount()}
}
const settle=async()=>{await Vue.nextTick();await new Promise(resolve=>setImmediate(resolve));await Vue.nextTick()}
const cities=[{id:2,name:'广州市',adcode:'440100'}],districts=[{id:3,name:'天河区',adcode:'440106'}]
const result={token:'synthetic-token',address:'合成停放地点',province:'广东省',city:'广州市',district:'天河区',adcode:'440106',level:'门牌号',status:'REQUIRES_CONFIRMATION',mock:false,point:{longitude:113.1,latitude:23.1}}
function client(post){return {get:async(_url,{params}={params:{}})=>({data:{data:_url.endsWith('/budget')?{callsEnabled:true,reason:'合成测试预算'}:{total:1,records:params.cityId?districts:cities}}}),post}}

test('parking city/district load and explicit action send only selected region and address',async()=>{
  const requests=[];const panel=mount(client(async(...args)=>{requests.push(args);return {data:{data:[{token:result.token,candidate:result}]}}}));await settle()
  const s=panel.state;assert.equal(s.cities[0].id,2);s.city='2';await settle();assert.equal(s.districts[0].id,3)
  s.district='3';s.detail='合成街道10号';await settle();assert.equal(requests.length,0)
  await s.resolve();assert.deepEqual(requests,[['/maps/locations/resolve',{districtId:3,detail:'合成街道10号'}]])
  assert.equal(s.confirmed,undefined);assert.deepEqual(s.candidates,[result]);s.confirmed=result;s.detail='新的合成街道20号';await settle();assert.equal(s.confirmed,undefined);assert.deepEqual(s.candidates,[]);panel.unmount()
})
test('duplicate clicks cause one lookup and changed input rejects stale response',async()=>{
  let finish,calls=0;const panel=mount(client(()=>{calls++;return new Promise(resolve=>{finish=resolve})}));await settle()
  const s=panel.state;s.city='2';await settle();s.district='3';s.detail='合成街道10号';await settle();const pending=s.resolve();await s.resolve();assert.equal(calls,1)
  s.detail='另一合成街道20号';await settle();finish({data:{data:[{token:result.token,candidate:result}]}});await pending;assert.deepEqual(s.candidates,[]);assert.equal(s.confirmed,undefined);panel.unmount()
})
test('quota/network error preserves typed address without auto retry or fake success',async()=>{
  let calls=0;const panel=mount(client(async()=>{calls++;throw new Error('高德调用额度不足')}));await settle()
  const s=panel.state;s.city='2';await settle();s.district='3';s.detail='合成街道10号';await settle();await s.resolve();await settle()
  assert.equal(calls,1);assert.equal(s.detail,'合成街道10号');assert.equal(s.error,'高德调用额度不足');assert.deepEqual(s.candidates,[]);assert.equal(s.confirmed,undefined);panel.unmount()
})
test('city change clears district and confirmation; no city-centre fallback',async()=>{
  const panel=mount(client(async()=>{throw new Error('must not call')}));await settle();const s=panel.state;s.city='2';await settle();s.district='3';await settle();s.confirmed=result;s.city='';await settle();assert.equal(s.district,'');assert.equal(s.confirmed,undefined);assert.deepEqual(s.candidates,[]);panel.unmount()
})
test('explicit confirmation/query, stable retry key and pagination never auto dispatch or recompute',async()=>{
  const requests=[],gets=[]
  const recommendation={queryId:'synthetic-query',provider:'AMAP_WEB_V1',mock:false,scope:'合成测试范围',complete:true,expiresAt:'2026-10-15T10:00:00Z',shops:{page:1,size:20,total:30,records:[]}}
  const fake={post:async(url,body,config)=>{requests.push({url,body,config});return {data:{data:url.endsWith('/confirm')?null:url.endsWith('/recommendations')?recommendation:[{token:result.token,candidate:result}]}}},get:async(url,{params}={params:{}})=>{gets.push(url);return {data:{data:url.includes('/recommendations/')?{...recommendation,shops:{...recommendation.shops,page:params.page}}:url.includes('exclusions')?{total:0,records:[]}:{total:1,records:params.cityId?districts:cities}}}}}
  const panel=mount(fake,{caseId:'synthetic-case'}),s=panel.state;await settle();s.city='2';await settle();s.district='3';s.detail='合成街道10号';await settle()
  await s.resolve();await s.query();assert.equal(requests.length,1)
  await s.confirm(s.candidates[0]);assert.equal(requests.length,2);await s.query();await s.query()
  const queries=requests.filter(r=>r.url.endsWith('/recommendations'));assert.equal(queries.length,2);assert.equal(queries[0].config.headers['Idempotency-Key'],queries[1].config.headers['Idempotency-Key']);assert.equal(queries[0].config.timeout,75000)
  assert.deepEqual(queries[0].body,{locationToken:result.token,shopCityId:null});await s.resultPage(2);assert.equal(requests.length,4);assert.equal(s.recommendation.shops.page,2)
  await s.query(true);const fresh=requests.at(-1);assert.notEqual(fresh.config.headers['Idempotency-Key'],queries[0].config.headers['Idempotency-Key']);assert.equal(requests.some(r=>/dispatch|reassign/.test(r.url)),false);assert.ok(gets.includes('/maps/recommendations/synthetic-query'));panel.unmount()
})

test('closed persistent budget blocks resolve and recompute while preserving entered form',async()=>{
 let calls=0;const fake={get:async(url)=>({data:{data:url.endsWith('/budget')?{callsEnabled:false,reason:'本轮真实调用预算已关闭'}:{total:0,records:[]}}}),post:async()=>{calls++;throw Error('must not call')}};
 const panel=mount(fake,{caseId:'synthetic-case',initialAddress:'合成已填地址'});await settle();const state=panel.state;
 state.district='3';state.confirmed=result;await state.resolve();await state.query();
 assert.equal(calls,0);assert.equal(state.detail,'合成已填地址');assert.equal(state.budget.callsEnabled,false);panel.unmount();
})
