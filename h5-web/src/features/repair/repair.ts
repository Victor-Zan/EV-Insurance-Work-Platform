import type {AxiosInstance} from 'axios'
export interface ReviewEntry {id:string;source:string;createdAt:string;text:string|null;score:number|null;reason?:string}
export interface RepairPhoto {id:string;version:number;category:string;contentType:string}
export interface RepairEntry {id:string;assignmentVersion:number;createdAt:string;note?:string;photos:RepairPhoto[]}
export interface RepairCase {caseId:string;status:string;version:number;assignmentVersion?:number;completion:RepairEntry|null;receipt:{id:number;confirmedBy:'OWNER'|'CUSTOMER_SERVICE';createdAt:string;withdrawn:boolean}|null;review:{eligibleForCurrentRating:boolean;original:ReviewEntry;current:ReviewEntry}|null}
export function repairApi(client:AxiosInstance,caseId:string){
  const base=`/repairs/cases/${caseId}`
  return {
    async get(){return (await client.get<{data:RepairCase}>(base)).data.data},
    async history(page:number){return (await client.get<{data:{records:RepairEntry[];total:number}}>(`${base}/progress`,{params:{page,size:20}})).data.data},
    async post(operation:'progress'|'completion'|'receipt'|'receipt/withdraw'|'review'|'review/corrections',body:unknown,key:string=crypto.randomUUID()){return (await client.post<{data:RepairCase}>(`${base}/${operation}`,body,{headers:{'Idempotency-Key':key}})).data.data},
  }
}
