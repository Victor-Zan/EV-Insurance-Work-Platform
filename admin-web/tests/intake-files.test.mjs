import test from 'node:test'
import assert from 'node:assert/strict'
import {readChatFiles,validateSceneFiles,intakeMissing} from '../src/features/workorders/intake.ts'
import {blankOcrDraft} from '../src/features/materials/ocr-form.ts'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'
test('folder and separate TXT imports retain the same text, photographs never pass through text decoding',async()=>{
 const text=new File(['案件号：20261015000000000001\n三者电话13826379142'],'聊天记录.txt',{type:'text/plain'})
 const photo=new File([new Uint8Array([255,216,255,0])],'物损.jpg',{type:'image/jpeg'})
 photo.arrayBuffer=()=>{throw Error('照片不应送文字提取')}
 const folder=await readChatFiles([text,photo]),separate=await readChatFiles([text])
 assert.equal(folder.text,separate.text);assert.deepEqual(folder.photos,[photo]);assert.equal(separate.photos.length,0)
})
test('two texts preserve source names and conflicts; unsupported files and over-limit photos are rejected',async()=>{
 const result=await readChatFiles([new File(['案件号：乙'],'b.txt'),new File(['案件号：甲'],'a.txt')]);assert.match(result.text,/a.txt.*\n案件号：甲[\s\S]*b.txt.*\n案件号：乙/)
 await assert.rejects(readChatFiles([new File(['x'],'a.txt'),new File(['%PDF'],'a.pdf')]),/仅支持TXT/)
 assert.throws(()=>validateSceneFiles(Array.from({length:21},()=>new File(['a'],'a.jpg'))),/最多20张/)
 await assert.rejects(readChatFiles([new File([new Uint8Array([255])],'bad.txt')]),TypeError)
})
test('formal submission requires contacts, one vehicle identifier and a photo, while colour, brand and model remain optional',()=>{
 const form=blankOcrDraft();assert.equal(intakeMissing(form,0).length,6)
 Object.assign(form,{claimNo:'SYNTHETIC',ownerPhone:'13826379142',surveyorName:'合成查勘',surveyorPhone:'13926018473',vehiclePlate:'粤B8Z71M'})
 assert.deepEqual(intakeMissing(form,1),[]);form.vehiclePlate='';form.vehicleVin='SYNTHETIC-VIN';assert.deepEqual(intakeMissing(form,1),[])
 form.surveyorPhone='';assert.deepEqual(intakeMissing(form,1),['查勘员电话'])
})
test('a partially failed batch retains successful photos and reuses the same upload key on retry',async()=>{
 const {descriptor}=parse(readFileSync(new URL('../src/features/workorders/ScenePhotoIntake.vue',import.meta.url),'utf8'))
 let code=ts.transpileModule(compileScript(descriptor,{id:'scene-test'}).content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>'const {'+names.replace(/\s+as\s+/g,':')+'}=__vue;').replace(/import\s*\{\s*httpClient\s*\}\s*from\s*['"][^'"]+['"];?/g,'const httpClient=__client;').replace(/import\s*\{\s*validateSceneFiles\s*\}\s*from\s*['"][^'"]+['"];?/g,'const validateSceneFiles=__validate;').replace('export default','return')
 const calls=[];let fail=true
 const client={async post(url,body,options){calls.push({url,name:body.get('file').name,category:body.get('category'),key:options.headers['Idempotency-Key']});if(body.get('file').name==='second.png'&&fail){fail=false;throw Error('模拟响应丢失')}return {data:{data:{id:'synthetic'}}}}}
 const component=new Function('__vue','__client','__validate',code)({...Vue,onUnmounted(){}},client,validateSceneFiles)
 const context=component.setup({modelValue:[new File(['a'],'first.jpg'),new File(['b'],'second.png')],locked:false},{expose(){},emit(){}})
 await assert.rejects(context.upload('synthetic-draft'),/响应丢失/);await context.upload('synthetic-draft')
 assert.deepEqual(calls.map(c=>c.name),['first.jpg','second.png','second.png']);assert.equal(calls[1].key,calls[2].key);assert.ok(calls.every(c=>c.category==='SCENE_PHOTO'&&!c.url.includes('/ocr')))
})
