import type { AxiosInstance } from 'axios'
import type { Page, ApiResponse } from '@/shared/http/create-client'

export type WorkOrderStatus =
  | 'DRAFT' | 'PENDING_DISPATCH' | 'PENDING_ACCEPTANCE' | 'PENDING_ARRIVAL' | 'ARRIVAL_EXCEPTION' | 'ARRIVED'
  | 'WAITING_QUOTE' | 'QUOTE_REVIEWING' | 'WAITING_INSURER_ASSESSMENT' | 'WAITING_REPAIR_AUTHORIZATION'
  | 'REPAIRING' | 'WAITING_OWNER_CONFIRMATION' | 'WAITING_INSURER_PAYMENT' | 'WAITING_SHOP_SETTLEMENT'
  | 'COMPLETED' | 'CANCELLED' | 'CLOSED'

export interface Assignment {
  id: number; assignmentVersion: number; shopId: number; status: string; reason?: string; transferDescription?: string
}
export interface WorkOrder {
  id: string
  businessNo?: string
  insuranceCompany?: string
  claimNo?: string
  claimReportedAt?: string
  dataSource: string
  currentResponsibleId: number
  ownerBindingStatus: 'PENDING' | 'BOUND'
  ownerName?: string
  ownerPhone?: string
  policyNo?: string
  vehicleBrand?: string
  vehicleModel?: string
  vehicleVin?: string
  vehiclePlate?: string
  vehicleOtherIdentifier?: string
  accidentAt?: string
  accidentRegionId?: number
  accidentAddress?: string
  accidentDescription?: string
  shopId?: number
  status: WorkOrderStatus
  version: number
  createdBy: number
  currentAssignment?: Assignment
}
export interface DraftForm {
  insuranceCompany: string; claimNo: string; claimReportedAt: string
  ownerName: string; ownerPhone: string; policyNo: string; vehicleBrand: string; vehicleModel: string
  vehicleVin: string; vehiclePlate: string; vehicleOtherIdentifier: string
  accidentAt: string; accidentRegionId: string; accidentAddress: string; accidentDescription: string
}
export interface CriticalForm {
  insuranceCompany: string; claimNo: string; vehicleVin: string; vehiclePlate: string; vehicleOtherIdentifier: string; reason: string
}
export interface ShopOption { id: number; code: string; name: string; address?: string }
export interface RegionOption { id: number; code: string; name: string; level: number }
export interface History { id: number; fromStatus?: string; toStatus: string; action: string; reason?: string; occurredAt: string }
export interface DuplicateConfiguration { possibleDuplicateDays: number }

export const statusLabels: Record<WorkOrderStatus, string> = {
  DRAFT: '草稿', PENDING_DISPATCH: '待派单', PENDING_ACCEPTANCE: '待接单', PENDING_ARRIVAL: '待到店',
  ARRIVAL_EXCEPTION: '到店异常', ARRIVED: '已到店', WAITING_QUOTE: '待报价', QUOTE_REVIEWING: '报价审核中',
  WAITING_INSURER_ASSESSMENT: '等待保险核损', WAITING_REPAIR_AUTHORIZATION: '待维修授权', REPAIRING: '维修中',
  WAITING_OWNER_CONFIRMATION: '待车主确认收车', WAITING_INSURER_PAYMENT: '待保险回款',
  WAITING_SHOP_SETTLEMENT: '待网点结算', COMPLETED: '已完成', CANCELLED: '已取消', CLOSED: '已关闭',
}
export function idempotencyKey(operation: string) { return `${operation}-${crypto.randomUUID()}` }
export function draftPayload(form: DraftForm) {
  const instant = (value: string) => value ? new Date(value).toISOString() : null
  const optional = (value: string) => value.trim() || null
  return {
    ...form,
    claimReportedAt: instant(form.claimReportedAt),
    accidentAt: instant(form.accidentAt),
    accidentRegionId: form.accidentRegionId ? Number(form.accidentRegionId) : null,
    policyNo: optional(form.policyNo),
    vehicleVin: optional(form.vehicleVin),
    vehiclePlate: optional(form.vehiclePlate),
    vehicleOtherIdentifier: optional(form.vehicleOtherIdentifier),
    accidentAddress: optional(form.accidentAddress),
  }
}
export function workOrderApi(client: AxiosInstance) {
  const data = async <T>(request: Promise<{ data: ApiResponse<T> }>) => (await request).data.data
  return {
    list: (page=1,status='',query='') => data<Page<WorkOrder>>(client.get('/work-orders',{ params:{ page,size:20,status:status||undefined,query:query||undefined } })),
    regions: () => data<Page<RegionOption>>(client.get('/pricing/regions',{ params:{ size:100 } })),
    create: (form: DraftForm) => data<WorkOrder>(client.post('/work-orders/drafts',draftPayload(form))),
    update: (id: string,form: DraftForm) => data<WorkOrder>(client.put(`/work-orders/${id}/draft`,draftPayload(form))),
    deleteDraft: (id: string) => data<void>(client.delete(`/work-orders/${id}/draft`)),
    detail: (id: string) => data<WorkOrder>(client.get(`/work-orders/${id}`)),
    submit: (id: string,confirmPossibleDuplicate=false,duplicateReason: string|null=null,key=idempotencyKey('submit')) =>
      data<WorkOrder>(client.post(`/work-orders/${id}/submit`,{ confirmPossibleDuplicate,duplicateReason },{ headers:{ 'Idempotency-Key':key } })),
    critical: (id: string,value: CriticalForm) => data<WorkOrder>(client.put(`/work-orders/${id}/critical-fields`,value)),
    shops: (id: string) => data<Page<ShopOption>>(client.get(`/work-orders/${id}/eligible-shops`,{ params:{ size:100 } })),
    dispatch: (id: string,shopId: number,confirmPossibleDuplicate=false,duplicateReason: string|null=null) =>
      data<WorkOrder>(client.post(`/work-orders/${id}/dispatch`,{ shopId,confirmPossibleDuplicate,duplicateReason },{ headers:{ 'Idempotency-Key':idempotencyKey('dispatch') } })),
    reassign: (id: string,value: { shopId:number; assignmentVersion:number; reason:string; vehicleNotArrivedConfirmed:boolean; transferDescription:string|null; confirmPossibleDuplicate:boolean }) =>
      data<WorkOrder>(client.post(`/work-orders/${id}/reassign`,value,{ headers:{ 'Idempotency-Key':idempotencyKey('reassign') } })),
    cancelAssignment: (id: string,assignmentVersion: number,reason: string) =>
      data<WorkOrder>(client.post(`/work-orders/${id}/cancel-assignment`,{ assignmentVersion,reason },{ headers:{ 'Idempotency-Key':idempotencyKey('cancel-assignment') } })),
    arrivalException: (id:string,assignmentVersion:number,reason:string) =>
      data<WorkOrder>(client.post(`/work-orders/${id}/arrival-exceptions`,{assignmentVersion,reason},{headers:{'Idempotency-Key':idempotencyKey('arrival-exception')}})),
    continueWaiting: (id:string,assignmentVersion:number,reason:string|null) =>
      data<WorkOrder>(client.post(`/work-orders/${id}/continue-waiting`,{assignmentVersion,reason},{headers:{'Idempotency-Key':idempotencyKey('continue-waiting')}})),
    cancelWorkOrder: (id:string,value:{reason:string;description:string;notifyOwner:boolean;notifyShop:boolean}) =>
      data<WorkOrder>(client.post(`/work-orders/${id}/cancel`,value,{headers:{'Idempotency-Key':idempotencyKey('cancel-work-order')}})),
    history: (id: string) => data<Page<History>>(client.get(`/work-orders/${id}/history`,{ params:{ size:100 } })),
    configuration: () => data<DuplicateConfiguration>(client.get('/work-orders/configuration')),
    updateConfiguration: (possibleDuplicateDays:number,reason:string) => data<DuplicateConfiguration>(
      client.put('/work-orders/configuration',{possibleDuplicateDays,reason})),
  }
}
