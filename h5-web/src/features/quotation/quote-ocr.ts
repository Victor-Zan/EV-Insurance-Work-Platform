import type { AxiosInstance } from 'axios'
import type { QuoteDraft } from '../../shared/ui/presentation.ts'
export interface OcrRef {jobId:string;revision?:number;itemIndex:number}
export interface QuoteOcrDraft extends QuoteDraft {ocr?:OcrRef;unit?:string;lineAmount?:string;quantityDefaulted?:boolean}
export interface Candidate {description:string;quantity:string;unitPrice:string;unit?:string;lineAmount?:string;calculatedAmount?:string;evidence?:unknown;origin?:string}
export interface Job {id:string;pageNo?:number;provider:string;state:string;errorCode?:string;reviewVersion:number;retryable:boolean;rerunsUsed?:number;candidate?:{items?:Candidate[];warnings?:string[];lines?:{text:string;location?:unknown;probability?:unknown}[];fields?:Record<string,string>;amountCandidate?:string;quoteRuleVersion?:string}}
export function historicalMock(jobProvider:string,currentProvider:string){return jobProvider==='MOCK_V1'&&currentProvider!==''&&currentProvider!=='MOCK_V1'}
export function taskSource(jobProvider:string,currentProvider:string){return jobProvider==='MOCK_V1'?(historicalMock(jobProvider,currentProvider)?'历史模拟结果 · 未调用百度':'模拟识别结果 · 非真实识别'):jobProvider==='BAIDU_HANDWRITING_V1'?'百度手写识别任务':`识别任务 · ${jobProvider}`}
export const ocrError:Record<string,string>={
  BAIDU_QUOTA:'百度额度：识别额度不足',
  BAIDU_LOCAL_BUDGET:'平台调用限制：本机识别次数已用完',
  BAIDU_AUTH:'百度鉴权：凭据、令牌或接口权限异常，请联系管理员',
  BAIDU_AUTH_TIMEOUT:'百度鉴权：获取令牌超时',
  BAIDU_AUTH_NETWORK:'百度鉴权：获取令牌时网络连接异常',
  BAIDU_RATE_LIMIT:'百度服务：请求频率超限',
  BAIDU_EMPTY_RESULT:'百度识别：未识别到文字，请核对原图',
  BAIDU_FORMAT:'百度图片校验：图片或请求格式不符合要求',
  BAIDU_IMAGE_SIZE:'平台图片校验：图片为空或超过10 MiB',
  BAIDU_IMAGE_FORMAT:'平台图片校验：仅支持JPEG/PNG图片',
  BAIDU_IMAGE_DIMENSIONS:'平台图片校验：图片尺寸须在15–4096像素范围内',
  BAIDU_IMAGE_DAMAGED:'平台图片校验：图片损坏或不完整',
  BAIDU_IMAGE_BLANK:'平台图片校验：图片内容为空白',
  BAIDU_ENCODED_SIZE:'平台图片校验：编码后图片超过接口大小限制',
  BAIDU_TIMEOUT_POSSIBLY_CHARGED:'百度请求：识别超时，可能已消耗额度',
  BAIDU_NETWORK_POSSIBLY_CHARGED:'百度请求：网络连接异常，可能已消耗额度',
  BAIDU_LEASE_POSSIBLY_CHARGED:'平台识别任务：处理中断，可能已消耗额度',
  BAIDU_HTTP:'百度服务：HTTP请求失败',
  BAIDU_SYSTEM:'百度服务：服务暂时异常',
  BAIDU_REJECTED:'百度服务：请求被拒绝',
  BAIDU_BAD_RESPONSE:'百度响应解析：返回内容无效',
  BAIDU_RESPONSE_SIZE:'百度响应读取：返回内容超过大小限制',
  BAIDU_RESULT_TOO_LARGE:'平台结果整理：识别内容超过处理上限',
  BAIDU_SIMULATION_FORBIDDEN:'平台配置：真实识别不允许模拟失败参数',
  SOURCE_ACCESS_CHANGED:'平台权限校验：案件、派单或材料已变化',
  SOURCE_HASH_MISMATCH:'平台原件校验：文件内容与上传记录不一致',
  PROVIDER_OR_STORAGE_FAILURE:'平台处理：识别适配或文件存储处理异常，具体原因待排查',
}
export function ocrFailureMessage(code?:string){return `识别失败，${ocrError[code??'']??'原因暂未确定，请联系管理员'}（${code||'UNKNOWN'}）。不会自动重试，可继续手填或手动重新识别。`}
export function isBlank(lines:QuoteOcrDraft[]){return lines.length===0||lines.every(line=>!line.description&&!line.quantity&&!line.unitPrice)}
export function sourceKey(ref:OcrRef){return `${ref.jobId}:${ref.itemIndex}`}
export function adopt(lines:QuoteOcrDraft[],candidate:Candidate,ref:OcrRef,target?:number):QuoteOcrDraft[]{
  if(lines.some((line,index)=>index!==target&&line.ocr&&sourceKey(line.ocr)===sourceKey(ref)))throw Error('此识别行已采用，请修改原行，勿重复计入')
  const existing=target===undefined?undefined:lines[target]
  if(target!==undefined&&!existing)throw Error('替换目标已变化，请重新选择')
  const recognizedQuantity=candidate.quantity?.trim()
  const quantity=recognizedQuantity||existing?.quantity
  const row:QuoteOcrDraft={description:candidate.description??'',quantity:quantity||'1',unitPrice:candidate.unitPrice??'',unit:candidate.unit??'',lineAmount:candidate.lineAmount??'',ocr:{...ref}}
  if(!recognizedQuantity&&(!quantity||existing?.quantityDefaulted))row.quantityDefaulted=true
  if(target!==undefined)return lines.map((line,index)=>index===target?row:line)
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
  async reextract(id:string,expectedVersion:number,key:string){return (await client.post<{data:Job}>(`${base}/${id}/reextract`,{expectedVersion},{headers:{'Idempotency-Key':key}})).data.data},
  async apply(id:string,expectedVersion:number,items:unknown[],key:string){return (await client.post<{data:Job}>(`${base}/${id}/apply`,{expectedVersion,items},{headers:{'Idempotency-Key':key}})).data.data}
}}
export function decimalProduct(quantity:string,price:string){
  if(!/^\d+$/.test(quantity)||!/^\d+(?:\.\d{1,2})?$/.test(price))return ''
  const [whole='',fraction='']=price.split('.');const cents=BigInt(quantity)*(BigInt(whole)*100n+BigInt(fraction.padEnd(2,'0')))
  return `${cents/100n}.${String(cents%100n).padStart(2,'0')}`
}
export function candidateTotal(items:Candidate[]){let sum=0n;for(const item of items){const amount=decimalProduct(item.quantity,item.unitPrice);if(!amount)return '';sum+=BigInt(amount.replace('.',''))}return `${sum/100n}.${String(sum%100n).padStart(2,'0')}`}
