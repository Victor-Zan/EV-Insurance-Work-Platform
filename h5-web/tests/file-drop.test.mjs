import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync,readdirSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'
import * as transport from '../src/shared/upload/file-drop.ts'

class Transfer {
  types=['Files'];files=[]
  items=Object.assign([], {add:file=>{this.files.push(file)}})
}
class Input extends EventTarget {type='file';accept='.png,.jpg';multiple=false;disabled=false;files=[];value='';clicks=0;click(){this.clicks++}}
function event(transfer=new Transfer()) {return {dataTransfer:transfer,prevented:false,stopped:false,preventDefault(){this.prevented=true},stopPropagation(){this.stopped=true}}}
const image=name=>new File(['synthetic image'],name,{type:'image/png'})
function zone(extra={}) {
  const {descriptor}=parse(readFileSync(new URL('../src/shared/upload/FileDropZone.vue',import.meta.url),'utf8'))
  let code=ts.transpileModule(compileScript(descriptor,{id:'drop-test'}).content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText
  code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>'const {'+names.replace(/\s+as\s+/g,':')+'}=__vue;')
  code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"][^'"]*file-drop['"];?/g,(_,names)=>'const {'+names+'}=__transport;').replace('export default','return')
  const hooks=[],ends=[],props=Vue.reactive({hint:'合成测试',...extra})
  const component=new Function('__vue','__transport',code)({...Vue,onMounted:fn=>hooks.push(fn),onUnmounted:fn=>ends.push(fn)},transport)
  const context=component.setup(props,{expose(){}}),input=new Input(),received=[]
  context.root.value={querySelector:()=>input}
  input.addEventListener('change',e=>context.change(e))
  input.addEventListener('change',()=>received.push([...input.files]))
  return {context,input,received,props,hooks,ends}
}

test('selection validation lists bad files and rejects the whole single/mixed/empty/oversized batch',()=>{
  const input=new Input()
  assert.match(transport.selectionError([image('a.png'),image('b.jpg')],input),/一个/)
  input.multiple=true
  const bad=[image('ok.png'),new File([],'empty.png'),new File(['x'],'wrong.zip'),{name:'big.png',size:10485761,type:'image/png'}]
  const error=transport.selectionError(bad,input,10485760)
  for(const name of ['empty.png','wrong.zip','big.png'])assert.ok(error.includes(name))
  assert.equal(transport.selectionError([image('valid.PNG')],input,10485760),'')
})

test('directory rejection never traverses; unknown browser asks for original picker',()=>{
  const transfer=new Transfer();let traversed=false
  transfer.items.push({kind:'file',webkitGetAsEntry:()=>({isDirectory:true,createReader(){traversed=true}}),getAsFile:()=>null})
  assert.throws(()=>transport.droppedFiles(transfer),/文件夹/);assert.equal(traversed,false)
  transfer.items=[{kind:'file',getAsFile:()=>image('a.png')}]
  assert.throws(()=>transport.droppedFiles(transfer),/浏览器无法安全确认/)
})

test('click and drop deliver identical files once to the existing native change handler',()=>{
  const previous=globalThis.DataTransfer,doc=globalThis.document
  globalThis.DataTransfer=Transfer;globalThis.document=new EventTarget()
  try {
    const file=image('same.png'),clicked=zone(),dropped=zone()
    clicked.input.files=[file];clicked.input.dispatchEvent(new Event('change'))
    const transfer=new Transfer();transfer.files=[file]
    const e=event(transfer);dropped.context.drop(e)
    assert.deepEqual(clicked.received,dropped.received);assert.equal(dropped.received.length,1)
    assert.equal(e.prevented,true);assert.equal(e.stopped,true)
    assert.equal(dropped.context.selected.value[0],file)
    // Repeated user choices still use the original flow, never a second upload service.
    dropped.context.drop(event(transfer));assert.equal(dropped.received.length,2)
  } finally {globalThis.DataTransfer=previous;globalThis.document=doc}
})

test('nested drag remains highlighted, leaving/reset removes highlight, text drag is untouched',()=>{
  const {context}=zone(),e=event()
  context.enter(e);context.enter(e);context.leave(e);assert.equal(context.active.value,true)
  context.leave(e);assert.equal(context.active.value,false)
  const text=event({types:['text/plain']});context.enter(text);context.over(text);context.drop(text)
  assert.equal(text.prevented,false);assert.equal(text.stopped,false)
  context.enter(e);context.reset();assert.equal(context.active.value,false)
})

test('busy/locked/native-disabled prevent both change and drop; latest props are checked',async()=>{
  const doc=globalThis.document;globalThis.document=new EventTarget()
  try {
    const {context,input,received,props}=zone()
    context.enter(event());props.busy=true;await Vue.nextTick();assert.equal(context.active.value,false)
    input.files=[image('a.png')];input.dispatchEvent(new Event('change'))
    context.drop(event());assert.equal(received.length,0);assert.match(context.localError.value,/不可操作/)
    props.busy=false;props.disabled=true;input.dispatchEvent(new Event('change'));assert.equal(received.length,0)
    props.disabled=false;input.disabled=true;input.dispatchEvent(new Event('change'));assert.equal(received.length,0)
    input.disabled=false;input.dispatchEvent(new Event('change'));assert.equal(received.length,1)
  } finally {globalThis.document=doc}
})

test('invalid replacement preserves prior selection and original handler/form; valid retry succeeds',()=>{
  const {context,input,received}=zone({maxBytes:10485760}),first=image('first.png')
  input.files=[first];input.dispatchEvent(new Event('change'))
  input.files=[new File(['bad'],'bad.exe')];input.dispatchEvent(new Event('change'))
  assert.equal(received.length,1);assert.deepEqual(context.selected.value,[first]);assert.match(context.localError.value,/bad.exe/)
  input.files=[image('retry.png')];input.dispatchEvent(new Event('change'))
  assert.equal(received.length,2);assert.equal(context.localError.value,'')
})

test('global guard prevents file navigation outside zones and does not consume normal text',()=>{
  const listeners={},target={addEventListener:(name,fn)=>listeners[name]=fn,dispatchEvent:e=>{assert.equal(e.type,'file-drop-reset')}}
  transport.preventFileNavigation(target)
  for(const name of ['dragover','drop']){
    const file=event();listeners[name](file);assert.equal(file.prevented,true)
    const text=event({types:['text/plain']});listeners[name](text);assert.equal(text.prevented,false)
  }
})

test('folder picker keeps relative paths while folder drops give explicit fallback',()=>{
  const doc=globalThis.document;globalThis.document=new EventTarget()
  try {
    const {context,input,received}=zone({folder:true});input.multiple=true;input.accept=''
    const file=new File(['内容'],'chat.txt');Object.defineProperty(file,'webkitRelativePath',{value:'目录/子目录/chat.txt'})
    input.files=[file];input.dispatchEvent(new Event('change'));assert.equal(received[0][0].webkitRelativePath,'目录/子目录/chat.txt')
    context.drop(event());assert.equal(received.length,1);assert.match(context.localError.value,/原“选择文件夹”/)
  } finally {globalThis.document=doc}
})

test('Escape, end, blur and outside drop reset highlighting, and lifecycle listeners are removed',()=>{
  const doc=globalThis.document,win=globalThis.window
  globalThis.document=new EventTarget();globalThis.window=new EventTarget()
  try{
    const {context,hooks,ends}=zone();hooks.forEach(fn=>fn())
    for(const type of ['dragend','blur','keydown','file-drop-reset']){
      context.enter(event());assert.equal(context.active.value,true)
      const e=new Event(type);if(type==='keydown')Object.defineProperty(e,'key',{value:'Escape'})
      ;(type==='file-drop-reset'?globalThis.document:globalThis.window).dispatchEvent(e)
      assert.equal(context.active.value,false)
    }
    ends.forEach(fn=>fn());context.enter(event());globalThis.window.dispatchEvent(new Event('blur'));assert.equal(context.active.value,true)
  }finally{globalThis.document=doc;globalThis.window=win}
})

test('all registered native upload inputs remain wrapped, transport parity and no external/paid service code',()=>{
  const source=readFileSync(new URL('../src/shared/upload/FileDropZone.vue',import.meta.url),'utf8')
  assert.match(source,/<button type="button"[^>]*:disabled="disabled"/)
  for(const name of ['FileDropZone.vue','file-drop.ts']){
    const admin=readFileSync(new URL('../../admin-web/src/shared/upload/'+name,import.meta.url),'utf8')
    assert.equal(admin,readFileSync(new URL('../../h5-web/src/shared/upload/'+name,import.meta.url),'utf8'))
    assert.doesNotMatch(admin,/httpClient|fetch\(|axios|baidubce|amap\.com|FormData/)
  }
  assert.match(source,/@change.capture="change"/)
  for(const app of ['admin-web','h5-web']){
    const base=new URL('../../'+app+'/src/',import.meta.url)
    for(const file of readdirSync(base,{recursive:true}).filter(path=>path.endsWith('.vue'))){
      const code=readFileSync(new URL(file.replaceAll('\\','/'),base),'utf8')
      if(file.endsWith('FundsImportPanel.vue')&&app==='h5-web')continue // Not reachable by H5 roles; do not add an entrance.
      if(code.includes('type="file"'))assert.equal((code.match(/type="file"/g)??[]).length,(code.match(/<FileDropZone\b/g)??[]).length,app+'/'+file)
    }
  }
})
