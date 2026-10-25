import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import {compile} from '@vue/compiler-dom'
import ts from 'typescript'
import * as Vue from 'vue'
import {repairApi} from '../src/features/repair/repair.ts'

const read=name=>readFileSync(new URL('../src/'+name,import.meta.url),'utf8')
function setup(path,props,deps={}){
 const {descriptor}=parse(read(path)),hooks={},events=[]
 let code=ts.transpileModule(compileScript(descriptor,{id:'receipt-page-test'}).content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>'const {'+names.replace(/\s+as\s+/g,':')+'}=__vue;')
 code=code.replace(/import .*from ['"].*['"];?/g,'').replace('export default','return')
 code='const {httpClient,currentUser,repairApi,localTime,URL}=__deps;const ElImage="ElImage",ElDialog="ElDialog",CompletionEvidence="CompletionEvidence",PhotoPicker="PhotoPicker",RecordList="RecordList";const label=value=>value;'+code
 const dependencies={currentUser:Vue.ref({roles:['CUSTOMER_SERVICE']}),localTime:v=>v,URL,repairApi,...deps}
 const component=new Function('__vue','__deps',code)({...Vue,onMounted(fn){hooks.mounted=fn},onUnmounted(fn){hooks.unmounted=fn}},dependencies)
 const reactiveProps=Vue.reactive(props),context=component.setup(reactiveProps,{expose(){},emit(...e){events.push(e)}})
 return {context,props:reactiveProps,hooks,events,descriptor,user:dependencies.currentUser}
}
function render(template,context){
 const {code}=compile(template,{mode:'function',prefixIdentifiers:true,expressionPlugins:['typescript']})
 const fn=new Function('Vue',ts.transpileModule(code,{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText)(Vue)
 return fn(Vue.proxyRefs(context),[])
}
function elements(vnode){const nodes=[];function visit(v){if(!v||typeof v!=='object')return;if(Array.isArray(v)){v.forEach(visit);return}if(typeof v.type==='string')nodes.push(v);if(Array.isArray(v.children))v.children.forEach(visit);else if(v.children?.default)visit(v.children.default())}visit(vnode);return nodes}
const nodes=f=>elements(render(f.descriptor.template.content,{...f.props,...f.context}))
const flush=()=>new Promise(resolve=>setImmediate(resolve))
const completion={id:'completion-snapshot',assignmentVersion:1,createdAt:'2026-10-25T09:34:17Z',photos:[{id:'frozen-first',version:2,category:'COMPLETION_PHOTO',contentType:'image/png'},{id:'frozen-second',version:1,category:'COMPLETION_PHOTO',contentType:'image/jpeg'}]}

test('completion gallery loads only frozen snapshot IDs, switches photos and releases blobs on replacement/unmount',async()=>{
 const calls=[],created=[],revoked=[]
 const f=setup('features/repair/CompletionEvidence.vue',{completion:structuredClone(completion),shopName:'开发测试网点'},{httpClient:{async get(url,options){calls.push([url,options]);return {data:new Blob(['test image'],{type:'image/png'})}}},URL:{createObjectURL(){const value='blob:test-'+created.length;created.push(value);return value},revokeObjectURL(value){revoked.push(value)}}})
 await flush()
 assert.deepEqual(calls.map(c=>c[0]),['/materials/frozen-first/download','/materials/frozen-second/download'])
 assert.ok(calls.every(c=>c[1].responseType==='blob'))
 assert.equal(f.context.active.value.version,2)
 const thumbs=nodes(f).filter(n=>n.type==='button'&&n.props?.['aria-pressed']!==undefined)
 thumbs[1].props.onClick();assert.equal(f.context.active.value.id,'frozen-second');assert.equal(f.context.previewList.value[0],created[1])
 f.props.completion={...structuredClone(completion),photos:[completion.photos[0]]};await flush()
 assert.deepEqual(revoked.slice(0,2),created.slice(0,2));assert.equal(f.context.selected.value,0)
 f.hooks.unmounted();assert.equal(revoked.length,3)
})

test('gallery failure stays local, explicit retry succeeds, and stale/unmounted downloads cannot create URLs',async()=>{
 let resolve,fail=true;const created=[]
 const f=setup('features/repair/CompletionEvidence.vue',{completion:{...completion,photos:[completion.photos[0]]}},{httpClient:{async get(){if(fail)throw Error('照片读取失败');return new Promise(r=>{resolve=r})}},URL:{createObjectURL(){created.push('blob:retry');return 'blob:retry'},revokeObjectURL(){}}})
 await flush();assert.equal(f.context.active.value.loading,false);assert.equal(f.context.active.value.error,'照片读取失败')
 assert.ok(nodes(f).some(n=>n.props?.role==='alert'))
 fail=false;const retry=f.context.loadPhoto(0);resolve({data:new Blob(['retry'],{type:'image/png'})});await retry
 assert.equal(f.context.active.value.url,'blob:retry');assert.equal(f.context.active.value.error,'')
 const late=f.context.loadPhoto(0);f.hooks.unmounted();resolve({data:new Blob(['late'],{type:'image/png'})});await late;assert.equal(created.length,1)
})

test('receipt task exposes one explicit action with nearby retry feedback and retains the normal receipt API/idempotency',async()=>{
 const calls=[];let fail=true
 const state={caseId:'receipt-case',status:'WAITING_OWNER_CONFIRMATION',version:4,assignmentVersion:1,completion,receipt:null,review:null}
 const client={async get(url){calls.push(['get',url]);return {data:{data:url.endsWith('/progress')?{records:[],total:0}:structuredClone(state)}}},async post(url,body,options){calls.push(['post',url,body,options]);if(fail)throw Error('版本已变化，请刷新后重试');return {data:{data:{...state,status:'WAITING_INSURER_PAYMENT'}}}}}
 const f=setup('features/repair/RepairPanel.vue',{caseId:'receipt-case',receiptMode:true,shopName:'开发测试网点'},{httpClient:client})
 await f.context.run(f.context.load)
 assert.equal(calls.filter(c=>c[0]==='post').length,0)
 f.user.value.roles=['ADMIN'];assert.equal(f.context.canReceive.value,false)
 f.user.value.roles=['CUSTOMER_SERVICE']
 let page=nodes(f);assert.equal(page.filter(n=>n.type==='button'&&n.children==='确认已收车').length,1)
 assert.equal(page.find(n=>n.type==='details').props.open,false)
 page.find(n=>n.type==='button'&&n.children==='案件资料').props.onClick();assert.deepEqual(f.events.at(-1),['open-materials'])
 await f.context.run(f.context.receive)
 const footer=nodes(f).find(n=>n.type==='footer');assert.ok(elements(footer).some(n=>n.props?.role==='alert'&&n.children.includes('版本已变化')))
 assert.equal(f.context.pending.value.body,JSON.stringify({operation:'receipt',body:{expectedVersion:4}}))
 fail=false;await f.context.run(f.context.receive)
 const posts=calls.filter(c=>c[0]==='post');assert.equal(posts.length,2);assert.equal(posts[0][1],'/repairs/cases/receipt-case/receipt');assert.deepEqual(posts[0][2],{expectedVersion:4});assert.equal(posts[0][3].headers['Idempotency-Key'],posts[1][3].headers['Idempotency-Key'])
 assert.ok(f.events.some(e=>e[0]==='changed'))
})

test('receipt drawer hides upload fields until a permitted category is opened; frozen completion cannot be replaced',async()=>{
 const calls=[];const file={id:'frozen-first',category:'COMPLETION_PHOTO',name:'模拟完工.png',state:'ACTIVE',version:2,size:10,contentType:'image/png'}
 const client={async request({url}){calls.push(url);return {data:{data:url.endsWith('/notice-status')?{}:{records:[file],total:1}}}}}
 const f=setup('features/materials/MaterialsPanel.vue',{caseId:'receipt-case',status:'WAITING_OWNER_CONFIRMATION',allowedCategories:['SCENE_PHOTO','ARRIVAL_PHOTO'],compact:true},{httpClient:client})
 await f.context.run(f.context.load)
 let page=nodes(f);assert.equal(page.some(n=>n.type==='input'&&n.props.type==='file'),false)
 assert.equal(page.some(n=>n.type==='button'&&['替换','作废'].includes(n.children)),false)
 assert.ok(page.some(n=>n.type==='button'&&n.children==='查看'))
 page.find(n=>n.props?.['aria-expanded']===false).props.onClick()
 assert.equal(f.context.expandedCategory.value,'SCENE_PHOTO')
 assert.equal(nodes(f).filter(n=>n.type==='input'&&n.props.type==='file').length,1)
 assert.equal(f.context.categories.value.includes('COMPLETION_PHOTO'),false)
 assert.ok(calls.every(url=>url.startsWith('/materials/cases/receipt-case')))
})

test('current-task identity uses current case fields; receipt alone replaces inline uploads and keeps the drawer initially closed',()=>{
 const source=read('features/workorders/WorkOrderDetailView.vue'),template=parse(source).descriptor.template.content
 const aside=template.match(/<aside[^>]*class="control-panel[\s\S]*?<\/aside>/)[0]
 const order={status:'WAITING_OWNER_CONFIRMATION',ownerName:'测试车主',ownerPhone:'测试电话',vehiclePlate:'测试车牌',vehicleVin:'测试车架',vehicleBrand:'测试品牌',vehicleModel:'测试型号',vehicleColor:'测试颜色'}
 const context={receiptMode:true,order,workflow:{canAct:true,actorRole:'CUSTOMER_SERVICE',materials:[],missing:[]},taskTitle:'收车'}
 let page=elements(render(aside,context));assert.equal(page.filter(n=>n.type==='strong').length,4)
 assert.ok(page.some(n=>n.type==='strong'&&n.children==='测试车主'))
 order.ownerName='更正后的车主';page=elements(render(aside,context));assert.ok(page.some(n=>n.type==='strong'&&n.children==='更正后的车主'))
 const opening=template.match(/<section v-if="[^\n]+?data-reading="待补材料">/)[0]
 assert.notEqual(render(opening+'</section>',{receiptMode:true,activeSection:'repair',workflow:{materials:[{canUpload:true,category:'SCENE_PHOTO'}]}},[]).type,'section')
 assert.equal(render(opening+'</section>',{receiptMode:false,activeSection:'repair',workflow:{materials:[{canUpload:true,category:'SCENE_PHOTO'}]}},[]).type,'section')
 assert.match(source,/receiptMaterialsOpen=ref\(false\)/)
 assert.match(template,/<ElDrawer v-if="receiptMode" v-model="receiptMaterialsOpen"/)
})
