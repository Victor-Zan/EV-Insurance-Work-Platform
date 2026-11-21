import { createCommand, request, RequestError } from './client'
import { onSessionCleared } from './session'
import { assignment, id, integer, parsePage, parseQuote, record, requireShop, shopRevision, string, type QuoteContext, type ShopCase, type ShopCommand, type ShopPhoto } from './shop'
import { money } from './quote-money'

export type RepairPhoto = { id: string; version: number; category: string; contentType: string }
export type RepairEntry = { id: string; assignmentVersion: number; createdAt: string; note: string; photos: RepairPhoto[] }
export type Review = { id: string; text: string; score: number | null; source: string; createdAt: string }
export type ShopRepair = { caseId: string; status: string; version: number; assignmentVersion: number; completion: RepairEntry | null;
  receipt: { confirmedBy: string; createdAt: string; withdrawn: boolean } | null; review: { original: Review; current: Review; eligible: boolean } | null }
export type Settlement = { caseId: string; target: string | null; net: string; status: string }
export type RepairPending = { title: string; command: ShopCommand; note: string; photoIds: string[]; category: 'PROGRESS_PHOTO' | 'COMPLETION_PHOTO' }
export const repairPending = new Map<string, RepairPending>()
onSessionCleared(() => repairPending.clear())
const base = (caseId: string) => `/repairs/cases/${id(caseId)}`
function photo(value: unknown): RepairPhoto {
  const row = record(value), category = string(row.category), contentType = string(row.contentType)
  if (['PROGRESS_PHOTO', 'COMPLETION_PHOTO'].indexOf(category) < 0 || ['image/jpeg', 'image/png'].indexOf(contentType) < 0) throw new RequestError('INVALID_RESPONSE', '维修证据需要重新核对。')
  return { id: id(row.id), version: integer(row.version, 1), category, contentType }
}
export function parseRepairEntry(value: unknown): RepairEntry {
  const row = record(value)
  if (!Array.isArray(row.photos) || row.photos.length > 20) throw new RequestError('INVALID_RESPONSE', '维修照片快照需要重新核对。')
  return { id: id(row.id), assignmentVersion: integer(row.assignmentVersion, 1), createdAt: string(row.createdAt), note: string(row.note), photos: row.photos.map(photo) }
}
export function parseReview(value: unknown): Review {
  const row = record(value), key = string(row.id), score = row.score == null ? null : integer(row.score, 1)
  if (!/^(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[1-9][0-9]*)$/i.test(key) || (score !== null && score > 5)) throw new RequestError('INVALID_RESPONSE', '评价记录需要重新核对。')
  return { id: key, text: string(row.text), score, source: string(row.source), createdAt: string(row.createdAt) }
}
export function parseRepair(value: unknown): ShopRepair {
  const row = record(value), receipt = row.receipt == null ? null : record(row.receipt), review = row.review == null ? null : record(row.review)
  const version = integer(row.assignmentVersion, 1), completion = row.completion == null ? null : parseRepairEntry(row.completion)
  if (completion && (completion.assignmentVersion !== version || !completion.photos.length || completion.photos.some(item => item.category !== 'COMPLETION_PHOTO'))) throw new RequestError('INVALID_RESPONSE', '完工快照不属于本次派单，请刷新。')
  return { caseId: id(row.caseId), status: string(row.status), version: integer(row.version), assignmentVersion: version, completion,
    receipt: receipt ? { confirmedBy: string(receipt.confirmedBy), createdAt: string(receipt.createdAt), withdrawn: receipt.withdrawn === true } : null,
    review: review ? { original: parseReview(review.original), current: parseReview(review.current), eligible: review.eligibleForCurrentRating === true } : null }
}
export function parseSettlement(value: unknown): Settlement {
  const row = record(value), payable = record(row.payable), status = string(payable.status)
  if (row.currency !== 'CNY' || row.unit !== 'yuan' || ['UNSET', 'UNPAID', 'PARTIAL', 'SETTLED'].indexOf(status) < 0) throw new RequestError('INVALID_RESPONSE', '结算摘要需要重新核对。')
  return { caseId: id(row.caseId), target: payable.target === null ? null : money(string(payable.target)), net: money(string(payable.net)), status }
}
export function matchRepair(row: ShopCase, value: ShopRepair) {
  const version = assignment(row, true)
  if (row.id !== value.caseId || version !== value.assignmentVersion) throw new RequestError('STALE_ASSIGNMENT', '案件或派单已变化，请刷新。', 409)
  return value
}
export const shopRepairApi = {
  async get(row: ShopCase, scope: string) { assignment(row, true); return matchRepair(row, parseRepair(await request(base(row.id), { scope }))) },
  async history(row: ShopCase, page: number, scope: string) {
    const version = assignment(row, true); integer(page, 1)
    const data = parsePage(await request(`${base(row.id)}/progress`, { scope, data: { page, size: 20 } }), parseRepairEntry)
    if (data.records.some(item => item.assignmentVersion !== version)) throw new RequestError('STALE_ASSIGNMENT', '维修记录不属于本次派单。', 409)
    return data
  },
  async reviews(row: ShopCase, page: number, scope: string) { assignment(row, true); integer(page, 1); return parsePage(await request(`${base(row.id)}/review/history`, { scope, data: { page, size: 20 } }), parseReview) },
  async settlement(row: ShopCase, scope: string) {
    assignment(row, true); const data = parseSettlement(await request(`/funds/cases/${id(row.id)}`, { scope }))
    if (data.caseId !== row.id) throw new RequestError('INVALID_RESPONSE', '结算案件不匹配。'); return data
  },
}
export function canOpenCompletion(row: ShopCase, repair: ShopRepair, quote: QuoteContext) {
  return row.assignment?.status === 'ACCEPTED' && row.id === repair.caseId && row.id === quote.caseId && row.assignment.version === repair.assignmentVersion
    && repair.assignmentVersion === quote.assignmentVersion && ['WAITING_REPAIR_AUTHORIZATION', 'REPAIRING'].indexOf(repair.status) >= 0 && quote.authorized && quote.gate.allowed
}
export function startRepairCommand(row: ShopCase, repair: ShopRepair, quote: QuoteContext): ShopCommand<QuoteContext> {
  matchRepair(row, repair)
  if (repair.status !== 'WAITING_REPAIR_AUTHORIZATION' || !canOpenCompletion(row, repair, quote)) throw new RequestError('REPAIR_GATE_INCOMPLETE', '请等待客服完成维修授权。', 409)
  const version = assignment(row, true), caseId = row.id, command = createCommand(`/quotations/cases/${id(caseId)}/start-repair`, { expectedVersion: quote.version, assignmentVersion: version })
  return { execute: async () => { requireShop(); const value = parseQuote(await command.execute()); if (value.caseId !== caseId || value.assignmentVersion !== version) throw new RequestError('INVALID_RESPONSE', '维修授权返回案件不匹配。'); shopRevision.value++; return value } }
}
export function repairCommand(row: ShopCase, repair: ShopRepair, quote: QuoteContext, operation: 'progress' | 'completion', note: string, ids: string[], photos: ShopPhoto[]): ShopCommand<ShopRepair> {
  matchRepair(row, repair)
  if (repair.status !== 'REPAIRING' || !canOpenCompletion(row, repair, quote)) throw new RequestError('REPAIR_GATE_INCOMPLETE', '当前状态不允许提交维修记录。', 409)
  const category = operation === 'completion' ? 'COMPLETION_PHOTO' : 'PROGRESS_PHOTO'
  if (ids.length > 20 || new Set(ids).size !== ids.length || (operation === 'completion' && !ids.length) || ids.some(key => !photos.some(item => item.id === key && item.category === category))) throw new RequestError('INVALID_REPAIR_PHOTO', '请核对本次派单照片；完工须选择1至20张完工照，进度只使用进度照。')
  if (operation === 'progress' && (!note.trim() || note.length > 2000)) throw new RequestError('VALIDATION_ERROR', '请填写1至2000字维修进度。')
  const version = assignment(row, true), caseId = row.id, body = { expectedVersion: repair.version, assignmentVersion: version, photoIds: ids.map(id), ...(operation === 'progress' ? { note: note.trim() } : {}) }
  const command = createCommand(`${base(caseId)}/${operation}`, body)
  return { execute: async () => { requireShop(); const value = parseRepair(await command.execute()); if (value.caseId !== caseId || value.assignmentVersion !== version) throw new RequestError('INVALID_RESPONSE', '维修记录返回案件不匹配。'); shopRevision.value++; return value } }
}
