import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'

function panel(client){
  const {descriptor}=parse(readFileSync(new URL('../src/features/materials/ArrivalPhotoPanel.vue',import.meta.url),'utf8'))
  let code=ts.transpile(compileScript(descriptor,{id:'arrival-photo-test'}).content,{target:ts.ScriptTarget.ESNext,module:ts.ModuleKind.ESNext})
  code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>'const {'+names.replace(/\s+as\s+/g,':')+'}=__vue;')
    .replace(/import\s*\{\s*httpClient\s*\}\s*from[^;]+;/,'const httpClient=__client;')
    .replace('export default','return')
  const component=new Function('__vue','__client',code)({...Vue,onUnmounted:()=>{}},client),events=[]
  return {state:component.setup({caseId:'case-test',assignmentVersion:3,uploaded:false},{expose:()=>{},emit:event=>events.push(event)}),events}
}
test('arrival photo upload keeps case and assignment scope; failed retry preserves bytes and idempotency key',async()=>{
  const calls=[],image=new File(['synthetic image'],'arrival.png',{type:'image/png'})
  const {state,events}=panel({post:async(...args)=>{calls.push(args);if(calls.length===1)throw Error('网络中断');return {data:{data:{id:'photo-test'}}}}})
  state.choose({target:{files:[image]}})
  await state.upload()
  assert.equal(state.selected.value,image);assert.equal(events.length,0)
  await state.upload()
  assert.equal(calls[0][0],'/materials/cases/case-test')
  assert.equal(calls[0][1].get('category'),'ARRIVAL_PHOTO');assert.equal(calls[0][1].get('assignmentVersion'),'3')
  assert.equal(calls[0][1].get('file'),image)
  assert.equal(calls[0][2].headers['Idempotency-Key'],calls[1][2].headers['Idempotency-Key'])
  assert.equal(state.selected.value,undefined);assert.deepEqual(events,['changed'])
})
test('invalid or oversized selection preserves the previously selected valid arrival photo',()=>{
  const {state}=panel({post:()=>{throw Error('must not upload')}}),image=new File(['synthetic'],'arrival.jpg',{type:'image/jpeg'})
  state.choose({target:{files:[image]}})
  state.choose({target:{files:[new File(['pdf'],'arrival.pdf',{type:'application/pdf'})],value:'pdf'}})
  assert.equal(state.selected.value,image);assert.match(state.message.value,/JPG.*PNG/)
  state.choose({target:{files:[{name:'large.png',type:'image/png',size:10485761}],value:'large'}})
  assert.equal(state.selected.value,image);assert.match(state.message.value,/10 MiB/)
  URL.revokeObjectURL(state.preview.value)
})
