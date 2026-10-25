import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'
import {workOrderApi} from '../src/features/workorders/work-orders.ts'
const settle=async()=>{await Vue.nextTick();await new Promise(resolve=>setImmediate(resolve));await Vue.nextTick()}
function mount(client,props,componentName='AccidentDistrictReview'){
 const source=readFileSync(new URL(`../src/features/workorders/${componentName}.vue`,import.meta.url),'utf8')
 const {descriptor}=parse(source)
 let code=ts.transpileModule(compileScript(descriptor,{id:'district-test'}).content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>`const {${names.replace(/\s+as\s+/g,':')}}=Vue;`)
 code=code.replace(/import\s*\{\s*httpClient\s*\}\s*from[^\n]+/g,'const httpClient=client;').replace(/import\s*\{\s*workOrderApi\s*\}\s*from[^\n]+/g,'').replace('export default','return')
 const component=new Function('Vue','client','workOrderApi',code)(Vue,client,workOrderApi);component.render=()=>null
 const renderer=Vue.createRenderer({createComment:()=>({}),insert(){},remove(){},parentNode:()=>null,nextSibling:()=>null})
 const app=renderer.createApp(component,props);app.mount({});return {s:app._instance.setupState,unmount:()=>app.unmount()}
}
test('accident review confirms on save with deliberate district and reason, never reads parking as district',async()=>{
 const calls=[],order=Vue.reactive({id:'case',version:9,parkingRegionId:88,parkingAddress:'合成停车位置'})
 const client={get:async()=>({data:{data:{records:[{id:3,name:'广东省 / 深圳市 / 南山区'}],total:1}}}),put:async(...args)=>{calls.push(args);return {data:{data:{}}}}}
 const p=mount(client,{order});await settle();assert.equal(p.s.selected,'');await p.s.save();assert.equal(calls.length,0)
 p.s.province='广东省';p.s.city='深圳市';p.s.selected='3';await p.s.save();assert.equal(calls.length,0)
 p.s.reason='人工核实';await p.s.save();assert.deepEqual(calls,[['/work-orders/case/accident-district',{expectedVersion:9,regionId:3,reason:'人工核实',confirmed:true}]])
 assert.equal(order.parkingRegionId,88);assert.equal(order.accidentRegionId,undefined)
 order.accidentRegionId=3;await settle();assert.equal(p.s.currentName,'广东省 / 深圳市 / 南山区');p.unmount()
})
test('failed or stale district save retains inputs and does not silently change the case',async()=>{
 const p=mount({get:async()=>({data:{data:{records:[{id:3,name:'广东省 / 深圳市 / 南山区'},{id:4,name:'广东省 / 深圳市 / 龙岗区'}],total:2}}}),put:async()=>{throw Error('案件版本已变化')}},{order:{id:'case',version:2,accidentRegionId:3}})
 await settle();p.s.selected='4';p.s.reason='核对新资料';await p.s.save()
 assert.equal(p.s.selected,'4');assert.equal(p.s.reason,'核对新资料');assert.match(p.s.error,/版本已变化/);assert.equal(p.s.busy,false);p.unmount()
})
test('manual dispatch remains available without map calls and explicitly keeps parking separate',async()=>{
 const calls=[],client={get:async(path)=>{calls.push(path);return {data:{data:{total:1,records:path.endsWith('eligible-shops')?[{id:7,name:'合成可派网点'}]:[{id:3,name:'合成区县'}]}}}},put:async(path,body)=>{calls.push([path,body]);return {data:{data:{}}}}}
 const p=mount(client,{order:{id:'case',version:4,accidentRegionId:3}},'ManualDispatchChoice');await settle()
 assert.equal(p.s.shops[0].id,7);p.s.address='合成当前停放地址';await p.s.save();assert.equal(calls.length,2)
 p.s.district='3';await p.s.save();assert.deepEqual(calls.find(c=>Array.isArray(c)),['/work-orders/case/dispatch-location',{expectedVersion:4,regionId:3,address:'合成当前停放地址',confirmed:true}]);assert.equal(calls.at(-1),'/work-orders/case/eligible-shops');assert.equal(p.s.saved,true)
 p.s.changed();assert.equal(p.s.saved,false);assert.equal(calls.some(c=>typeof c==='string'&&c.includes('/maps/')),false);p.unmount()
})


test('accident province and city changes clear dependent choices without saving',async()=>{
 const calls=[]
 const p=mount({get:async()=>({data:{data:{records:[{id:3,name:'广东省 / 深圳市 / 南山区'},{id:4,name:'广东省 / 广州市 / 天河区'}],total:2}}}),put:async(...args)=>{calls.push(args)}},{order:{id:'case',version:2}})
 await settle();p.s.province='广东省';p.s.city='深圳市';p.s.selected='3'
 assert.deepEqual(p.s.districts.map(row=>row.id),[3]);p.s.cityChanged();assert.equal(p.s.selected,'')
 p.s.provinceChanged();assert.equal(p.s.city,'');assert.equal(p.s.selected,'');assert.equal(calls.length,0);p.unmount()
})
