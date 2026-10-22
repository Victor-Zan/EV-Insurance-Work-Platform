import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'
import {saveIntake,openSavedCase,createManualDraft} from '../src/features/workorders/intake-save.ts'
import {intakeMissing,validateSceneFiles} from '../src/features/workorders/intake.ts'
import {ApiError} from '../src/shared/http/create-client.ts'

function view(){
 const {descriptor}=parse(readFileSync(new URL('../src/features/workorders/WorkOrdersView.vue',import.meta.url),'utf8'))
 let code=ts.transpileModule(compileScript(descriptor,{id:'manual-view'}).content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]([^'"]+)['"];?/g,(_,names,path)=>'const {'+names.replace(/\s+as\s+/g,':')+'}='+ (path==='vue'?'__vue':'__deps')+';')
 code=code.replace(/import\s+(\w+)\s+from\s*['"][^'"]+['"];?/g,(_,name)=>'const '+name+'={};').replace('export default','return')
 const calls=[],messages=[],draft={id:'synthetic-only',status:'DRAFT'},formal={...draft,status:'PENDING_DISPATCH'}
 const api={async create(form,key){calls.push({op:'create',form:{...form},key});return draft},async update(id,form){calls.push({op:'update',id,form:{...form}});return draft},async detail(){calls.push({op:'detail'});return draft},async submit(id,confirmed,reason,key){calls.push({op:'submit',id,key});return formal},async list(){calls.push({op:'list'});return {records:[],total:0}}}
 const router={async push(path){calls.push({op:'navigate',path})},resolve:path=>({href:path})}
 const deps={workOrderApi:()=>api,useRouter:()=>router,useRoute:()=>({query:{create:'1'}}),currentUser:Vue.ref({roles:['CUSTOMER_SERVICE']}),httpClient:{},saveIntake,openSavedCase,createManualDraft,intakeMissing,validateSceneFiles,ApiError,nodeNames:{},statusLabels:{},vMagnetic:{},ElMessage:{success:message=>messages.push(message),warning:message=>messages.push(message),error:message=>messages.push(message)},ElMessageBox:{async prompt(){throw Error('unexpected duplicate dialog')}}}
 for(const name of ['ElButton','ElDialog','ElInput','ElOption','ElSelect','ElTag'])deps[name]={}
 const component=new Function('__vue','__deps',code)({...Vue,onMounted(){}},deps)
 const context=component.setup({}, {expose(){}})
 Object.assign(context.form,{claimNo:'SYNTH-MANUAL',ownerPhone:'13800000001',vehiclePlate:'粤B8Z71M',surveyorName:'合成查勘',surveyorPhone:'13900000001'})
 context.sceneFiles.value=[new File(['synthetic'],'scene.png')]
 context.ocrPanel.value={async createReviewedDraft(){return undefined}}
 context.photoPanel.value={async upload(id){calls.push({op:'upload',id})}}
 return {context,api,calls,messages,router,draft,formal}
}

test('actual creation view saves an unused-intake manual draft and separately submits a complete manual case',async()=>{
 for(const formal of [false,true]){
  const f=view();await f.context.create(formal)
  assert.equal(f.calls.filter(c=>c.op==='create').length,1)
  assert.equal(f.calls.filter(c=>c.op==='submit').length,formal?1:0)
  assert.equal(f.calls.find(c=>c.op==='navigate').path,'/work-orders/synthetic-only')
  assert.equal(f.calls.find(c=>c.op==='create').form.claimNo,'SYNTH-MANUAL')
  assert.equal(f.context.creationError.value,'')
 }
})

test('required fields/photos are still enforced and an imported conflict cannot be bypassed',async()=>{
 const f=view();f.context.form.surveyorPhone='';f.context.sceneFiles.value=[]
 await f.context.create(true)
 assert.match(f.context.creationError.value,/查勘员电话/);assert.match(f.context.creationError.value,/照片/);assert.equal(f.calls.length,0)
 f.context.ocrBlocked.value=true;await f.context.create(false)
 assert.match(f.context.creationError.value,/核对/);assert.equal(f.calls.length,0)
})

test('rapid clicks cannot duplicate creation while the first save is pending',async()=>{
 const f=view();let release
 f.api.create=async(form,key)=>{f.calls.push({op:'create',form,key});return new Promise(resolve=>{release=resolve})}
 const first=f.context.create(true);await Promise.resolve();await Promise.resolve()
 await f.context.create(true);assert.equal(f.calls.filter(c=>c.op==='create').length,1)
 release(f.draft);await first;assert.equal(f.calls.filter(c=>c.op==='submit').length,1)
})

test('view keeps a lost manual-create request and recovers its ID before applying later edits',async()=>{
 const f=view();let first=true
 f.api.create=async(form,key)=>{f.calls.push({op:'create',form:{...form},key});if(first){first=false;throw Error('响应丢失')}return f.draft}
 await f.context.create(true);assert.equal(f.context.form.claimNo,'SYNTH-MANUAL')
 f.context.form.claimNo='SYNTH-CORRECTED';await f.context.create(true)
 const creates=f.calls.filter(c=>c.op==='create');assert.equal(creates.length,2);assert.equal(creates[0].key,creates[1].key);assert.deepEqual(creates[0].form,creates[1].form)
 assert.equal(f.calls.find(c=>c.op==='update').form.claimNo,'SYNTH-CORRECTED')
 assert.equal(f.calls.filter(c=>c.op==='submit').length,1)
})

test('failed upload keeps form, files and draft; retry does not create another case',async()=>{
 const f=view();let fail=true
 f.context.photoPanel.value={async upload(){if(fail){fail=false;throw new ApiError('存储暂不可用','STORAGE_UNAVAILABLE',503)}}}
 await f.context.create(true);assert.equal(f.context.pendingDraft.value.id,f.draft.id)
 assert.equal(f.context.sceneFiles.value.length,1);assert.equal(f.context.form.claimNo,'SYNTH-MANUAL')
 assert.match(f.context.creationError.value,/STORAGE_UNAVAILABLE/)
 await f.context.create(true);assert.equal(f.calls.filter(c=>c.op==='create').length,1);assert.equal(f.calls.filter(c=>c.op==='submit').length,1)
})

test('navigation failure after commit never becomes a creation failure',async()=>{
 const f=view(),win=globalThis.window,locations=[]
 globalThis.window={location:{assign:path=>locations.push(path)}}
 try{f.router.push=async()=>{throw Error('chunk load failed')};await f.context.create(true)
  assert.deepEqual(locations,['/work-orders/synthetic-only']);assert.equal(f.context.creationError.value,'')
  assert.ok(f.messages.includes('案件已正式建单'))
 }finally{globalThis.window=win}
})
