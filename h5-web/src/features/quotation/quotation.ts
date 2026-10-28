import type { AxiosInstance } from 'axios'
export interface RawQuote {id:string;version:number;total:string;lines:unknown[]}
export interface FormalQuote {id:string;version:number;total:string;markupValue:string;markupAmount:string;originalTotal:string;lines:unknown[]}
export interface QuoteCase {caseId:string;status:string;version:number;basisVersion:number;assignmentVersion?:number;arrivalPhotoUploaded:boolean;rawQuote:RawQuote|null;formalQuote?:FormalQuote|null;assessment?:{id:string;version:number;amount:string};authorized:boolean;gate:{allowed:boolean;missing:string[];lossAssessmentFileUploaded:boolean;finalAssessmentAmountRecorded:boolean;insurerConfirmationRecorded:boolean;customerServiceConfirmationRecorded:boolean}}
export function formalPayload(expectedVersion:number,rawQuoteId:string,mode:'FIXED_AMOUNT'|'PERCENTAGE',value:string){return mode==='FIXED_AMOUNT'?{expectedVersion,rawQuoteId,mode,fixedAmount:value}:{expectedVersion,rawQuoteId,mode,percentage:value}}
export function quotationApi(client:AxiosInstance,caseId:string){const base=`/quotations/cases/${caseId}`;return {async get(){
  const data=(await client.get<{data:Omit<QuoteCase,'arrivalPhotoUploaded'>&{arrivalPhotoUploaded?:boolean}}>(base)).data.data
  if(typeof data.arrivalPhotoUploaded==='boolean')return {...data,arrivalPhotoUploaded:data.arrivalPhotoUploaded}
  if(data.assignmentVersion!==undefined){
    for(let page=1;;page++){
      const files=(await client.get<{data:{records:{category:string;state:string;contentType:string}[];total:number}}>(`/materials/cases/${caseId}`,{params:{page,size:100}})).data.data
      if(files.records.some(file=>file.category==='ARRIVAL_PHOTO'&&file.state==='ACTIVE'&&['image/jpeg','image/png'].includes(file.contentType)))return {...data,arrivalPhotoUploaded:true}
      if(page*100>=files.total)break
    }
  }
  return {...data,arrivalPhotoUploaded:false}
},async post(operation:string,body:unknown,key:string=crypto.randomUUID()){return (await client.post<{data:QuoteCase}>(`${base}/${operation}`,body,{headers:{'Idempotency-Key':key}})).data.data},async history(type:string,page:number){return (await client.get<{data:{records:unknown[];total:number}}>(`${base}/history`,{params:{type,page,size:20}})).data.data},async export(quoteId:string){return (await client.get<Blob>(`${base}/formal-quotes/${quoteId}/export`,{responseType:'blob'})).data}}}
