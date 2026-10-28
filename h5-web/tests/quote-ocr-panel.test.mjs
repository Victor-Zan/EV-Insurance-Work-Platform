import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'
import * as transport from '../src/features/quotation/quote-ocr.ts'

function panel(client,lines=[]){
  const {descriptor}=parse(readFileSync(new URL('../src/features/quotation/QuoteOcrPanel.vue',import.meta.url),'utf8'))
  let code=ts.transpile(compileScript(descriptor,{id:'ocr-panel-test'}).content,{target:ts.ScriptTarget.ESNext,module:ts.ModuleKind.ESNext})
  code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>'const {'+names.replace(/\s+as\s+/g,':')+'}=__vue;')
    .replace(/import\s*\{\s*httpClient\s*\}\s*from[^;]+;/,'const httpClient=__client;')
    .replace(/import\s*\{([^}]+)\}\s*from\s*['"][^'"]*quote-ocr['"];?/g,(_,names)=>'const {'+names+'}=__transport;')
    .replace('export default','return')
  const hooks=[],ends=[],props=Vue.reactive({caseId:'case-test',assignmentVersion:2,locked:false,lines})
  const component=new Function('__vue','__client','__transport',code)({...Vue,onMounted:fn=>hooks.push(fn),onUnmounted:fn=>ends.push(fn)},client,transport)
  const state=component.setup(props,{expose:()=>{},emit:(_,value)=>{props.lines=value}})
  return {state,props,mount:async()=>{for(const fn of hooks)await fn()},close:()=>ends.forEach(fn=>fn())}
}
const candidate={description:'合成轮胎',quantity:'2',unitPrice:'12.50'}
const job=(extra={})=>({id:'job-test',provider:'BAIDU_HANDWRITING_V1',state:'SUCCEEDED',reviewVersion:0,rerunsUsed:0,...extra})
const data=value=>({data:{data:value}})
function reads(url){
  if(url.endsWith('/configuration'))return data({provider:'BAIDU_HANDWRITING_V1'})
  if(url.endsWith('/program'))return data({items:[candidate]})
  if(url.endsWith('/image'))return {data:new Blob(['synthetic'])}
  return data(job({reviewVersion:1,rerunsUsed:1}))
}
test('selecting a file uploads once and automatically fills a blank form; duplicate upload never adds rows',async()=>{
  const calls=[],ui=panel({get:async url=>reads(url),post:async(...args)=>{calls.push(args);return data(job())}})
  const file=new File(['synthetic'],'list.png',{type:'image/png'})
  try{
    await ui.state.choose({target:{files:[file]}})
    assert.equal(calls.length,1);assert.equal(calls[0][1].get('file'),file)
    assert.equal(calls[0][1].get('assignmentVersion'),'2')
    assert.equal(ui.props.lines.length,1);assert.equal(ui.props.lines[0].description,candidate.description)
    assert.equal(ui.state.selected.value,undefined)
    await ui.state.choose({target:{files:[file]}})
    assert.equal(ui.props.lines.length,1);assert.equal(ui.state.pages.value.length,1)
    assert.doesNotMatch(ui.state.message.value,/幂等|相同原件|重复识别/)
  }finally{ui.close()}
})
test('re-recognition reuses the original and request key after a lost response, preserves typed rows, and enforces the returned cap',async()=>{
  const calls=[],ui=panel({get:async url=>reads(url),post:async(url,body,config)=>{
    calls.push({url,body,config})
    if(url.endsWith('/retry')){
      if(calls.filter(call=>call.url.endsWith('/retry')).length===1)throw Error('网络中断')
      return data(job({state:'QUEUED',reviewVersion:1,rerunsUsed:1}))
    }
    return data(job())
  }})
  try{
    await ui.state.choose({target:{files:[new File(['synthetic'],'list.png',{type:'image/png'})]}})
    ui.props.lines[0].description='人工修改保留'
    const page=ui.state.pages.value[0]
    await ui.state.retry(page)
    assert.equal(ui.props.lines[0].ocr.jobId,'job-test')
    await ui.state.retry(page)
    const retries=calls.filter(call=>call.url.endsWith('/retry'))
    assert.equal(retries.length,2);assert.deepEqual(retries[0].body,{expectedVersion:0})
    assert.equal(retries[0].config.headers['Idempotency-Key'],retries[1].config.headers['Idempotency-Key'])
    assert.equal(ui.props.lines[0].description,'人工修改保留');assert.equal(ui.props.lines[0].ocr,undefined)
    await ui.state.refresh()
    assert.equal(page.items[0].description,candidate.description);assert.equal(ui.props.lines.length,1)
    page.job=job({reviewVersion:3,rerunsUsed:3})
    await ui.state.retry(page)
    assert.equal(calls.filter(call=>call.url.endsWith('/retry')).length,2)
  }finally{ui.close()}
})
test('a rejected stale/capped request reloads the server count rather than leaving an endless retry action',async()=>{
  let count=0
  const ui=panel({get:async url=>url.endsWith('/job-test')?data(job({rerunsUsed:3})):reads(url),post:async url=>{
    if(url.endsWith('/retry')){count++;throw Object.assign(Error('已达上限'),{status:409})}
    return data(job())
  }})
  try{
    await ui.state.choose({target:{files:[new File(['synthetic'],'list.png',{type:'image/png'})]}})
    const page=ui.state.pages.value[0];await ui.state.retry(page);await ui.state.retry(page)
    assert.equal(count,1);assert.equal(page.rerun,undefined);assert.equal(page.job.rerunsUsed,3)
  }finally{ui.close()}
})

test('refresh restores original image jobs and persistent retry counts using only scoped reads',async()=>{
  const readsOnly=[],ui=panel({get:async(url,config)=>{
    readsOnly.push({url,config})
    return url.endsWith('/ocr')?data({records:[job({rerunsUsed:2})]}):reads(url)
  },post:async()=>{throw Error('restoration must never recognize')}})
  try{
    await ui.mount()
    assert.equal(ui.state.pages.value.length,1);assert.equal(ui.state.pages.value[0].job.rerunsUsed,2)
    const list=readsOnly.find(call=>call.url.endsWith('/ocr'))
    assert.deepEqual(list.config.params,{assignmentVersion:2,page:1,size:20})
    assert.equal(ui.state.uploading.value,false);assert.equal(ui.props.lines.length,1)
  }finally{ui.close()}
})
