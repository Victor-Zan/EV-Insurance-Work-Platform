import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'
import {pointFromEvent} from '../src/features/materials/amap-js.ts'
function setup(overrides={}){
 const events=[],requests=[],maps=[],fetches=[],hooks={};let sdkCalls=0,bootstrap,sdk
 class Map {constructor(_host,options){this.options=options;this.events={};this.destroyed=false;maps.push(this)}on(name,fn){this.events[name]=fn}add(){}remove(){}setCenter(){}destroy(){this.destroyed=true}}
 class Marker {constructor(options){this.position=options.position;this.events={}}on(n,fn){this.events[n]=fn}setPosition(p){this.position=p}setDraggable(){}getPosition(){return {getLng:()=>this.position[0],getLat:()=>this.position[1]}}}
 const client={get:async path=>{requests.push(['get',path]);return {data:{data:{enabled:true,configured:true,reason:'synthetic'}}}},post:async path=>{requests.push(['post',path]);return bootstrap?bootstrap():{data:{data:{publicKey:'synthetic-public-key',servicePath:'/_AMapService'}}}}}
 const source=readFileSync(new URL('../src/features/materials/AmapLocationMap.vue',import.meta.url),'utf8'),{descriptor}=parse(source)
 let code=ts.transpileModule(compileScript(descriptor,{id:'js-map-view'}).content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>`const {${names.replace(/\s+as\s+/g,':')}}=__vue;`)
 code=code.replace(/import\s*\{\s*httpClient\s*\}\s*from[^\n]+/g,'const httpClient=__client;')
 code=code.replace(/import\s*\{[^}]+\}\s*from\s*['"]\.\/amap-js['"];?/g,'const {loadAmapJs,pointFromEvent}=__map;').replace('export default','return')
 const component=new Function('__vue','__client','__map','window','document','fetch',code)({...Vue,onMounted:fn=>{hooks.mount=fn},onUnmounted:fn=>{hooks.unmount=fn}},client,{pointFromEvent,loadAmapJs:async()=>{sdkCalls++;return sdk?sdk():{Map,Marker}}},{location:{origin:'http://localhost:5210'}},{createElement:()=>({setAttribute(){}})},async(...args)=>{fetches.push(args)})
 const props=Vue.reactive({disabled:false,...overrides}),scope=Vue.effectScope(),state=scope.run(()=>component.setup(props,{expose(){},emit:(...e)=>events.push(e)}));state.host.value={}
 return {events,requests,maps,fetches,hooks,props,scope,state,sdkCalls:()=>sdkCalls,setBootstrap:fn=>{bootstrap=fn},setSdk:fn=>{sdk=fn}}
}
test('mount only reads local config; explicit loading waits for complete and map clicks stay local',async t=>{
 const f=setup();t.after(()=>{f.hooks.unmount();f.scope.stop()});await f.hooks.mount();assert.equal(f.sdkCalls(),0);assert.equal(f.requests.filter(r=>r[0]==='post').length,0)
 await Promise.all([f.state.load(),f.state.load()]);assert.equal(f.sdkCalls(),1);assert.equal(f.requests.filter(r=>r[0]==='post').length,1);assert.equal(f.state.state.value,'loading')
 f.maps[0].events.complete();assert.equal(f.state.state.value,'ready')
 f.maps[0].events.click({lnglat:{getLng:()=>113.1,getLat:()=>23.1}});assert.deepEqual(f.events.at(-1),['pick',{longitude:113.1,latitude:23.1}]);assert.equal(f.requests.length,2)
 f.props.disabled=true;await Vue.nextTick();f.maps[0].events.click({lnglat:{getLng:()=>114,getLat:()=>24}});assert.equal(f.events.length,1)
})
test('leaving during bootstrap cannot initialize a late map and revokes its cookie without vendor calls',async()=>{
 const f=setup();let release;f.setBootstrap(()=>new Promise(resolve=>{release=resolve}));await f.hooks.mount();const loading=f.state.load();f.hooks.unmount();release({data:{data:{publicKey:'synthetic-public-key',servicePath:'/_AMapService'}}});await loading
 assert.equal(f.sdkCalls(),0);assert.equal(f.maps.length,0);assert.deepEqual(f.fetches[0],[ '/_AMapService/session',{method:'DELETE',credentials:'same-origin',keepalive:true}]);f.scope.stop()
})

test('dispatch autoload starts one SDK bootstrap on mount and uses a road-level zoom without geolocation',async t=>{
 const f=setup({autoLoad:true,approximate:true,point:{longitude:114,latitude:22.5}});t.after(()=>{f.hooks.unmount();f.scope.stop()})
 await f.hooks.mount();assert.equal(f.sdkCalls(),1);assert.equal(f.maps[0].options.zoom,13);assert.deepEqual(f.maps[0].options.center,[114,22.5])
 await f.state.load();assert.equal(f.sdkCalls(),1);assert.equal(f.requests.filter(r=>r[0]==='post').length,1)
})

test('explicit retry revokes the failed grant, preserves the point and recovers from SDK failure',async t=>{
 const point={longitude:114,latitude:22.5},f=setup({point});t.after(()=>{f.hooks.unmount();f.scope.stop()})
 f.setSdk(()=>{throw new Error('synthetic network failure')});await f.hooks.mount();await f.state.load()
 assert.equal(f.state.state.value,'failed');assert.match(f.state.error.value,/脚本/);assert.equal(f.requests.filter(r=>r[0]==='post').length,1)
 const source=readFileSync(new URL('../src/features/materials/AmapLocationMap.vue',import.meta.url),'utf8')
 assert.doesNotMatch(source,/:disabled="[^"]*state==='failed'/);assert.match(source,/重新加载地图/)
 f.setSdk(undefined);await f.state.load();assert.equal(f.fetches.length,1);assert.equal(f.requests.filter(r=>r[0]==='post').length,2)
 f.maps[0].events.complete();assert.equal(f.state.state.value,'ready');assert.deepEqual(f.props.point,point)
})

test('failed map is destroyed and its late events cannot fail the replacement map',async t=>{
 const f=setup();t.after(()=>{f.hooks.unmount();f.scope.stop()});await f.hooks.mount();await f.state.load()
 const first=f.maps[0];first.events.error();assert.equal(first.destroyed,true);assert.equal(f.state.state.value,'failed')
 await f.state.load();f.maps[1].events.complete();first.events.error();assert.equal(f.state.state.value,'ready');assert.equal(f.maps[1].destroyed,false)
})

test('an old backend proxy is diagnosed before SDK requests and can recover after runtime synchronization',async t=>{
 const f=setup();t.after(()=>{f.hooks.unmount();f.scope.stop()})
 f.setBootstrap(async()=>({data:{data:{publicKey:'synthetic-public-key',servicePath:'/api/v1/maps/js-proxy'}}}))
 await f.hooks.mount();await f.state.load();assert.equal(f.sdkCalls(),0);assert.equal(f.state.state.value,'failed');assert.match(f.state.error.value,/前后端版本/)
 f.setBootstrap(undefined);await f.state.load();f.maps[0].events.complete();assert.equal(f.state.state.value,'ready');assert.equal(f.sdkCalls(),1);assert.equal(f.fetches.length,1)
})
