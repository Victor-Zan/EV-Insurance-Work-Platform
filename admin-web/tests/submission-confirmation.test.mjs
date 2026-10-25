import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'
import {mayConfirm} from '../src/features/pricing/prices.ts'

function setup(path,props,deps){
 const {descriptor}=parse(readFileSync(new URL('../src/features/'+path,import.meta.url),'utf8'))
 let code=ts.transpileModule(compileScript(descriptor,{id:'submission-confirmation'}).content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]([^'"]+)['"];?/g,(_,names,path)=>'const {'+names.replace(/\s+as\s+/g,':')+'}='+(path==='vue'?'__vue':'__deps')+';')
 code=code.replace(/import\s+(\w+)\s+from\s*['"][^'"]+['"];?/g,(_,name)=>'const '+name+'={};').replace('export default','return')
 const component=new Function('__vue','__deps',code)({...Vue,onMounted(){},onUnmounted(){}},deps),scope=Vue.effectScope(),events=[]
 const context=scope.run(()=>component.setup(Vue.reactive(props),{expose(){},emit(...args){events.push(args)}}))
 return {context,events,stop:()=>scope.stop()}
}

test('temporary shop registration confirms only on click; editing does not register and expired candidates stay blocked',async t=>{
 const calls=[],f=setup('materials/PublicWorkshopPanel.vue',{caseId:'synthetic-case',locationToken:'synthetic-location',parkingReady:true},{httpClient:{async post(path,body){calls.push({path,body});return {data:{data:{shopId:7,name:body.name,notice:'已登记'}}}}}});t.after(f.stop)
 const s=f.context
 s.settings.value={enabled:true,providerManaged:true};s.accept({queryId:'synthetic-query',expiresAt:new Date(Date.now()+60000).toISOString(),rows:[]})
 await s.createTemporary();assert.equal(calls.length,0)
 s.select({id:'synthetic-shop',name:'合成维修门店',address:'合成路测试入口',point:{longitude:113,latitude:23}})
 s.ownAddress.value='人工核实的合成地址';await Vue.nextTick();assert.equal(calls.length,0)
 await s.createTemporary();assert.deepEqual(calls[0],{path:'/maps/public-workshops/temporary-shop',body:{queryId:'synthetic-query',candidateId:'synthetic-shop',name:'合成维修门店',address:'人工核实的合成地址',verified:true}})
 assert.ok(f.events.some(([name,value])=>name==='choose'&&value?.shopId===7))
 s.result.value.expiresAt=new Date(Date.now()-1000).toISOString();await s.createTemporary();assert.equal(calls.length,1)
 assert.equal(calls.some(c=>c.path.includes('/dispatch')),false)
})

test('owner invitation retains the required reason and submits verification only on explicit invitation',async t=>{
 const calls=[],f=setup('account/OwnerInvitation.vue',{caseId:'synthetic-owner-case',version:4},{currentUser:Vue.ref({roles:['CUSTOMER_SERVICE']}),httpClient:{async post(path,body){calls.push({path,body});return {data:{data:{id:'synthetic-invitation'}}}}}});t.after(f.stop)
 const s=f.context;await s.invite();assert.match(s.error.value,/填写说明/);assert.equal(calls.length,0)
 s.reason.value=' 已核实本案归属 ';s.existing.value='99';assert.equal(calls.length,0)
 await s.invite();assert.deepEqual(calls[0],{path:'/work-orders/synthetic-owner-case/owner-invitation',body:{expectedVersion:4,verified:true,reason:'已核实本案归属',existingOwnerId:null}})
 s.busy.value=true;await s.invite();assert.equal(calls.length,1)
})

test('shop coordinates require complete data and retain the explicit synthetic flag when confirmed by saving',async t=>{
 const calls=[],f=setup('materials/ShopMapSettings.vue',{shopId:7,address:'合成地址'},{httpClient:{async post(path,body){calls.push({path,body});return {data:{data:{}}}},async get(){return {data:{data:{version:2}}}}}});t.after(f.stop)
 const s=f.context;await s.savePosition();assert.match(s.error.value,/请选择区县/);assert.equal(calls.length,0)
 s.district.value='3';s.longitude.value='113.123';s.latitude.value='23.456';assert.equal(calls.length,0)
 await s.savePosition();assert.deepEqual(calls[0],{path:'/maps/admin/shops/7/location',body:{districtId:3,longitude:'113.123',latitude:'23.456',gcj02Confirmed:true,synthetic:false}})
 s.synthetic.value=true;await s.savePosition();assert.equal(calls[1].body.synthetic,true);assert.equal(s.position.value.version,2)
})

test('price import requires a preview and confirms the chosen batch on click without an extra check',async t=>{
 const calls=[],navigation=[],f=setup('pricing/ImportView.vue',{}, {mayConfirm,priceTypes:{},useRouter:()=>({async push(path){navigation.push(path)}}),httpClient:{async post(path,body){calls.push({path,body});return {data:{data:{id:8}}}}}});t.after(f.stop)
 const s=f.context;await s.confirm();assert.equal(calls.length,0)
 s.preview.value={id:'synthetic-preview',errorRows:1};assert.equal(calls.length,0)
 s.busy.value=true;await s.confirm();assert.equal(calls.length,0);s.busy.value=false
 await s.confirm();assert.deepEqual(calls,[{path:'/pricing/imports/previews/synthetic-preview/confirm',body:{confirmed:true}}]);assert.deepEqual(navigation,['/pricing/batches/8'])
 s.select({target:{files:[new File(['synthetic'],'next.csv')]}});await s.confirm();assert.equal(calls.length,1);assert.equal(s.preview.value,null)
})
