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
  status: WorkOrderStatus; shopId?: number; currentAssignment?: Assignment; workflowNode?: string
}
export interface History { id: number; fromStatus?: string; toStatus: string; action: string; reason?: string; occurredAt: string }
export const statusLabels: Record<WorkOrderStatus,string> = {
  DRAFT:'草稿',PENDING_DISPATCH:'待派单',PENDING_ACCEPTANCE:'待接单',PENDING_ARRIVAL:'待到店',ARRIVAL_EXCEPTION:'到店异常',ARRIVED:'已到店',
  WAITING_QUOTE:'待报价',QUOTE_REVIEWING:'报价审核中',WAITING_INSURER_ASSESSMENT:'等待保险核损',WAITING_REPAIR_AUTHORIZATION:'待维修授权',
  REPAIRING:'维修中',WAITING_OWNER_CONFIRMATION:'待车主确认收车',WAITING_INSURER_PAYMENT:'待保险回款',
  WAITING_SHOP_SETTLEMENT:'待网点结算',COMPLETED:'已完成',CANCELLED:'已取消',CLOSED:'已关闭',
}
export function actionFor(status:WorkOrderStatus,role:'REPAIR_SHOP'|'OWNER'):{label:string;section:string} {
  if(role==='OWNER') {
    if(status==='WAITING_OWNER_CONFIRMATION')return {label:'确认收车',section:'receipt'}
    if(status==='WAITING_INSURER_PAYMENT'||status==='WAITING_SHOP_SETTLEMENT')return {label:'已收车，等待客服完成资金确认',section:'receipt'}
    if(status==='COMPLETED')return {label:'查看完工与评价',section:'receipt'}
    if(status==='REPAIRING')return {label:'查看维修进度',section:'progress'}
    return {label:'查看案件进度',section:'overview'}
  }
  const actions:Partial<Record<WorkOrderStatus,{label:string;section:string}>>={
    PENDING_ACCEPTANCE:{label:'处理接单',section:'overview'},PENDING_ARRIVAL:{label:'确认车辆到店',section:'overview'},ARRIVAL_EXCEPTION:{label:'处理到店异常',section:'overview'},
    ARRIVED:{label:'提交原始报价',section:'quotation'},WAITING_QUOTE:{label:'提交原始报价',section:'quotation'},QUOTE_REVIEWING:{label:'查看报价状态',section:'quotation'},WAITING_INSURER_ASSESSMENT:{label:'查看授权条件',section:'quotation'},WAITING_REPAIR_AUTHORIZATION:{label:'查看授权条件',section:'quotation'},
    REPAIRING:{label:'更新维修进度',section:'repair'},WAITING_OWNER_CONFIRMATION:{label:'等待车主收车',section:'repair'},WAITING_INSURER_PAYMENT:{label:'等待客服确认到账',section:'settlement'},WAITING_SHOP_SETTLEMENT:{label:'查看结算',section:'settlement'},COMPLETED:{label:'查看案件记录',section:'overview'},
  }
  return actions[status]??{label:'查看案件详情',section:'overview'}
}
const key=(operation:string)=>`${operation}-${crypto.randomUUID()}`
export function workOrderApi(client:AxiosInstance){
  const data=async<T>(request:Promise<{data:ApiResponse<T>}>)=>(await request).data.data
  return {
    list:(page=1,size=20,query='')=>data<Page<WorkOrder>>(client.get('/work-orders',{params:{page,size,...(query.trim()?{query:query.trim()}:{})}})),
    detail:(id:string)=>data<WorkOrder>(client.get(`/work-orders/${id}`)),
    history:(id:string,page=1)=>data<Page<History>>(client.get(`/work-orders/${id}/history`,{params:{page,size:20}})),
    accept:(id:string,assignmentVersion:number)=>data<WorkOrder>(client.post(`/work-orders/${id}/accept`,{assignmentVersion},{headers:{'Idempotency-Key':key('accept')}})),
    reject:(id:string,assignmentVersion:number,reason:string)=>data<WorkOrder>(client.post(`/work-orders/${id}/reject`,{assignmentVersion,reason},{headers:{'Idempotency-Key':key('reject')}})),
    exception:(id:string,assignmentVersion:number,reason:string)=>data<WorkOrder>(client.post(`/work-orders/${id}/arrival-exceptions`,{assignmentVersion,reason},{headers:{'Idempotency-Key':key('arrival-exception')}})),
    continueWaiting:(id:string,assignmentVersion:number,reason:string|null)=>data<WorkOrder>(client.post(`/work-orders/${id}/continue-waiting`,{assignmentVersion,reason},{headers:{'Idempotency-Key':key('continue-waiting')}})),
    arrive:(id:string,assignmentVersion:number)=>data<WorkOrder>(client.post(`/work-orders/${id}/arrive`,{assignmentVersion},{headers:{'Idempotency-Key':key('arrive')}})),
  }
}
