import { ref } from 'vue'
import { createCommand, request, RequestError } from './client'
import { currentSession, isRecord, onSessionCleared } from './session'

export type Page<T> = { page: number; size: number; total: number; records: T[] }
export type OwnerCase = { id: string; businessNo: string; claimNo: string; vehicle: string; plate: string; insuranceCompany: string;
  accidentAddress: string; accidentDescription: string; status: string; workflowNode: string }
export type Photo = { id: string; version: number; category: string; contentType: string; createdAt: string }
export type Progress = { id: string; note: string; createdAt: string; photos: Photo[] }
export type ReviewEntry = { id: string; text: string; score: number | null; createdAt: string }
export type Repair = { caseId: string; status: string; version: number; completion: Progress | null;
  receipt: { confirmedBy: string; createdAt: string; withdrawn: boolean } | null;
  review: { original: ReviewEntry; current: ReviewEntry; eligible: boolean } | null }
export type Notice = { id: number; caseId: string; title: string; createdAt: string; read: boolean; reason: string; section: 'progress' | 'receipt' | 'complaint' }
export type Complaint = { id: string; caseId: string; description: string; status: string; createdAt: string; photos: Photo[] }
export type ComplaintEvent = { id: number; note: string; createdAt: string }
export const ownerRevision = ref(0)
onSessionCleared(() => { ownerRevision.value = 0 })
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const photoCategories = ['ARRIVAL_PHOTO', 'PROGRESS_PHOTO', 'COMPLETION_PHOTO']
const received = ['WAITING_INSURER_PAYMENT', 'WAITING_SHOP_SETTLEMENT', 'COMPLETED']
const complaintStates = ['REPAIRING', 'WAITING_OWNER_CONFIRMATION', ...received]
const statusLabels: Record<string, string> = { PENDING_DISPATCH: '待派单', PENDING_ACCEPTANCE: '待接单', PENDING_ARRIVAL: '待到店',
  ARRIVAL_EXCEPTION: '到店异常', ARRIVED: '已到店', WAITING_QUOTE: '待报价', QUOTE_REVIEWING: '报价审核中',
  WAITING_INSURER_ASSESSMENT: '等待保险核损', WAITING_REPAIR_AUTHORIZATION: '待维修授权', REPAIRING: '维修中',
  WAITING_OWNER_CONFIRMATION: '待确认收车', WAITING_INSURER_PAYMENT: '已收车', WAITING_SHOP_SETTLEMENT: '已收车',
  COMPLETED: '已收车', CANCELLED: '已取消', CLOSED: '已关闭' }
const noticeTitles: Record<string, string> = { ARRIVED: '车辆已到店', OWNER_RECEIPT: '维修完工待确认收车', OWNER_RECEIVED: '已确认收车',
  RECEIPT_WITHDRAWN: '收车确认已撤回', COMPLAINT_NEW: '服务反馈已提交', COMPLAINT_UPDATED: '服务反馈处理记录已更新', CASE_CANCELLED: '案件已取消' }
export const workflow = [ ['CREATE', '建单'], ['DISPATCH_ARRIVAL', '派单到店'], ['SHOP_QUOTE', '网点报价'], ['QUOTE_REVIEW', '报价审核'],
  ['INSURER_ASSESSMENT', '保险核损'], ['REPAIR', '维修中'], ['RECEIPT', '收车'], ['COMPLETE', '完成'] ]
export const ownerStatus = (status: string) => statusLabels[status] ?? '进度待核对'
const complaintLabels: Record<string, string> = { SUBMITTED: '已提交', PROCESSING: '处理中', RESOLVED: '已处理', CLOSED: '已关闭' }
const photoLabels: Record<string, string> = { ARRIVAL_PHOTO: '到店照片', PROGRESS_PHOTO: '维修进度照片', COMPLETION_PHOTO: '完工照片' }
export const complaintStatus = (status: string) => complaintLabels[status] ?? '处理中'
export const photoLabel = (category: string) => photoLabels[category] ?? '维修照片'
export function actionLabel(row: OwnerCase) {
  if (row.status === 'WAITING_OWNER_CONFIRMATION') return '确认收车'
  if (received.indexOf(row.status) >= 0) return '查看完工与评价'
  return row.status === 'REPAIRING' ? '查看维修进度' : '查看案件进度'
}
export function workflowIndex(row: OwnerCase) { return workflow.findIndex(node => node[0] === (row.workflowNode === 'FUNDS' ? 'COMPLETE' : row.workflowNode)) }
export function displayTime(value: string) {
  if (!value) return '时间待核对'
  const ms = Date.parse(value)
  if (!Number.isFinite(ms)) return '时间待核对'
  if (!/(?:Z|[+-]\d\d:\d\d)$/i.test(value)) return value.replace('T', ' ').slice(0, 16)
  return new Date(ms + 8 * 3600000).toISOString().slice(0, 16).replace('T', ' ')
}
function record(value: unknown): Record<string, unknown> {
  if (!isRecord(value)) throw new RequestError('INVALID_RESPONSE', '案件信息暂无法确认，请刷新后重试。')
  return value
}
const text = (value: unknown) => typeof value === 'string' ? value : ''
function integer(value: unknown, minimum = 0) {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < minimum) throw new RequestError('INVALID_RESPONSE', '服务返回的数据需要核实，请重试。')
  return value
}
export function resourceId(value: unknown) {
  if (typeof value !== 'string' || !uuid.test(value)) throw new RequestError('VALIDATION_ERROR', '案件或材料标识无效。')
  return value
}
export function requireOwner() {
  if (currentSession()?.user.role !== 'OWNER') throw new RequestError('FORBIDDEN', '此页面仅供已登录的车主使用。', 403)
}
function parseCase(value: unknown): OwnerCase {
  const row = record(value)
  if (row.status === 'DRAFT') throw new RequestError('FORBIDDEN', '此案件暂不可查看。', 403)
  return { id: resourceId(row.id), businessNo: text(row.businessNo), claimNo: text(row.claimNo), vehicle: [text(row.vehicleBrand), text(row.vehicleModel)].filter(Boolean).join(' '),
    plate: text(row.vehiclePlate), insuranceCompany: text(row.insuranceCompany), accidentAddress: text(row.accidentAddress), accidentDescription: text(row.accidentDescription),
    status: text(row.status), workflowNode: text(row.workflowNode) }
}
function parsePhoto(value: unknown): Photo | null {
  const row = record(value)
  if (photoCategories.indexOf(text(row.category)) < 0 || ['image/jpeg', 'image/png'].indexOf(text(row.contentType)) < 0
    || (row.state != null && row.state !== 'ACTIVE')) return null
  return { id: resourceId(row.id), version: integer(row.version, 1), category: text(row.category), contentType: text(row.contentType), createdAt: text(row.createdAt) }
}
function photoList(value: unknown) { return Array.isArray(value) ? value.map(parsePhoto).filter((item): item is Photo => item !== null) : [] }
function progress(value: unknown): Progress {
  const row = record(value)
  return { id: resourceId(row.id), note: text(row.note), createdAt: text(row.createdAt), photos: photoList(row.photos) }
}
function reviewEntry(value: unknown): ReviewEntry {
  const row = record(value)
  const score = row.score == null ? null : integer(row.score, 1)
  if (score !== null && score > 5) throw new RequestError('INVALID_RESPONSE', '评分数据异常，请刷新。')
  const id = text(row.id)
  // Owner originals use UUIDs; the existing server's public corrections use BIGSERIAL strings.
  if (!uuid.test(id) && !/^[1-9][0-9]*$/.test(id)) throw new RequestError('INVALID_RESPONSE', '评价记录标识异常。')
  return { id, text: text(row.text), score, createdAt: text(row.createdAt) }
}
function repair(value: unknown): Repair {
  const row = record(value), receipt = row.receipt == null ? null : record(row.receipt), review = row.review == null ? null : record(row.review)
  return { caseId: resourceId(row.caseId), status: text(row.status), version: integer(row.version), completion: row.completion == null ? null : progress(row.completion),
    receipt: receipt ? { confirmedBy: text(receipt.confirmedBy), createdAt: text(receipt.createdAt), withdrawn: receipt.withdrawn === true } : null,
    review: review ? { original: reviewEntry(review.original), current: reviewEntry(review.current), eligible: review.eligibleForCurrentRating === true } : null }
}
function complaint(value: unknown): Complaint {
  const row = record(value)
  return { id: resourceId(row.id), caseId: resourceId(row.caseId), description: text(row.description), status: text(row.status), createdAt: text(row.createdAt), photos: photoList(row.photos) }
}
function notice(value: unknown): Notice {
  const row = record(value)
  return { id: integer(row.id, 1), caseId: resourceId(row.caseId), title: noticeTitles[text(row.kind)] ?? '案件有新进展', createdAt: text(row.createdAt), read: row.read === true,
    reason: row.kind === 'CASE_CANCELLED' ? text(row.reason) : '',
    section: ['OWNER_RECEIPT', 'OWNER_RECEIVED', 'RECEIPT_WITHDRAWN'].indexOf(text(row.kind)) >= 0 ? 'receipt' : ['COMPLAINT_NEW', 'COMPLAINT_UPDATED'].indexOf(text(row.kind)) >= 0 ? 'complaint' : 'progress' }
}
function page<T>(value: unknown, parse: (item: unknown) => T | null): Page<T> {
  const row = record(value)
  if (!Array.isArray(row.records) || row.records.length > 100) throw new RequestError('INVALID_RESPONSE', '列表返回异常，请重试。')
  return { page: integer(row.page, 1), size: integer(row.size, 1), total: integer(row.total), records: row.records.map(parse).filter((item): item is T => item !== null) }
}
function get<T>(path: string, data: Record<string, unknown>, parse: (value: unknown) => T, scope: string) {
  requireOwner(); return request<unknown>(path, { data, scope }).then(parse)
}
function paged<T>(path: string, number: number, parse: (item: unknown) => T | null, scope: string, extra: Record<string, unknown> = {}) {
  if (!Number.isInteger(number) || number < 1 || number > 100000) throw new RequestError('VALIDATION_ERROR', '页码无效。')
  return get(path, { page: number, size: 20, ...extra }, value => page(value, parse), scope)
}
export const ownerApi = {
  cases: (number = 1, query = '', scope = 'owner-home') => paged('/work-orders', number, parseCase, scope, query.trim() ? { query: query.trim().slice(0, 120) } : {}),
  detail: (id: string, scope: string) => get(`/work-orders/${resourceId(id)}`, {}, parseCase, scope).then(value => { if (value.id !== id) throw new RequestError('INVALID_RESPONSE', '案件信息不匹配。'); return value }),
  repair: (id: string, scope: string) => get(`/repairs/cases/${resourceId(id)}`, {}, repair, scope).then(value => { if (value.caseId !== id) throw new RequestError('INVALID_RESPONSE', '案件信息不匹配。'); return value }),
  progress: (id: string, number: number, scope: string) => paged(`/repairs/cases/${resourceId(id)}/progress`, number, progress, scope),
  photos: (id: string, number: number, scope: string) => paged(`/materials/cases/${resourceId(id)}`, number, parsePhoto, scope),
  history: (id: string, number: number, scope: string) => paged(`/work-orders/${resourceId(id)}/history`, number, value => {
    const row = record(value), state = text(row.toStatus)
    if (['WAITING_SHOP_SETTLEMENT', 'COMPLETED'].indexOf(state) >= 0) return null
    return { label: ownerStatus(state), createdAt: text(row.occurredAt) }
  }, scope),
  notices: (number = 1, scope = 'owner-home') => paged('/notifications', number, notice, scope),
  unread: (scope = 'owner-home') => get('/notifications/unread-count', {}, value => integer(record(value).count), scope),
  complaints: (id: string, number: number, scope: string) => paged(`/complaints/cases/${resourceId(id)}`, number, complaint, scope),
  complaintHistory: (id: string, number: number, scope: string) => paged(`/complaints/${resourceId(id)}/history`, number, value => {
    const row = record(value); return { id: integer(row.id, 1), note: text(row.publicNote), createdAt: text(row.createdAt) }
  }, scope),
  async readNotice(id: number) {
    requireOwner(); integer(id, 1)
    const result = await request<unknown>(`/notifications/${id}/read`, { method: 'POST', data: {} })
    return notice(result)
  },
}
export const canReceive = (value: Repair) => value.status === 'WAITING_OWNER_CONFIRMATION' && !!value.completion?.photos.length
export const canReview = (value: Repair) => received.indexOf(value.status) >= 0 && !!value.receipt && !value.receipt.withdrawn && !value.review
export const canComplain = (row: OwnerCase) => complaintStates.indexOf(row.status) >= 0
export type OwnerAction = { execute: () => Promise<Repair | Complaint> }
// Uncertain writes survive page navigation in this session, so a retry keeps its original key/body.
export type PendingOwnerAction = { kind: 'receipt' | 'review' | 'complaint'; command: OwnerAction; text: string; score: number | null; photoIds: string[] }
export const pendingOwnerActions = new Map<string, PendingOwnerAction>()
onSessionCleared(() => pendingOwnerActions.clear())
function matching<T extends { caseId: string }>(value: T, id: string): T {
  if (value.caseId !== id) throw new RequestError('INVALID_RESPONSE', '返回案件不匹配，请刷新核实，勿重复创建操作。')
  ownerRevision.value++; return value
}
export function receiptCommand(value: Repair): OwnerAction {
  requireOwner(); if (!canReceive(value)) throw new RequestError('RECEIPT_STATE_CONFLICT', '请等待完工照片提交后再确认收车。', 409)
  const command = createCommand(`/repairs/cases/${resourceId(value.caseId)}/receipt`, { expectedVersion: value.version })
  return { execute: async () => { requireOwner(); return matching(repair(await command.execute()), value.caseId) } }
}
export function reviewCommand(value: Repair, content: string, score: number | null): OwnerAction {
  requireOwner()
  if (!canReview(value)) throw new RequestError('REVIEW_STATE_CONFLICT', '请先确认收车；已有评价不能再次提交。', 409)
  if (content.length > 2000 || (!content.trim() && score === null) || (score !== null && (!Number.isInteger(score) || score < 1 || score > 5)))
    throw new RequestError('VALIDATION_ERROR', '评价可跳过；提交时请填写文字或1—5星评分。')
  const command = createCommand(`/repairs/cases/${resourceId(value.caseId)}/review`, { expectedVersion: value.version, text: content.trim() || null, score })
  return { execute: async () => { requireOwner(); return matching(repair(await command.execute()), value.caseId) } }
}
export function complaintCommand(row: OwnerCase, description: string, ids: string[], visiblePhotos: Photo[]): OwnerAction {
  requireOwner()
  if (!canComplain(row)) throw new RequestError('COMPLAINT_STATE_CONFLICT', '此案件当前不能提交反馈。', 409)
  if (!description.trim() || description.length > 2000 || ids.length > 20 || new Set(ids).size !== ids.length
    || ids.some(id => !visiblePhotos.some(photo => photo.id === id))) throw new RequestError('VALIDATION_ERROR', '请填写反馈，并最多选择20张本人可见的已有维修照片。')
  const command = createCommand(`/complaints/cases/${resourceId(row.id)}`, { description: description.trim(), photoIds: [...ids] })
  return { execute: async () => { requireOwner(); return matching(complaint(await command.execute()), row.id) } }
}
