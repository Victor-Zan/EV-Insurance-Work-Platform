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
  code=code.replace(/import\s*\{\s*mapPreviewClientKey\s*\}\s*from[^\n]+/g,'const mapPreviewClientKey=Symbol();')
  code=code.replace(/import\s+(DispatchMapCanvas|DispatchCandidateList|AmapLocationMap)\s+from[^\n]+/g,'const $1={};')
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
  await s.resolve();assert.deepEqual(requests,[['/maps/locations/resolve',{districtId:3,detail:'合成街道10号'},{timeout:15000}]])
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
  const fake={post:async(url,body,config)=>{requests.push({url,body,config});return {data:{data:url.endsWith('/confirm')?null:url.endsWith('/recommendations')?recommendation:[{token:result.token,candidate:result}]}}},get:async(url,{params}={params:{}})=>{gets.push(url);return {data:{data:url.endsWith('/budget')?{callsEnabled:true,reason:'synthetic allowance'}:url.endsWith('/recommendation-estimate')?{candidates:3,maximumRouteCalls:3,routesRemaining:20,fits:true}:url.includes('/recommendations/')?{...recommendation,shops:{...recommendation.shops,page:params.page}}:url.includes('exclusions')?{total:0,records:[]}:{total:1,records:params.cityId?districts:cities}}}}}
  const panel=mount(fake,{caseId:'synthetic-case'}),s=panel.state;await settle();s.city='2';await settle();s.district='3';s.detail='合成街道10号';await settle()
  await s.resolve();await s.query();assert.equal(requests.length,1)
  await s.confirm(s.candidates[0]);assert.equal(requests.length,2);await s.query();assert.equal(requests.length,2);await s.performQuery();await s.query();await s.performQuery()
  const queries=requests.filter(r=>r.url.endsWith('/recommendations'));assert.equal(queries.length,2);assert.equal(queries[0].config.headers['Idempotency-Key'],queries[1].config.headers['Idempotency-Key']);assert.equal(queries[0].config.timeout,75000)
  assert.deepEqual(queries[0].body,{locationToken:result.token,shopCityId:null});await s.resultPage(2);assert.equal(requests.length,4);assert.equal(s.recommendation.shops.page,2)
  await s.query(true);await s.performQuery();const fresh=requests.at(-1);assert.notEqual(fresh.config.headers['Idempotency-Key'],queries[0].config.headers['Idempotency-Key']);assert.equal(requests.some(r=>/dispatch|reassign/.test(r.url)),false);assert.ok(gets.includes('/maps/recommendations/synthetic-query'));panel.unmount()
})

test('closed persistent budget blocks resolve and recompute while preserving entered form',async()=>{
 let calls=0;const fake={get:async(url)=>({data:{data:url.endsWith('/budget')?{callsEnabled:false,reason:'本轮真实调用预算已关闭'}:{total:0,records:[]}}}),post:async()=>{calls++;throw Error('must not call')}};
 const panel=mount(fake,{caseId:'synthetic-case',initialAddress:'合成已填地址'});await settle();const state=panel.state;
 state.district='3';state.confirmed=result;await state.resolve();await state.query();
 assert.equal(calls,0);assert.equal(state.detail,'合成已填地址');assert.equal(state.budget.callsEnabled,false);panel.unmount();
})

test('category exhaustion does not borrow another category and estimate never calls routes',async()=>{
 const posts=[];let routesRemaining=2
 const fake={get:async(url)=>({data:{data:url.endsWith('/budget')?{callsEnabled:true,reason:'合成预算',operations:[{operation:'GEOCODE',total:10,used:10,reserved:0,remaining:0},{operation:'DRIVING',total:20,used:18,reserved:0,remaining:routesRemaining}]}:url.endsWith('/recommendation-estimate')?{candidates:3,maximumRouteCalls:3,routesRemaining,fits:routesRemaining>=3}:{total:0,records:[]}}}),post:async(url)=>{posts.push(url);return {data:{data:{}}}}}
 const panel=mount(fake,{caseId:'synthetic-case'});await settle();const s=panel.state
 s.district='3';s.detail='合成停车地址';await settle();await s.resolve();assert.equal(posts.length,0)
 s.confirmed=result;await s.query();assert.equal(s.estimate.candidates,3);assert.equal(s.estimate.fits,false);await s.performQuery();assert.equal(posts.length,0)
 routesRemaining=3;await s.query();assert.equal(s.estimate.fits,true);assert.equal(posts.length,0)
 s.detail='另一合成地址';await settle();assert.equal(s.estimate,undefined);assert.equal(s.confirmed,undefined);panel.unmount()
})


test('a mock preview cannot fall back to the real transport and live mode rejects grid picks',async()=>{
 assert.throws(()=>mount(client(async()=>{throw Error('must not call')}),{previewOnly:true}),/隔离传输/)
 let posts=0;const panel=mount(client(async()=>{posts++;return {data:{data:[]}}}));await settle()
 panel.state.detail='手工保留的合成地址';await settle();await panel.state.previewPick({x:40,y:60})
 assert.equal(posts,0);assert.equal(panel.state.detail,'手工保留的合成地址');panel.unmount()
})

test('selection is explicit, expiry is enforced and changing the input invalidates selection',async()=>{
 const choices=[];const panel=mount(client(async()=>({data:{data:[]}})),{caseId:'synthetic-case',onChoose:value=>choices.push(value)});await settle();const s=panel.state
 const row={shopId:7,name:'合成网点',status:'READY',metres:1000}
 s.recommendation={queryId:'synthetic-query',expiresAt:new Date(Date.now()+60000).toISOString(),shops:{records:[row]}}
 s.selectShop(999);assert.equal(choices.length,0)
 s.selectShop(7);assert.equal(choices.at(-1).shopId,7);const count=choices.length;s.recommendation.expiresAt=new Date(Date.now()-1000).toISOString();s.selectShop(7);assert.equal(choices.length,count+1);assert.equal(choices.at(-1),undefined);assert.match(s.error,/已过期/)
 s.detail='另一个合成停放位置';await settle();assert.equal(s.recommendation,undefined);assert.equal(s.confirmed,undefined);panel.unmount()
})

test('a late confirmation or route response cannot restore a changed location or selection',async()=>{
 let release;const fake=client(()=>new Promise(resolve=>{release=resolve}));const panel=mount(fake,{caseId:'synthetic-case'});await settle();const s=panel.state
 s.district='3';s.detail='合成原地址';await settle();const confirming=s.confirm(result);s.detail='合成修改地址';await settle();release({data:{data:null}});await confirming;assert.equal(s.confirmed,undefined)
 s.confirmed=result;s.estimate={candidates:1,fits:true,maximumRouteCalls:1};const querying=s.performQuery();s.detail='合成再次修改地址';await settle()
 release({data:{data:{queryId:'stale-query',expiresAt:new Date(Date.now()+60000).toISOString(),shops:{records:[{shopId:7,status:'READY',metres:1000}]}}}});await querying;assert.equal(s.recommendation,undefined);assert.equal(s.confirmed,undefined);panel.unmount()
})


test('changing candidate scope clears the shop but does not falsely dirty an unchanged confirmed parking location',async()=>{
 const pending=[],choices=[];const panel=mount(client(async()=>({data:{data:[]}})),{caseId:'synthetic',onPending:v=>pending.push(v),onChoose:v=>choices.push(v)});await settle();const s=panel.state
 s.confirmed=result;s.shopCity='2';await settle();assert.equal(s.confirmed.token,result.token);assert.equal(pending.at(-1),false);assert.equal(choices.at(-1),undefined)
 s.detail='合成位置确实改变';await settle();assert.equal(s.confirmed,undefined);assert.equal(pending.at(-1),true);panel.unmount()
})


test('a completed recommendation may preselect the nearest known ready row without submitting dispatch',async()=>{
 const choices=[],posts=[];const fake=client(async(url)=>{posts.push(url);return {data:{data:{queryId:'synthetic-default',expiresAt:new Date(Date.now()+60000).toISOString(),shops:{records:[{shopId:4,name:'合成最近网点',status:'READY',metres:1500}]}}}}})
 const panel=mount(fake,{caseId:'synthetic',onChoose:v=>choices.push(v)});await settle();const s=panel.state;s.confirmed=result;s.estimate={candidates:1,fits:true,maximumRouteCalls:1};await s.performQuery()
 assert.equal(choices.at(-1).shopId,4);assert.equal(choices.at(-1).queryId,'synthetic-default');assert.deepEqual(posts,['/maps/cases/synthetic/recommendations']);panel.unmount()
})


test('real map pick is local until explicit reverse verification; changed input discards its late response',async()=>{
 const requests=[];let release;const fake=client(async(url,body)=>{requests.push({url,body});return new Promise(resolve=>{release=resolve})});const panel=mount(fake,{caseId:'synthetic'});await settle();const s=panel.state
 s.district='3';await settle();s.pickRealPoint({longitude:113.1,latitude:23.1});assert.equal(requests.length,0);assert.equal(s.confirmed,undefined)
 const verifying=s.verifyPoint();assert.equal(requests.length,1);assert.equal(requests[0].url,'/maps/locations/point');assert.deepEqual(requests[0].body,{districtId:3,longitude:113.1,latitude:23.1})
 s.detail='用户已修改的合成地址';await settle();release({data:{data:[{token:result.token,candidate:result}]}});await verifying;assert.deepEqual(s.candidates,[]);assert.equal(s.selectedPoint,undefined);panel.unmount()
})

test('confirming a verified point persists the selected canonical address rather than old typed text',async()=>{
 const confirmations=[];const panel=mount(client(async()=>({data:{data:null}})),{onConfirmed:value=>confirmations.push(value)});await settle();const s=panel.state;s.district='3';s.detail='旧的合成手填地址';await settle();await s.confirm({...result,address:'新的已核对选点地址'})
 assert.deepEqual(confirmations.at(-1),{districtId:3,address:'新的已核对选点地址'});panel.unmount()
})
