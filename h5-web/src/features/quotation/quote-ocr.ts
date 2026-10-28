import type { AxiosInstance } from 'axios'
import type { QuoteDraft } from '../../shared/ui/presentation.ts'
export interface OcrRef {jobId:string;revision?:number;itemIndex:number}
export interface QuoteOcrDraft extends QuoteDraft {ocr?:OcrRef;unit?:string;lineAmount?:string}
export interface Candidate {description:string;quantity:string;unitPrice:string;unit?:string;lineAmount?:string;calculatedAmount?:string;evidence?:unknown;origin?:string}
export interface Job {id:string;pageNo?:number;provider:string;state:string;errorCode?:string;reviewVersion:number;retryable:boolean;rerunsUsed?:number;candidate?:{items?:Candidate[];warnings?:string[];lines?:{text:string;location?:unknown;probability?:unknown}[];fields?:Record<string,string>;amountCandidate?:string}}
export function historicalMock(jobProvider:string,currentProvider:string){return jobProvider==='MOCK_V1'&&currentProvider!==''&&currentProvider!=='MOCK_V1'}
export function taskSource(jobProvider:string,currentProvider:string){return jobProvider==='MOCK_V1'?(historicalMock(jobProvider,currentProvider)?'历史模拟结果 · 未调用百度':'模拟识别结果 · 非真实识别'):jobProvider==='BAIDU_HANDWRITING_V1'?'百度手写识别任务':`识别任务 · ${jobProvider}`}
export const ocrError:Record<string,string>={BAIDU_QUOTA:'百度额度不足，可保留表单继续手填',BAIDU_LOCAL_BUDGET:'本机识别授权次数已用完，不代表百度账户额度耗尽',BAIDU_AUTH:'百度凭据或权限异常，请联系管理员',BAIDU_RATE_LIMIT:'百度请求限流，请稍后明确重试',BAIDU_EMPTY_RESULT:'未识别到文字，请核对原图或手填',BAIDU_FORMAT:'图片不符合识别要求',BAIDU_ENCODED_SIZE:'编码后图片超过百度限制，请使用完整且更小的原件',BAIDU_TIMEOUT_POSSIBLY_CHARGED:'识别超时，可能已经消耗额度；不会自动重新识别',BAIDU_NETWORK_POSSIBLY_CHARGED:'识别网络异常，可能已经消耗额度',BAIDU_LEASE_POSSIBLY_CHARGED:'任务中断，可能已消耗额度；不会自动重新识别',SOURCE_ACCESS_CHANGED:'案件、派单或材料已变化，不能采用旧结果',SOURCE_HASH_MISMATCH:'原件校验失败，不能识别',BAIDU_SYSTEM:'百度服务异常，可保留表单继续手填'}
export function isBlank(lines:QuoteOcrDraft[]){return lines.length===0||lines.every(line=>!line.description&&!line.quantity&&!line.unitPrice)}
export function sourceKey(ref:OcrRef){return `${ref.jobId}:${ref.itemIndex}`}
export function adopt(lines:QuoteOcrDraft[],candidate:Candidate,ref:OcrRef,target?:number):QuoteOcrDraft[]{
  if(lines.some((line,index)=>index!==target&&line.ocr&&sourceKey(line.ocr)===sourceKey(ref)))throw Error('此识别行已采用，请修改原行，勿重复计入')
  const row:QuoteOcrDraft={description:candidate.description??'',quantity:candidate.quantity??'',unitPrice:candidate.unitPrice??'',unit:candidate.unit??'',lineAmount:candidate.lineAmount??'',ocr:{...ref}}
  if(target!==undefined){if(!lines[target])throw Error('替换目标已变化，请重新选择');return lines.map((line,index)=>index===target?row:line)}
  return isBlank(lines)?[row]:[...lines,row]
}
export function quoteOcrApi(client:AxiosInstance,caseId:string){const base=`/quotations/cases/${caseId}/ocr`;return {
  async configuration(){return (await client.get<{data:{provider:string}}>(`${base}/configuration`)).data.data},
  async upload(file:File,assignmentVersion:number,pageNo:number,category:string,key:string){const body=new FormData();body.append('file',file);body.append('assignmentVersion',String(assignmentVersion));body.append('pageNo',String(pageNo));body.append('category',category);return (await client.post<{data:Job}>(base,body,{headers:{'Idempotency-Key':key}})).data.data},
  async list(assignmentVersion:number){return (await client.get<{data:{records:Job[]}}>(base,{params:{assignmentVersion,page:1,size:20}})).data.data.records},
  async get(id:string){return (await client.get<{data:Job}>(`${base}/${id}`)).data.data},
  async program(id:string){return (await client.get<{data:NonNullable<Job['candidate']>}>(`${base}/${id}/program`)).data.data},
  async image(id:string){return (await client.get<Blob>(`${base}/${id}/image`,{responseType:'blob'})).data},
  async raw(id:string){return (await client.get<{data:{text:string}}>(`${base}/${id}/raw`)).data.data.text},
  async retry(id:string,expectedVersion:number,key:string){return (await client.post<{data:Job}>(`${base}/${id}/retry`,{expectedVersion},{headers:{'Idempotency-Key':key}})).data.data},
  async apply(id:string,expectedVersion:number,items:unknown[],key:string){return (await client.post<{data:Job}>(`${base}/${id}/apply`,{expectedVersion,items},{headers:{'Idempotency-Key':key}})).data.data}
}}
export function decimalProduct(quantity:string,price:string){
  if(!/^\d+$/.test(quantity)||!/^\d+(?:\.\d{1,2})?$/.test(price))return ''
  const [whole='',fraction='']=price.split('.');const cents=BigInt(quantity)*(BigInt(whole)*100n+BigInt(fraction.padEnd(2,'0')))
  return `${cents/100n}.${String(cents%100n).padStart(2,'0')}`
}
export function candidateTotal(items:Candidate[]){let sum=0n;for(const item of items){const amount=decimalProduct(item.quantity,item.unitPrice);if(!amount)return '';sum+=BigInt(amount.replace('.',''))}return `${sum/100n}.${String(sum%100n).padStart(2,'0')}`}
