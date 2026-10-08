import type { AxiosInstance } from 'axios'
import type { ApiResponse, Page } from '@/shared/http/create-client'

export type WorkOrderStatus =
  | 'DRAFT' | 'PENDING_DISPATCH' | 'PENDING_ACCEPTANCE' | 'PENDING_ARRIVAL' | 'ARRIVAL_EXCEPTION' | 'ARRIVED'
  | 'WAITING_QUOTE' | 'QUOTE_REVIEWING' | 'WAITING_INSURER_ASSESSMENT' | 'WAITING_REPAIR_AUTHORIZATION'
  | 'REPAIRING' | 'WAITING_OWNER_CONFIRMATION' | 'WAITING_INSURER_PAYMENT' | 'WAITING_SHOP_SETTLEMENT'
  | 'COMPLETED' | 'CANCELLED' | 'CLOSED'
export interface Assignment { id: number; assignmentVersion: number; shopId: number; status: string }
export interface WorkOrder {
  id: string; businessNo?: string; insuranceCompany?: string; claimNo?: string; ownerName?: string; ownerPhone?: string
  vehicleBrand?: string; vehicleModel?: string; accidentAt?: string; accidentAddress?: string; accidentDescription?: string
  status: WorkOrderStatus; shopId?: number; currentAssignment?: Assignment
}
export interface History { id: number; toStatus: string; action: string; reason?: string; occurredAt: string }
export const statusLabels: Record<WorkOrderStatus,string> = {
  DRAFT:'草稿',PENDING_DISPATCH:'待派单',PENDING_ACCEPTANCE:'待接单',PENDING_ARRIVAL:'待到店',ARRIVAL_EXCEPTION:'到店异常',ARRIVED:'已到店',
  WAITING_QUOTE:'待报价',QUOTE_REVIEWING:'报价审核中',WAITING_INSURER_ASSESSMENT:'等待保险核损',WAITING_REPAIR_AUTHORIZATION:'待维修授权',
  REPAIRING:'维修中',WAITING_OWNER_CONFIRMATION:'待车主确认收车',WAITING_INSURER_PAYMENT:'待保险回款',
  WAITING_SHOP_SETTLEMENT:'待网点结算',COMPLETED:'已完成',CANCELLED:'已取消',CLOSED:'已关闭',
}
const key=(operation:string)=>`${operation}-${crypto.randomUUID()}`
export function workOrderApi(client:AxiosInstance){
  const data=async<T>(request:Promise<{data:ApiResponse<T>}>)=>(await request).data.data
  return {
    list:()=>data<Page<WorkOrder>>(client.get('/work-orders',{params:{size:50}})),
    detail:(id:string)=>data<WorkOrder>(client.get(`/work-orders/${id}`)),
    history:(id:string)=>data<Page<History>>(client.get(`/work-orders/${id}/history`,{params:{size:50}})),
    accept:(id:string,assignmentVersion:number)=>data<WorkOrder>(client.post(`/work-orders/${id}/accept`,{assignmentVersion},{headers:{'Idempotency-Key':key('accept')}})),
    reject:(id:string,assignmentVersion:number,reason:string)=>data<WorkOrder>(client.post(`/work-orders/${id}/reject`,{assignmentVersion,reason},{headers:{'Idempotency-Key':key('reject')}})),
    exception:(id:string,assignmentVersion:number,reason:string)=>data<WorkOrder>(client.post(`/work-orders/${id}/arrival-exceptions`,{assignmentVersion,reason},{headers:{'Idempotency-Key':key('arrival-exception')}})),
    continueWaiting:(id:string,assignmentVersion:number,reason:string|null)=>data<WorkOrder>(client.post(`/work-orders/${id}/continue-waiting`,{assignmentVersion,reason},{headers:{'Idempotency-Key':key('continue-waiting')}})),
    arrive:(id:string,assignmentVersion:number)=>data<WorkOrder>(client.post(`/work-orders/${id}/arrive`,{assignmentVersion},{headers:{'Idempotency-Key':key('arrive')}})),
  }
}
