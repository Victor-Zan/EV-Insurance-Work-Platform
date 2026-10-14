import test from 'node:test'
import assert from 'node:assert/strict'
import { prefillOcrDraft,ocrDraftPayload,blankOcrDraft,evidenceText } from '../src/features/materials/ocr-form.ts'
test('only existing draft fields prefill; sheet number/date/items never become claim/accident values',()=>{
  const form=prefillOcrDraft({ownerName:'合成客户',documentNo:'REPAIR-TEST',documentDate:'2026-10-13',accidentRegionText:'模拟区域',accidentRegionId:'3',quantity:'1'})
  assert.equal(form.ownerName,'合成客户');assert.equal(form.claimNo,'');assert.equal(form.accidentAt,'');assert.equal(form.accidentRegionId,'');assert.equal(Object.hasOwn(form,'quantity'),false)
})
test('Chinese datetime prefill roundtrips without depending on browser timezone',()=>{
  const form=prefillOcrDraft({accidentAt:'2026-10-13T01:30:00Z'})
  assert.equal(form.accidentAt,'2026-10-13T09:30:00');assert.equal(ocrDraftPayload(form).accidentAt,'2026-10-13T01:30:00.000Z')
  assert.equal(ocrDraftPayload(blankOcrDraft()).accidentAt,null)
  assert.equal(prefillOcrDraft(ocrDraftPayload(form)).accidentAt,form.accidentAt)
  assert.equal(prefillOcrDraft({accidentAt:'not-a-date'}).accidentAt,'')
})
test('human correction stays in the reviewed draft payload',()=>{
  const form=prefillOcrDraft({ownerName:'合成甲',ownerPhone:'13800000000'});form.ownerName='合成乙';form.accidentRegionId='3'
  assert.equal(ocrDraftPayload(form).ownerName,'合成乙');assert.equal(ocrDraftPayload(form).accidentRegionId,3)
})
test('evidence is readable, preserves original l and only labels actual provider confidence',()=>{
  const source=[{line:0,text:'l',location:{left:10,top:20,width:30,height:40},probability:{average:0.72},labelEvidence:{text:'数量'}}]
  const before=structuredClone(source),text=evidenceText(source)
  assert.match(text,/原文“l”/);assert.match(text,/left=10/);assert.match(text,/0.72/);assert.match(text,/不代表字段映射置信度/);assert.deepEqual(source,before)
  assert.match(evidenceText([{line:1,text:'合成文字'}]),/未返回置信度/);assert.equal(evidenceText(undefined),'未识别 / 待人工填写')
})
