import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'
import {renderToString} from '@vue/server-renderer'
import {mapStatus} from '../src/features/materials/map-recommendation.ts'
function component(name,inlineTemplate=false){
 const source=readFileSync(new URL(`../src/features/materials/${name}.vue`,import.meta.url),'utf8')
 const {descriptor}=parse(source)
 let code=ts.transpileModule(compileScript(descriptor,{id:'dispatch-ui-test',inlineTemplate}).content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>`const {${names.replace(/\s+as\s+/g,':')}}=Vue;`)
 code=code.replace(/import\s*\{\s*mapStatus\s*\}\s*from[^\n]+/g,'const mapStatus=__mapStatus;').replace('export default','return')
 return new Function('Vue','__mapStatus',code)(Vue,mapStatus)
}
function setup(name,values){const props=Vue.reactive(values),events=[],scope=Vue.effectScope();const state=scope.run(()=>component(name).setup(props,{expose(){},emit:(...args)=>events.push(args)}));return {state,props,events,scope}}
const row=(shopId=1)=>({shopId,name:'合成网点'+shopId,address:'合成地址',qualification:'合成资格',status:'READY',metres:1800,seconds:420,calculatedAt:null,mock:false})
test('mock canvas supports mouse and keyboard without converting a grid into real coordinates',t=>{
 const f=setup('DispatchMapCanvas',{preview:true,disabled:false});t.after(()=>f.scope.stop())
 f.state.pick({currentTarget:{getBoundingClientRect:()=>({left:0,top:0,width:200,height:100})},clientX:50,clientY:60})
 assert.deepEqual(f.events.at(-1),['pick',{x:25,y:60}])
 let prevented=false;f.state.key({key:'ArrowRight',preventDefault(){prevented=true}});f.state.keyboardPick({detail:0})
 assert.equal(prevented,true);assert.deepEqual(f.events.at(-1),['pick',{x:27,y:60}]);assert.equal('latitude' in f.events.at(-1)[1],false)
 f.props.disabled=true;f.state.keyboardPick({detail:0});assert.equal(f.events.length,2)
 f.props.disabled=false;f.props.preview=false;f.state.pick({});assert.equal(f.events.length,2)
})
test('radio selection rejects unavailable/expired entries and does not submit dispatch',t=>{
 const f=setup('DispatchCandidateList',{rows:[row()],expiresAt:new Date(Date.now()+60000).toISOString(),busy:false});t.after(()=>f.scope.stop())
 f.state.choose(row());assert.deepEqual(f.events,[['choose',1]])
 f.props.busy=true;f.state.choose(row());assert.equal(f.events.length,1)
 f.props.busy=false;f.state.choose({...row(),status:'MAP_NO_ROUTE'});assert.equal(f.events.length,1)
 f.props.expiresAt=new Date(Date.now()-1000).toISOString();f.state.choose(row());assert.equal(f.events.length,1)
 assert.equal(f.events.some(e=>e[0]==='dispatch'),false)
})
test('candidate markup exposes one-choice controls, unknown distance and explicit mock labels',async()=>{
 const html=await renderToString(Vue.createSSRApp(component('DispatchCandidateList',true),{rows:[row(),{...row(2),metres:null,seconds:null,status:'MAP_NO_ROUTE'}],selectedShopId:1,preview:true,total:2,page:1}))
 assert.match(html,/type="radio"/);assert.match(html,/checked/);assert.match(html,/disabled/);assert.match(html,/1\.80 km/);assert.match(html,/未知/);assert.match(html,/非真实道路距离/);assert.match(html,/已选择/);assert.match(html,/合成网点1/)
})
test('live map never pretends to load an interactive Amap base layer',async()=>{
 const html=await renderToString(Vue.createSSRApp(component('DispatchMapCanvas',true),{preview:false,confirmed:false}))
 assert.match(html,/非高德底图/);assert.match(html,/尚未启用/);assert.doesNotMatch(html,/class="pick-surface"/)
})
test('development preview has a mandatory local adapter and no business submit or supplier URLs',()=>{
 const source=readFileSync(new URL('../src/dev/DispatchPreview.vue',import.meta.url),'utf8')
 assert.match(source,/axios\.create\(\{adapter:/);assert.match(source,/预览不支持此操作，未访问后端/)
 assert.doesNotMatch(source,/restapi\.amap|webapi\.amap|baidubce|fetch\(|XMLHttpRequest|workOrderApi/)
 const panel=readFileSync(new URL('../src/features/materials/ParkingLocationPanel.vue',import.meta.url),'utf8')
 assert.match(panel,/previewOnly&&!injectedPreviewClient/);assert.match(panel,/requestGeneration!==generation/)
 assert.doesNotMatch(panel,/VITE_.*KEY|AMAP_WEB_SERVICE_KEY/)
})
