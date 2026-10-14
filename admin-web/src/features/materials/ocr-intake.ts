import type { AxiosInstance } from 'axios'
import type { DraftForm, WorkOrder } from '../workorders/work-orders'
import { ocrDraftPayload } from './ocr-form.ts'

export interface OcrCandidate {
  fields?:Record<string,string>; evidence?:Record<string,unknown>; warnings?:string[]
  lines?:{line:number;text:string;location?:Record<string,number>}[]
  items?:{description?:string;quantity?:string;unitPrice?:string;lineAmount?:string;evidence?:unknown}[]
  [key:string]:unknown
}
export interface IntakeJob {
  id:string;provider:string;providerApi:string;state:string;reviewVersion:number
  confirmed:boolean;intakeId?:string;candidate?:OcrCandidate;errorCode?:string
}
export function ocrIntakeApi(client:AxiosInstance){
  const data=async<T>(request:Promise<{data:{data:T}}>)=>(await request).data.data
  // Retain a review command across a lost response so a click retry does not append duplicates.
  let command:{jobId:string;signature:string;key:string;version:number;candidate:OcrCandidate}|undefined
  return {
    upload(file:File,key:string){const body=new FormData();body.append('file',file);return data<IntakeJob>(client.post('/ocr/intake',body,{headers:{'Content-Type':undefined,'Idempotency-Key':key}}))},
    job:(id:string)=>data<IntakeJob>(client.get(`/ocr/${id}`)),
    program:(id:string)=>data<OcrCandidate>(client.get(`/ocr/${id}/program`)),
    image:(id:string)=>client.get<Blob>(`/ocr/${id}/image`,{responseType:'blob'}).then(response=>response.data),
    async createReviewedDraft(job:IntakeJob,form:DraftForm,decisions:Record<string,string>={}){
      if(job.state!=='SUCCEEDED'||!job.intakeId)throw new Error('请等待建单前识别成功，或解除关联后手工建单')
      const draft=ocrDraftPayload(form),signature=JSON.stringify({draft,decisions})
      if(command?.jobId!==job.id||command.signature!==signature){
        command={jobId:job.id,signature,key:crypto.randomUUID(),version:job.reviewVersion,candidate:{...job.candidate,origin:'HUMAN_REVISION',draft,formMergeDecisions:decisions}}
      }
      const reviewed=await data<IntakeJob>(client.post(`/ocr/${job.id}/review`,{expectedVersion:command.version,candidate:command.candidate},{headers:{'Idempotency-Key':command.key}}))
      const confirmed=await data<IntakeJob>(client.post(`/ocr/${job.id}/confirm`,{expectedVersion:reviewed.reviewVersion}))
      return data<WorkOrder>(client.post(`/ocr/${job.id}/draft`,{expectedVersion:confirmed.reviewVersion,draft}))
    },
  }
}
