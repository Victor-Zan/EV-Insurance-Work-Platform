import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'
import * as MapHelpers from '../src/features/workorders/dispatch-map.ts'

const location={label:'模拟接车地址',point:{latitude:22.55,longitude:113.95},mock:true}
const shops=[{id:1,name:'模拟网点甲',distanceMetres:1000,mockLocation:location},{id:2,name:'模拟网点乙',distanceMetres:2000,mockLocation:{...location,point:{latitude:22.56,longitude:113.96}}}]
function setup(){
 const {descriptor}=parse(readFileSync(new URL('../src/features/workorders/MockDispatchLocation.vue',import.meta.url),'utf8'))
 let code=ts.transpileModule(compileScript(descriptor,{id:'dispatch-test'}).content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>'const {'+names.replace(/\s+as\s+/g,':')+'}=__vue;')
 code=code.replace(/import\s*\{[^}]+\}\s*from\s*['"]element-plus['"];?/g,'')
 code=code.replace(/import\s*\{\s*httpClient\s*\}\s*from\s*['"][^'"]+['"];?/g,'const httpClient={};')
 code=code.replace(/import\s*\{\s*workOrderApi\s*\}\s*from\s*['"][^'"]+['"];?/g,'const workOrderApi=()=>__api;')
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"][^'"]*dispatch-map['"];?/g,(_,names)=>'const {'+names+'}=__helpers;').replace('export default','return')
 const calls=[],events=[],props=Vue.reactive({caseId:'synthetic-map-case',order:{version:1,accidentAddress:'深圳市合成接车地址'},selectedShopId:undefined})
 let mounted
 const api={async mapCities(query){calls.push(['cities',query]);return {records:[{id:11,name:'深圳市',code:'SZ'}],total:1}},async mapLocation(address){calls.push(['geocode',address]);return location},async dispatchLocation(id,version,region,address){calls.push(['save',id,version,region,address]);props.order.version++;return props.order},async mapShops(){calls.push(['shops']);return {records:shops,total:2}}}
 const component=new Function('__vue','__api','__helpers',code)({...Vue,onMounted(fn){mounted=fn}},api,MapHelpers)
 const context=component.setup(props,{expose(){},emit(name,value){events.push([name,value]);if(name==='select')props.selectedShopId=value}})
 return {context,props,calls,events,api,mount:()=>mounted()}
}
test('chat address prefill needs confirmation; locating recommends the nearest and manual selection remains possible',async()=>{
 const f=setup();await f.mount();assert.equal(f.context.address.value,'深圳市合成接车地址');assert.equal(f.context.region.value,11);assert.equal(f.calls.some(c=>c[0]==='save'),false)
 f.context.confirmed.value=true;await f.context.locate();assert.equal(f.props.selectedShopId,1);assert.equal(f.context.saved.value,true)
 assert.deepEqual(f.calls.map(c=>c[0]),['cities','geocode','save','shops']);f.context.selectShop(2);assert.equal(f.props.selectedShopId,2)
 f.context.changed();assert.equal(f.props.selectedShopId,undefined);assert.equal(f.context.origin.value,undefined);assert.equal(f.context.saved.value,false);assert.deepEqual(f.events.at(-1),['pending',true])
})
test('failed geocoding cannot save an address or enable dispatch',async()=>{
 const f=setup();await f.mount();f.context.confirmed.value=true;f.api.mapLocation=async()=>{throw Error('模拟定位失败')};await f.context.locate()
 assert.match(f.context.error.value,/定位失败/);assert.equal(f.calls.some(c=>c[0]==='save'),false);assert.equal(f.props.selectedShopId,undefined);assert.deepEqual(f.events.at(-1),['pending',true])
})
test('reopening a confirmed location restores confirmation and retains the assigned shop without another save',async()=>{
 const f=setup();f.props.order.dispatchAddressConfirmed=true;f.props.order.currentAssignment={shopId:2};await f.mount()
 assert.equal(f.context.confirmed.value,true);assert.equal(f.context.saved.value,true);assert.equal(f.props.selectedShopId,2);assert.equal(f.calls.some(c=>c[0]==='save'),false)
})
test('map projection stays finite for coincident points and city context is not duplicated',()=>{
 for(const point of MapHelpers.mapMarkers(location,[shops[0]])){assert.ok(Number.isFinite(point.x)&&Number.isFinite(point.y));assert.ok(point.x>=0&&point.x<=600&&point.y>=0&&point.y<=320)}
 assert.equal(MapHelpers.locationAddress('深圳市','南山区合成地址'),'深圳市南山区合成地址');assert.equal(MapHelpers.locationAddress('深圳市','深圳市合成地址'),'深圳市合成地址')
 assert.equal(MapHelpers.recommendedShop(shops),1);assert.equal(MapHelpers.recommendedShop([]),undefined)
})
