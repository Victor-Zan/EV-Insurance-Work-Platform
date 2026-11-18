import test from 'node:test'
import assert from 'node:assert/strict'
import {adopt,isBlank,quoteOcrApi,decimalProduct,candidateTotal,historicalMock,taskSource,ocrFailureMessage} from '../src/features/quotation/quote-ocr.ts'
import {quotePayload} from '../src/shared/ui/presentation.ts'
const candidate={description:'合成项目',quantity:'',unitPrice:'',unit:'只',lineAmount:'25.00'},source={jobId:'job-1',itemIndex:0}
test('failure messages distinguish provider, platform and unknown causes without promising automatic retries',()=>{
  assert.match(ocrFailureMessage('BAIDU_QUOTA'),/百度额度.*BAIDU_QUOTA.*不会自动重试/)
  assert.match(ocrFailureMessage('BAIDU_LOCAL_BUDGET'),/平台调用限制/)
  assert.match(ocrFailureMessage('BAIDU_BAD_RESPONSE'),/百度响应解析/)
  assert.match(ocrFailureMessage('SOURCE_HASH_MISMATCH'),/平台原件校验/)
  assert.match(ocrFailureMessage('NEW_ERROR'),/原因暂未确定.*NEW_ERROR/)
  assert.match(ocrFailureMessage(),/原因暂未确定.*UNKNOWN/)
})
test('adoption defaults missing quantity to one without changing the source or filling missing price',()=>{
  const form=[{description:'',quantity:'',unitPrice:''}],next=adopt(form,candidate,source)
  assert.equal(next[0].quantity,'1')
  assert.equal(next[0].quantityDefaulted,true)
  assert.equal(next[0].unitPrice,'')
  assert.deepEqual(next[0].ocr,source)
  assert.equal(candidate.quantity,'')
  assert.equal(isBlank(form),true)
  assert.throws(()=>quotePayload(next))
  const priced=adopt([],{...candidate,unitPrice:'12.50'},source)
  assert.equal(candidateTotal(priced),'12.50')
  assert.equal(quotePayload(priced)[0].quantity,1)
  const recognized=adopt([],{...candidate,quantity:'3',unitPrice:'12.50'},source)
  assert.equal(recognized[0].quantity,'3')
  assert.equal(recognized[0].quantityDefaulted,undefined)
})
test('waiting-time manual entries remain intact; a missing OCR quantity preserves the value when replacing',()=>{const latest=[{description:'等待期间手填',quantity:'3',unitPrice:'8.00'}];assert.equal(isBlank(latest),false);const appended=adopt(latest,candidate,source);assert.equal(appended[0].description,'等待期间手填');assert.equal(latest.length,1);const replaced=adopt(latest,candidate,source,0);assert.equal(replaced.length,1);assert.equal(replaced[0].description,'合成项目');assert.equal(replaced[0].quantity,'3');assert.equal(replaced[0].quantityDefaulted,undefined);assert.throws(()=>adopt(latest,candidate,source,3))})
test('same source cannot be adopted twice; same name on another page is not merged',()=>{const first=adopt([],candidate,source);assert.throws(()=>adopt(first,candidate,source),/已采用/);const two=adopt(first,candidate,{jobId:'job-2',itemIndex:0});assert.equal(two.length,2);assert.equal(two[1].ocr.jobId,'job-2')})
test('decimal calculation uses exact cents and never l as 1; amount evidence is untouched',()=>{assert.equal(decimalProduct('l','8.00'),'');assert.equal(decimalProduct('','8.00'),'');assert.equal(decimalProduct('2','9007199254740993.01'),'18014398509481986.02');assert.equal(candidate.lineAmount,'25.00')})
test('candidate totals require complete numbers and never sum source image totals',()=>{assert.equal(candidateTotal([candidate]),'');assert.equal(candidateTotal([{description:'工时',quantity:'2',unitPrice:'12.50',lineAmount:'99.00'},{description:'配件',quantity:'1',unitPrice:'8.00'}]),'33.00')})
test('uploads and confirmations use case scoped existing API client and explicit idempotency keys',async()=>{const calls=[],client={post:async(url,body,config)=>{calls.push({url,body,config});return {data:{data:{id:'job-1'}}}}},api=quoteOcrApi(client,'case-1');await api.upload(new File(['synthetic'],'synthetic.png',{type:'image/png'}),2,3,'ASSESSMENT','stable-upload');assert.equal(calls[0].url,'/quotations/cases/case-1/ocr');assert.equal(calls[0].body.get('assignmentVersion'),'2');assert.equal(calls[0].body.get('pageNo'),'3');assert.equal(calls[0].body.get('category'),'ASSESSMENT');assert.equal(calls[0].config.headers['Idempotency-Key'],'stable-upload');await api.apply('job-1',4,[{index:0}], 'stable-apply');assert.deepEqual(calls[1].body,{expectedVersion:4,items:[{index:0}]});assert.equal(calls[1].config.headers['Idempotency-Key'],'stable-apply')})

test('actual task source remains explicit after configuration switches; historical Mock must not prefill as Baidu',()=>{assert.equal(historicalMock('MOCK_V1','BAIDU_HANDWRITING_V1'),true);assert.equal(historicalMock('BAIDU_HANDWRITING_V1','BAIDU_HANDWRITING_V1'),false);assert.equal(historicalMock('MOCK_V1','MOCK_V1'),false);assert.match(taskSource('MOCK_V1','BAIDU_HANDWRITING_V1'),/历史模拟/);assert.match(taskSource('MOCK_V1','MOCK_V1'),/非真实识别/);assert.equal(taskSource('BAIDU_HANDWRITING_V1','BAIDU_HANDWRITING_V1'),'百度手写识别任务')})
