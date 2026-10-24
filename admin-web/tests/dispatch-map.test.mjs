import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'
import * as MapHelpers from '../src/features/workorders/dispatch-map.ts'

const location={label:'模拟接车地址',point:{latitude:22.55,longitude:113.95},mock:true}
const shops=[{id:1,name:'模拟网点甲',distanceMetres:1000,mockLocation:location},{id:2,name:'模拟网点乙',distanceMetres:2000,mockLocation:{...location,point:{latitude:22.56,longitude:113.96}}}]
export function setupManual(overrides={}){
 const calls=[],events=[],props=Vue.reactive({order:{id:'synthetic-map-case',status:'PENDING_DISPATCH',version:1,accidentRegionId:3,parkingAddress:'',parkingRegionId:null,dispatchAddressConfirmed:false},selectedShopId:undefined,expanded:true,...overrides})
 const api={async accidentDistricts(){calls.push(['districts']);return {records:[{id:3,name:'模拟省 / 模拟市 / 模拟区'}],total:1}},async shops(id,page){calls.push(['shops',id,page]);return {records:shops,total:2}},async dispatchLocation(id,version,region,address){calls.push(['save',id,version,region,address]);return {...props.order,version:version+1,parkingRegionId:region,parkingAddress:address,dispatchAddressConfirmed:true}}}
 return component('ManualDispatchChoice',props,api,calls,events)
}
export function component(name,props,api,calls=[],events=[]){
 const {descriptor}=parse(readFileSync(new URL(`../src/features/workorders/${name}.vue`,import.meta.url),'utf8'))
 let code=ts.transpileModule(compileScript(descriptor,{id:'dispatch-test'}).content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>'const {'+names.replace(/\s+as\s+/g,':')+'}=__vue;')
 code=code.replace(/import\s*\{\s*httpClient\s*\}\s*from\s*['"][^'"]+['"];?/g,'const httpClient={};')
 code=code.replace(/import\s*\{\s*workOrderApi\s*\}\s*from\s*['"][^'"]+['"];?/g,'const workOrderApi=()=>__api;')
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"][^'"]*dispatch-map['"];?/g,(_,names)=>'const {'+names+'}=__helpers;')
 code=code.replace(/import\s+(\w+)\s+from\s*['"][^'"]+['"];?/g,(_,name)=>'const '+name+'={};').replace('export default','return')
 let mounted=async()=>{};const scope=Vue.effectScope()
 const compiled=new Function('__vue','__api','__helpers',code)({...Vue,onMounted(fn){mounted=fn}},api,MapHelpers)
 const context=scope.run(()=>compiled.setup(props,{expose(){},emit(name,value){events.push([name,value]);if(name==='select')props.selectedShopId=value;if(name==='changed'&&value)Object.assign(props.order,value)}}))
 return {context,props,calls,events,api,scope,mount:()=>mounted()}
}

test('manual parking confirmation and explicit shop choice do not require mock geocoding or a recommendation',async t=>{
 const f=setupManual();t.after(()=>f.scope.stop());await f.mount()
 f.context.address.value='模拟市测试入口';f.context.district.value='3';f.context.confirmed.value=true
 await f.context.save();await Vue.nextTick()
 assert.equal(f.context.saved.value,true);assert.equal(f.props.selectedShopId,undefined)
 f.context.select(2);assert.equal(f.props.selectedShopId,2)
 assert.ok(f.calls.some(c=>c[0]==='save'));assert.equal(f.calls.some(c=>c[0]==='geocode'),false)
 assert.deepEqual(f.events.at(-1),['pending',false])
 f.context.confirmed.value=false;f.context.confirmationChanged();assert.equal(f.props.selectedShopId,undefined);assert.equal(f.context.saved.value,false);assert.deepEqual(f.events.at(-1),['pending',true])
 f.context.changed();assert.equal(f.props.selectedShopId,undefined);assert.equal(f.context.saved.value,false);assert.deepEqual(f.events.at(-1),['pending',true])
})

test('failed optional mock geocoding never saves, changes selection or blocks legal manual dispatch',async t=>{
 const props=Vue.reactive({caseId:'synthetic',order:{parkingAddress:'合成位置',dispatchAddressConfirmed:true},selectedShopId:2})
 const f=component('MockDispatchLocation',props,{async mapLocation(){throw Error('模拟定位失败')},async mapShops(){throw Error('must not continue')}});t.after(()=>f.scope.stop())
 await f.context.preview();assert.match(f.context.error.value,/不影响.*人工派单/)
 assert.equal(props.selectedShopId,2);assert.deepEqual(f.events,[]);assert.equal(f.context.busy.value,false)
})

test('reopening a confirmed parking location restores confirmation independently of city pages and does not auto-select a shop',async t=>{
 const f=setupManual({order:{id:'synthetic',version:4,accidentRegionId:3,parkingRegionId:999,parkingAddress:'已保存入口',dispatchAddressConfirmed:true}});t.after(()=>f.scope.stop());await f.mount()
 assert.equal(f.context.confirmed.value,true);assert.equal(f.context.saved.value,true);assert.equal(f.context.district.value,'999')
 assert.equal(f.props.selectedShopId,undefined);assert.equal(f.calls.some(c=>c[0]==='save'),false)
 f.context.select(1);assert.equal(f.props.selectedShopId,1);assert.deepEqual(f.events.at(-1),['pending',false])
})

test('map projection stays finite for coincident points and city context is not duplicated',()=>{
 for(const point of MapHelpers.mapMarkers(location,[shops[0]])){assert.ok(Number.isFinite(point.x)&&Number.isFinite(point.y));assert.ok(point.x>=0&&point.x<=600&&point.y>=0&&point.y<=320)}
 assert.equal(MapHelpers.locationAddress('深圳市','南山区合成地址'),'深圳市南山区合成地址');assert.equal(MapHelpers.locationAddress('深圳市','深圳市合成地址'),'深圳市合成地址')
 assert.equal(MapHelpers.recommendedShop(shops),1);assert.equal(MapHelpers.recommendedShop([]),undefined)
})

test('missing district or unconfirmed address blocks saving and selection with explicit guidance',async t=>{
 const f=setupManual();t.after(()=>f.scope.stop());f.props.order.accidentRegionId=null;await f.mount()
 assert.match(f.context.missing.value,/事故服务区县/);assert.equal(f.calls.some(c=>c[0]==='shops'),false)
 await f.context.save();assert.match(f.context.error.value,/补全事故区县/);assert.equal(f.calls.some(c=>c[0]==='save'),false)
 f.props.order.accidentRegionId=3;await Vue.nextTick();await Promise.resolve();f.context.select(1);assert.equal(f.props.selectedShopId,undefined)
 assert.match(f.context.missing.value,/确认车辆/)
})

test('candidate failures release loading and can retry without losing saved address; empty lists cannot select',async t=>{
 const f=setupManual({order:{id:'synthetic',version:2,accidentRegionId:3,parkingAddress:'已核对地址',parkingRegionId:3,dispatchAddressConfirmed:true}});t.after(()=>f.scope.stop())
 f.api.shops=async()=>{throw Error('候选读取失败')};await f.mount()
 assert.equal(f.context.shopBusy.value,false);assert.match(f.context.shopError.value,/读取失败/);f.context.select(1);assert.equal(f.props.selectedShopId,undefined)
 assert.equal(f.context.address.value,'已核对地址');assert.equal(f.context.saved.value,true)
 f.api.shops=async()=>({records:[],total:0});await f.context.moreShops();assert.equal(f.context.shopError.value,'');assert.match(f.context.missing.value,/暂无启用/)
 f.context.select(1);assert.equal(f.props.selectedShopId,undefined)
 f.api.shops=async()=>({records:[{id:9,name:'合格测试网点'}],total:1});await f.context.moreShops();f.context.select(9);assert.equal(f.props.selectedShopId,9)
})

test('failed parking save preserves manual inputs and retry cannot duplicate a pending save',async t=>{
 const f=setupManual();t.after(()=>f.scope.stop());await f.mount()
 f.context.district.value='3';f.context.address.value='手工填写的测试地址';f.context.confirmed.value=true
 f.api.dispatchLocation=async()=>{throw Error('版本冲突，请刷新后重试')};await f.context.save()
 assert.equal(f.context.busy.value,false);assert.equal(f.context.saved.value,false);assert.equal(f.context.address.value,'手工填写的测试地址');assert.equal(f.context.confirmed.value,true)
 let release,count=0
 f.api.dispatchLocation=async()=>{count++;return new Promise(resolve=>{release=resolve})}
 const first=f.context.save();await f.context.save();assert.equal(count,1)
 release({...f.props.order,parkingAddress:f.context.address.value,parkingRegionId:3,dispatchAddressConfirmed:true,version:2});await first;await Vue.nextTick()
 assert.equal(f.context.saved.value,true);f.context.select(2);assert.equal(f.props.selectedShopId,2)
})

test('district changes discard old selection and late candidate results cannot replace the new eligible set',async t=>{
 const f=setupManual({order:{id:'synthetic',version:2,accidentRegionId:3,parkingAddress:'已核对地址',parkingRegionId:3,dispatchAddressConfirmed:true}});t.after(()=>f.scope.stop())
 const releases=[];f.api.shops=async()=>new Promise(resolve=>releases.push(resolve))
 const first=f.mount();await Promise.resolve();f.props.order.accidentRegionId=4;await Vue.nextTick()
 assert.equal(releases.length,2);releases[1]({records:[{id:8,name:'新服务区网点'}],total:1});await Promise.resolve();await Vue.nextTick()
 releases[0]({records:[{id:7,name:'旧服务区网点'}],total:1});await first
 assert.deepEqual(f.context.shops.value.map(row=>row.id),[8]);f.context.select(7);assert.equal(f.props.selectedShopId,undefined)
 f.context.select(8);assert.equal(f.props.selectedShopId,8);assert.equal(f.context.shopBusy.value,false)
})

test('saved parking changes invalidate selection and retain unsaved input for rechecking',async t=>{
 const f=setupManual({order:{id:'synthetic',version:2,accidentRegionId:3,parkingAddress:'旧保存地址',parkingRegionId:3,dispatchAddressConfirmed:true}});t.after(()=>f.scope.stop());await f.mount();f.context.select(1)
 f.context.address.value='正在编辑地址';f.context.changed();f.props.order.parkingAddress='其他窗口保存地址';await Vue.nextTick()
 assert.equal(f.props.selectedShopId,undefined);assert.equal(f.context.saved.value,false);assert.equal(f.context.address.value,'正在编辑地址');assert.match(f.context.error.value,/重新核对/)
})

test('optional preview neither auto-loads nor selects and discards late results after a parking change',async t=>{
 const props=Vue.reactive({caseId:'synthetic',order:{parkingAddress:'合成位置',dispatchAddressConfirmed:true},selectedShopId:2})
 let release,calls=0
 const f=component('MockDispatchLocation',props,{async mapLocation(){calls++;return new Promise(resolve=>{release=resolve})},async mapShops(){return {records:shops,total:2}}});t.after(()=>f.scope.stop())
 assert.equal(calls,0);const pending=f.context.preview();props.order.parkingAddress='新位置';await Vue.nextTick();release(location);await pending
 assert.equal(f.context.origin.value,undefined);assert.deepEqual(f.context.candidates.value,[]);assert.equal(props.selectedShopId,2);assert.deepEqual(f.events,[])
})

test('after dispatch the existing shop is shown and a new manual choice is labelled reassignment',async t=>{
 const f=setupManual({order:{id:'synthetic',status:'PENDING_ACCEPTANCE',version:5,accidentRegionId:3,parkingAddress:'已确认位置',parkingRegionId:3,dispatchAddressConfirmed:true,currentAssignment:{shopId:1}}});t.after(()=>f.scope.stop());await f.mount()
 assert.match(f.context.missing.value,/当前派单网点：模拟网点甲/);assert.equal(f.props.selectedShopId,undefined)
 f.context.select(2);assert.match(f.context.missing.value,/模拟网点乙.*改派/)
})
