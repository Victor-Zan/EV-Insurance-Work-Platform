import { ref } from 'vue'
import { createCommand, request, RequestError } from './client'
import { currentSession, isRecord, onSessionCleared } from './session'
import { money, quantity, quoteTotal } from './quote-money'
export type Page<T> = { page: number; size: number; total: number; records: T[] }
export type ShopCase = { id: string; businessNo: string; claimNo: string; status: string; vehicle: string; plate: string; owner: string; phone: string;
  insurance: string; address: string; description: string; assignment: { version: number; status: string } | null; shopId: number }
export type ShopPhoto = { id: string; version: number; category: string; contentType: string; createdAt: string }
export type QuoteLine = { description: string; quantity: string; unitPrice: string; unit?: string; lineAmount?: string; quantityDefaulted?: boolean; ocr?: { jobId: string; itemIndex: number; revision?: number } }
export type RawQuote = { id: string; version: number; assignmentVersion: number; total: string; lines: { description: string; quantity: number; unitPrice: string; amount: string }[] }
export type RawHistory = { id: string; version: number; total: string; createdAt: string }
export type QuoteContext = { caseId: string; status: string; version: number; assignmentVersion: number; arrivalPhotoUploaded: boolean; rawQuote: RawQuote | null;
  authorized: boolean; gate: { allowed: boolean; amount: boolean; insurer: boolean; service: boolean } }
export type ShopNotice = { id: number; caseId: string; title: string; read: boolean; createdAt: string; reason: string }
export type ShopTask = { key: string; caseId: string; title: string; deadline: string; overdue: boolean }
export type ShopCommand<T = unknown> = { execute: () => Promise<T> }
export const shopRevision = ref(0)
export const shopPending = new Map<string, { title: string; command: ShopCommand; draft?: QuoteLine[] }>()
onSessionCleared(() => { shopPending.clear(); shopRevision.value = 0 })
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
export function id(value: unknown): string { if (typeof value !== 'string' || !uuid.test(value)) throw new RequestError('VALIDATION_ERROR', '案件或材料标识无效。'); return value }
export function record(value: unknown): Record<string, unknown> { if (!isRecord(value)) throw new RequestError('INVALID_RESPONSE', '服务数据需要重新核对。'); return value }
export const string = (value: unknown) => typeof value === 'string' ? value : ''
export function integer(value: unknown, minimum = 0): number { if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < minimum) throw new RequestError('INVALID_RESPONSE', '服务数据需要重新核对。'); return value }
export function requireShop() { const user = currentSession()?.user; if (!user || user.role !== 'REPAIR_SHOP' || !user.shopId) throw new RequestError('FORBIDDEN', '仅已授权维修网点可以使用。', 403); return user.shopId }
const statuses: Record<string, string> = { PENDING_DISPATCH: '待派单', PENDING_ACCEPTANCE: '待接单', PENDING_ARRIVAL: '待到店', ARRIVAL_EXCEPTION: '到店异常', ARRIVED: '已到店', WAITING_QUOTE: '待报价', QUOTE_REVIEWING: '报价审核中', WAITING_INSURER_ASSESSMENT: '等待保险核损', WAITING_REPAIR_AUTHORIZATION: '待维修授权', REPAIRING: '维修中', WAITING_OWNER_CONFIRMATION: '待车主收车', WAITING_INSURER_PAYMENT: '待结算', WAITING_SHOP_SETTLEMENT: '待结算', COMPLETED: '已完成', CLOSED: '已关闭', CANCELLED: '已取消' }
export const shopStatus = (value: string) => statuses[value] ?? '状态待核对'
const titles: Record<string, string> = { ACCEPT: '新派单待接单', ARRIVAL: '确认车辆到店', ARRIVED: '车辆已到店', QUOTE: '上传到店照片并报价', REPAIR: '维修任务', OWNER_RECEIPT: '等待车主收车', OWNER_RECEIVED: '车主已确认收车', COMPLAINT_NEW: '服务反馈已提交', COMPLAINT_UPDATED: '反馈处理已更新', CASE_CANCELLED: '案件已取消' }
Object.assign(titles, { DISPATCHED: '新派单待接单', ACCEPT_SHOP: '新派单待接单', ARRIVE_SHOP: '确认车辆到店', QUOTE_SHOP: '待提交网点报价', QUOTE_REVIEW: '报价待客服审核', REVIEW_QUOTE: '报价待客服审核', START_REPAIR_SHOP: '维修授权已满足', REPAIR_SHOP: '维修任务', RECEIVE_OWNER: '等待车主收车', PAY_UPDATED: '结算记录已更新' })
export const taskTitle = (kind: unknown) => titles[string(kind)] ?? '案件有新进展'
export function parseCase(value: unknown): ShopCase {
  const row = record(value), shop = requireShop(), assignment = row.currentAssignment == null ? null : record(row.currentAssignment)
  if (row.shopId !== shop || row.status === 'DRAFT' || (assignment && assignment.shopId !== shop)) throw new RequestError('FORBIDDEN', '案件不属于当前网点。', 403)
  return { id: id(row.id), businessNo: string(row.businessNo), claimNo: string(row.claimNo), status: string(row.status), shopId: shop,
    vehicle: [string(row.vehicleBrand), string(row.vehicleModel)].filter(Boolean).join(' '), plate: string(row.vehiclePlate), owner: string(row.ownerName), phone: string(row.ownerPhone),
    insurance: string(row.insuranceCompany), address: string(row.accidentAddress), description: string(row.accidentDescription),
    assignment: assignment ? { version: integer(assignment.assignmentVersion, 1), status: string(assignment.status) } : null }
}
export function parseRaw(value: unknown): RawQuote {
  const row = record(value)
  if (!Array.isArray(row.lines) || row.lines.length > 1000) throw new RequestError('INVALID_RESPONSE', '报价明细需要核对。')
  return { id: id(row.id), version: integer(row.version, 1), assignmentVersion: integer(row.assignmentVersion, 1), total: money(string(row.total)), lines: row.lines.map(value => {
    const line = record(value); return { description: string(line.description), quantity: integer(line.quantity, 1), unitPrice: money(string(line.unitPrice)), amount: money(string(line.amount)) }
  }) }
}
export function parseQuote(value: unknown): QuoteContext {
  const row = record(value), gate = record(row.gate)
  if (typeof row.arrivalPhotoUploaded !== 'boolean') throw new RequestError('BACKEND_VERSION', '服务版本暂不支持到店照片核实，请联系工作人员升级。')
  const assignmentVersion = integer(row.assignmentVersion, 1), raw = row.rawQuote == null ? null : parseRaw(row.rawQuote)
  if (raw && raw.assignmentVersion !== assignmentVersion) throw new RequestError('INVALID_RESPONSE', '报价属于旧派单，请刷新核对。')
  return { caseId: id(row.caseId), status: string(row.status), version: integer(row.version), assignmentVersion, arrivalPhotoUploaded: row.arrivalPhotoUploaded, rawQuote: raw,
    authorized: row.authorized === true, gate: { allowed: gate.allowed === true, amount: gate.finalAssessmentAmountRecorded === true, insurer: gate.insurerConfirmationRecorded === true, service: gate.customerServiceConfirmationRecorded === true } }
}
function photo(value: unknown): ShopPhoto | null {
  const row = record(value)
  if (row.state !== 'ACTIVE' || ['ARRIVAL_PHOTO', 'PROGRESS_PHOTO', 'COMPLETION_PHOTO'].indexOf(string(row.category)) < 0 || ['image/jpeg', 'image/png'].indexOf(string(row.contentType)) < 0) return null
  return { id: id(row.id), version: integer(row.version, 1), category: string(row.category), contentType: string(row.contentType), createdAt: string(row.createdAt) }
}
export function parsePage<T>(value: unknown, parse: (value: unknown) => T | null): Page<T> {
  const row = record(value)
  if (!Array.isArray(row.records) || row.records.length > 100) throw new RequestError('INVALID_RESPONSE', '列表需要重新核对。')
  return { page: integer(row.page, 1), size: integer(row.size, 1), total: integer(row.total), records: row.records.map(parse).filter((row): row is T => row !== null) }
}
function notice(value: unknown): ShopNotice { const row = record(value); return { id: integer(row.id, 1), caseId: id(row.caseId), title: taskTitle(row.kind), read: row.read === true, createdAt: string(row.createdAt), reason: row.kind === 'CASE_CANCELLED' ? string(row.reason) : '' } }
function get<T>(path: string, data: Record<string, unknown>, parse: (value: unknown) => T, scope: string) { requireShop(); return request<unknown>(path, { data, scope }).then(parse) }
function paged<T>(path: string, page: number, parse: (value: unknown) => T | null, scope: string, data: Record<string, unknown> = {}) {
  if (!Number.isInteger(page) || page < 1 || page > 100000) throw new RequestError('VALIDATION_ERROR', '页码无效。')
  return get(path, { page, size: 20, ...data }, value => parsePage(value, parse), scope)
}
export const shopApi = {
  cases: (page = 1, query = '', scope = 'shop-home') => paged('/work-orders', page, parseCase, scope, query.trim() ? { query: query.trim().slice(0, 120) } : {}),
  detail: (caseId: string, scope: string) => get(`/work-orders/${id(caseId)}`, {}, parseCase, scope).then(value => { if (value.id !== caseId) throw new RequestError('INVALID_RESPONSE', '案件不匹配。'); return value }),
  quote: (caseId: string, scope: string) => get(`/quotations/cases/${id(caseId)}`, {}, parseQuote, scope).then(value => { if (value.caseId !== caseId) throw new RequestError('INVALID_RESPONSE', '案件不匹配。'); return value }),
  photos: (caseId: string, page: number, scope: string) => paged(`/materials/cases/${id(caseId)}`, page, photo, scope),
  quoteHistory: (caseId: string, page: number, scope: string) => paged(`/quotations/cases/${id(caseId)}/history`, page, value => {
    const row = record(value)
    return { id: id(row.id), version: integer(row.version_no, 1), total: money(string(row.total)), createdAt: string(row.created_at) }
  }, scope, { type: 'RAW' }),
  history: (caseId: string, page: number, scope: string) => paged(`/work-orders/${id(caseId)}/history`, page, value => {
    const row = record(value); return { label: shopStatus(string(row.toStatus)), createdAt: string(row.occurredAt), reason: string(row.reason) }
  }, scope),
  tasks: (page = 1, scope = 'shop-home') => paged('/notifications/todos', page, value => { const row = record(value); return { key: string(row.taskKey), caseId: id(row.caseId), title: taskTitle(row.kind), deadline: string(row.deadline), overdue: row.overdue === true } }, scope),
  notices: (page = 1, scope = 'shop-home') => paged('/notifications', page, notice, scope),
  unread: (scope = 'shop-home') => get('/notifications/unread-count', {}, value => integer(record(value).count), scope),
  async read(id: number) { requireShop(); integer(id, 1); return notice(await request(`/notifications/${id}/read`, { method: 'POST', data: {} })) },
}
export function assignment(row: ShopCase, accepted: boolean) {
  if (row.shopId !== requireShop() || !row.assignment || row.assignment.status !== (accepted ? 'ACCEPTED' : 'PENDING')) throw new RequestError('STALE_ASSIGNMENT', '当前派单或网点归属已变化，请刷新。', 409)
  return row.assignment.version
}
export function quoteEditable(row: ShopCase, quote: QuoteContext) { return row.assignment?.status === 'ACCEPTED' && quote.caseId === row.id && quote.assignmentVersion === row.assignment.version && ['ARRIVED', 'WAITING_QUOTE', 'QUOTE_REVIEWING'].indexOf(quote.status) >= 0 }
export function assignmentCommand(row: ShopCase, operation: 'accept' | 'reject' | 'arrive' | 'arrival-exceptions' | 'continue-waiting', reason = ''): ShopCommand<ShopCase | null> {
  const pending = operation === 'accept' || operation === 'reject', version = assignment(row, !pending)
  const state = pending ? 'PENDING_ACCEPTANCE' : operation === 'continue-waiting' ? 'ARRIVAL_EXCEPTION' : 'PENDING_ARRIVAL'
  if (row.status !== state) throw new RequestError('STATE_CONFLICT', '案件状态已变化，请刷新核对。', 409)
  const limit = operation === 'arrival-exceptions' ? 1000 : 500
  if (reason.length > limit || ((operation === 'reject' || operation === 'arrival-exceptions') && !reason.trim())) throw new RequestError('VALIDATION_ERROR', `请填写原因，最多${limit}字。`)
  const body: Record<string, unknown> = { assignmentVersion: version }
  if (operation === 'reject' || operation === 'arrival-exceptions' || operation === 'continue-waiting') body.reason = reason.trim() || null
  const command = createCommand(`/work-orders/${id(row.id)}/${operation}`, body)
  return { execute: async () => {
    requireShop(); const data = record(await command.execute()); if (data.id !== row.id) throw new RequestError('INVALID_RESPONSE', '案件不匹配。')
    const result = operation === 'reject' ? null : parseCase(data); shopRevision.value++; return result
  } }
}
export function validateLines(lines: QuoteLine[], requireRevision = false) {
  if (!lines.length || lines.length > 1000) throw new RequestError('VALIDATION_ERROR', '报价须有1至1000条明细。')
  quoteTotal(lines); const used = new Set<string>()
  return lines.map(line => {
    if (!line.description.trim() || line.description.length > 255) throw new RequestError('VALIDATION_ERROR', '项目名称必填且最多255字。')
    const result: { description: string; quantity: number; unitPrice: string; ocr?: { jobId: string; revision?: number; itemIndex: number } } = { description: line.description.trim(), quantity: quantity(line.quantity), unitPrice: money(line.unitPrice) }
    if (line.ocr) {
      const key = `${id(line.ocr.jobId)}:${line.ocr.itemIndex}`
      if (!Number.isInteger(line.ocr.itemIndex) || line.ocr.itemIndex < 0 || line.ocr.itemIndex >= 200 || used.has(key) || (requireRevision && (!Number.isInteger(line.ocr.revision) || line.ocr.revision! < 1))) throw new RequestError('VALIDATION_ERROR', '识别引用重复或人工核对版本无效。')
      used.add(key); result.ocr = { ...line.ocr }
    }
    return result
  })
}
export function rawQuoteCommand(row: ShopCase, quote: QuoteContext, lines: QuoteLine[]): ShopCommand<QuoteContext> {
  assignment(row, true)
  if (!quoteEditable(row, quote) || !quote.arrivalPhotoUploaded) throw new RequestError('QUOTE_STATE_CONFLICT', '请先确认本次派单的到店照片已上传，并核对报价阶段。', 409)
  const command = createCommand(`/quotations/cases/${id(row.id)}/raw-quotes`, { expectedVersion: quote.version, assignmentVersion: quote.assignmentVersion, lines: validateLines(lines, true) })
  return { execute: async () => { requireShop(); const result = parseQuote(await command.execute()); if (result.caseId !== row.id || result.assignmentVersion !== quote.assignmentVersion) throw new RequestError('INVALID_RESPONSE', '案件或派单不匹配。'); shopRevision.value++; return result } }
}
