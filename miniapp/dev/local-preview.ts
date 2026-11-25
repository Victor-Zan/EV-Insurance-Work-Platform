import type { DemoRole, DemoResponse, DemoRequest, DemoUpload } from '../src/services/local-preview'
import { lineAmount, quoteTotal } from '../src/services/quote-money'

// Substituted only by explicit local-demo builds; native preview is devtools-only.
export const isLocalDemo = true
export const isNativePreview = import.meta.env.VITE_NATIVE_PREVIEW === 'true'
function localOnly() {
  if (isNativePreview) {
    if (uni.getSystemInfoSync().platform !== 'devtools') throw new Error('开发预览仅支持微信开发者工具，不可用于真机或发布。')
    return
  }
  if (typeof location === 'undefined' || ['127.0.0.1', 'localhost', '[::1]'].indexOf(location.hostname) < 0)
    throw new Error('演示包仅允许在本机回环地址打开。')
}
type Photo = { id: string; version: number; category: string; contentType: string; state: string; createdAt: string }
type Entry = { id: string; assignmentVersion: number; note: string; createdAt: string; photos: Photo[] }
type Review = { id: string; text: string | null; score: number | null; source: string; createdAt: string }
type QuoteLine = { description: string; quantity: number; unitPrice: string; amount: string }
type RawQuote = { id: string; version: number; assignmentVersion: number; total: string; lines: QuoteLine[] }
type Case = { id: string; businessNo: string; status: string; assignmentVersion: number; repairVersion: number; quoteVersion: number;
  vehicleBrand: string; vehicleModel: string; plate: string; photos: Photo[]; progress: Entry[]; completion: Entry | null;
  receipt: { confirmedBy: string; createdAt: string; withdrawn: boolean } | null; review: Review | null; raw: RawQuote | null;
  complaints: { id: string; caseId: string; description: string; status: string; createdAt: string; photos: Photo[] }[] }
let nextId = 100, role: DemoRole | null = null
const uid = () => `00000000-0000-4000-8000-${('000000000000' + ++nextId).slice(-12)}`
const now = () => new Date().toISOString()
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const files = new Map<string, Blob>()
const retries = new Map<string, DemoResponse>()
const uploadKeys = new Map<string, string>()
const users = {
  OWNER: { id: 90001, username: 'demo-owner', displayName: '本机演示车主', roles: ['OWNER'], enabled: true, activated: true },
  REPAIR_SHOP: { id: 90002, username: 'demo-shop', displayName: '本机演示维修网点', roles: ['REPAIR_SHOP'], shopId: 7, enabled: true, activated: true },
}
function sample(number: number, status: string): Case {
  const value: Case = { id: uid(), businessNo: `DEMO-20261107-${('000000' + number).slice(-6)}`, status,
    assignmentVersion: 1, repairVersion: 0, quoteVersion: 0, vehicleBrand: '雅迪', vehicleModel: number % 2 ? '冠能' : '电动轻便车',
    plate: '演示车辆', photos: [], progress: [], completion: null, receipt: null, review: null, raw: null, complaints: [] }
  if (['REPAIRING', 'WAITING_OWNER_CONFIRMATION', 'WAITING_INSURER_PAYMENT'].indexOf(status) >= 0) {
    const photo: Photo = { id: uid(), version: 1, category: status === 'REPAIRING' ? 'PROGRESS_PHOTO' : 'COMPLETION_PHOTO', contentType: 'image/png', state: 'ACTIVE', createdAt: now() }
    value.photos.push(photo)
    const entry = { id: uid(), assignmentVersion: 1, note: '演示记录：车辆检修完成，正在核对试车情况。', createdAt: now(), photos: [photo] }
    value.progress.push({ ...entry, photos: status === 'REPAIRING' ? [photo] : [] })
    if (status !== 'REPAIRING') value.completion = entry
    if (status === 'WAITING_INSURER_PAYMENT') value.receipt = { confirmedBy: 'OWNER', createdAt: now(), withdrawn: false }
  }
  return value
}
const cases = [sample(1, 'PENDING_ACCEPTANCE'), sample(2, 'ARRIVED'), sample(3, 'REPAIRING'), sample(4, 'WAITING_OWNER_CONFIRMATION'), sample(5, 'WAITING_INSURER_PAYMENT')]
const notices = cases.map((item, index) => ({ id: index + 1, caseId: item.id, kind: item.status === 'WAITING_OWNER_CONFIRMATION' ? 'OWNER_RECEIPT' : item.status === 'WAITING_INSURER_PAYMENT' ? 'OWNER_RECEIVED' : 'DISPATCHED', createdAt: now(), read: false }))
const success = (data: unknown): DemoResponse => ({ statusCode: 200, data: { code: 'SUCCESS', data: clone(data) } })
const failure = (statusCode: number, code: string): DemoResponse => ({ statusCode, data: { code } })
function page<T>(rows: T[], data: Record<string, unknown>) {
  const number = Math.max(1, Number(data.page) || 1), size = Math.min(20, Math.max(1, Number(data.size) || 20))
  return { page: number, size, total: rows.length, records: rows.slice((number - 1) * size, number * size) }
}
function row(item: Case) {
  return { id: item.id, businessNo: item.businessNo, claimNo: `示例理赔-${item.businessNo.slice(-6)}`, status: item.status, shopId: 7,
    vehicleBrand: item.vehicleBrand, vehicleModel: item.vehicleModel, vehiclePlate: item.plate, ownerName: '演示车主', ownerPhone: '',
    insuranceCompany: '演示保险资料', accidentAddress: '本机示例位置', accidentDescription: '仅用于检查界面与交互，不是真实案件。',
    workflowNode: item.status === 'REPAIRING' ? 'REPAIR' : item.status.startsWith('WAITING_OWNER') ? 'RECEIPT' : item.status === 'WAITING_INSURER_PAYMENT' ? role === 'REPAIR_SHOP' ? 'FUNDS' : 'COMPLETE' : item.status === 'PENDING_ACCEPTANCE' ? 'DISPATCH_ARRIVAL' : 'SHOP_QUOTE',
    currentAssignment: { shopId: 7, assignmentVersion: item.assignmentVersion, status: item.status === 'PENDING_ACCEPTANCE' ? 'PENDING' : 'ACCEPTED' } }
}
function repair(item: Case) {
  return { caseId: item.id, status: item.status, version: item.repairVersion, assignmentVersion: item.assignmentVersion,
    completion: item.completion, receipt: item.receipt, review: item.review ? { original: item.review, current: item.review, eligibleForCurrentRating: true } : null }
}
function quote(item: Case) {
  const allowed = ['REPAIRING', 'WAITING_OWNER_CONFIRMATION', 'WAITING_INSURER_PAYMENT'].indexOf(item.status) >= 0
  return { caseId: item.id, status: item.status, version: item.quoteVersion, assignmentVersion: item.assignmentVersion,
    arrivalPhotoUploaded: item.photos.some(photo => photo.category === 'ARRIVAL_PHOTO'), rawQuote: item.raw, authorized: allowed,
    gate: { allowed, finalAssessmentAmountRecorded: allowed, insurerConfirmationRecorded: allowed, customerServiceConfirmationRecorded: allowed } }
}
export function demoSession(value: DemoRole): unknown {
  localOnly(); role = value
  return { accessToken: `local-demo-${value.toLowerCase()}-not-a-server-token`, expiresAt: new Date(Date.now() + 8 * 3600000).toISOString(), user: users[value] }
}
export async function demoLogin(value: DemoRole): Promise<unknown> { return demoSession(value) }
export async function demoRequest(path: string, options: DemoRequest): Promise<DemoResponse> {
  localOnly()
  if (!role) return failure(401, 'NO_SESSION')
  const key = options.idempotencyKey ? `${role}:${path}:${options.idempotencyKey}` : ''
  if (key && retries.has(key)) return clone(retries.get(key)!)
  const result = handle(path, options)
  if (key && result.statusCode === 200) retries.set(key, clone(result))
  return result
}
function handle(path: string, options: DemoRequest): DemoResponse {
  const data = options.data ?? {}, post = options.method === 'POST', shop = role === 'REPAIR_SHOP'
  if (path === '/auth/me') return success(users[role!])
  if (path === '/work-orders') {
    const query = String(data.query ?? '').trim().toLowerCase()
    return success(page(cases.filter(item => item.status !== 'PENDING_DISPATCH' && (!query || `${item.businessNo}${item.vehicleBrand}${item.vehicleModel}`.toLowerCase().includes(query))).map(row), data))
  }
  if (path === '/notifications/unread-count') return success({ count: notices.filter(item => !item.read).length })
  if (path === '/notifications') return success(page(notices, data))
  if (path === '/notifications/todos') return shop ? success(page(cases.filter(item => ['PENDING_ACCEPTANCE', 'PENDING_ARRIVAL', 'ARRIVED', 'REPAIRING'].indexOf(item.status) >= 0).map(item => ({ taskKey: item.id, caseId: item.id, kind: item.status === 'PENDING_ACCEPTANCE' ? 'ACCEPT_SHOP' : item.status === 'PENDING_ARRIVAL' ? 'ARRIVE_SHOP' : item.status === 'REPAIRING' ? 'REPAIR_SHOP' : 'QUOTE_SHOP', deadline: null, overdue: false })), data)) : failure(403, 'FORBIDDEN')
  const notice = /^\/notifications\/(\d+)\/read$/.exec(path)
  if (notice && post) { const value = notices.find(item => item.id === Number(notice[1])); if (!value) return failure(404, 'NOT_FOUND'); value.read = true; return success(value) }
  const complaint = /^\/complaints\/([a-f0-9-]+)\/history$/.exec(path)
  if (complaint) return success(page([{ id: 1, publicNote: '演示反馈已登记，等待处理。', createdAt: now() }], data))
  const id = path.split('/').find(part => /^[a-f0-9-]{36}$/.test(part)), item = cases.find(value => value.id === id)
  if (!item) return failure(404, 'LOCAL_DEMO_UNSUPPORTED')
  const base = `/repairs/cases/${item.id}`
  if (path === `/work-orders/${item.id}`) return success(row(item))
  if (path === `/work-orders/${item.id}/history`) return success(page([{ toStatus: item.status, occurredAt: now() }], data))
  if (path === `/materials/cases/${item.id}`) return success(page(item.photos, data))
  if (path === `/complaints/cases/${item.id}`) {
    if (post) {
      if (shop) return failure(403, 'FORBIDDEN')
      const ids = Array.isArray(data.photoIds) ? data.photoIds : []
      const value = { id: uid(), caseId: item.id, description: String(data.description ?? ''), status: 'SUBMITTED', createdAt: now(), photos: item.photos.filter(photo => ids.indexOf(photo.id) >= 0) }
      item.complaints.unshift(value); return success(value)
    }
    return success(page(item.complaints, data))
  }
  if (path === base) return success(repair(item))
  if (path === `${base}/progress` && !post) return success(page(item.progress, data))
  if (path === `${base}/review/history`) return success(page(item.review ? [item.review] : [], data))
  if (path.startsWith('/quotations/') || path.startsWith('/funds/')) {
    if (!shop) return failure(403, 'FORBIDDEN')
    if (path === `/quotations/cases/${item.id}`) return success(quote(item))
    if (path.endsWith('/ocr/configuration')) return success({ provider: 'MOCK_V1' })
    if (path.endsWith('/ocr')) return success(page([], data))
    if (path.endsWith('/history')) return success(page(item.raw ? [{ id: item.raw.id, version_no: item.raw.version, total: item.raw.total, created_at: now() }] : [], data))
    if (path.endsWith('/raw-quotes') && post) {
      if (data.expectedVersion !== item.quoteVersion || data.assignmentVersion !== item.assignmentVersion || !item.photos.some(photo => photo.category === 'ARRIVAL_PHOTO')) return failure(409, 'STATE_CONFLICT')
      const lines = data.lines as { description: string; quantity: number; unitPrice: string }[]
      item.raw = { id: uid(), version: (item.raw?.version ?? 0) + 1, assignmentVersion: item.assignmentVersion,
        total: quoteTotal(lines.map(line => ({ quantity: String(line.quantity), unitPrice: line.unitPrice }))),
        lines: lines.map(line => ({ ...line, amount: lineAmount(String(line.quantity), line.unitPrice) })) }
      item.quoteVersion++; item.status = 'QUOTE_REVIEWING'; return success(quote(item))
    }
    if (path === `/funds/cases/${item.id}`) return success({ caseId: item.id, currency: 'CNY', unit: 'yuan', payable: { target: item.receipt ? '100.00' : null, net: '0.00', status: item.receipt ? 'UNPAID' : 'UNSET' } })
  }
  if (post && path.startsWith('/work-orders/')) {
    if (!shop) return failure(403, 'FORBIDDEN')
    if (data.assignmentVersion !== item.assignmentVersion) return failure(409, 'STATE_CONFLICT')
    const operation = path.split('/').pop(), states: Record<string, [string, string]> = { accept: ['PENDING_ACCEPTANCE', 'PENDING_ARRIVAL'], reject: ['PENDING_ACCEPTANCE', 'PENDING_DISPATCH'], arrive: ['PENDING_ARRIVAL', 'ARRIVED'], 'arrival-exceptions': ['PENDING_ARRIVAL', 'ARRIVAL_EXCEPTION'], 'continue-waiting': ['ARRIVAL_EXCEPTION', 'PENDING_ARRIVAL'] }
    const next = states[operation ?? '']; if (!next || item.status !== next[0]) return failure(409, 'STATE_CONFLICT')
    item.status = next[1]; return success(row(item))
  }
  if (post && path.startsWith(base)) {
    if (data.expectedVersion !== item.repairVersion) return failure(409, 'STATE_CONFLICT')
    if (path.endsWith('/progress') || path.endsWith('/completion')) {
      if (!shop) return failure(403, 'FORBIDDEN')
      const completion = path.endsWith('/completion'), ids = Array.isArray(data.photoIds) ? data.photoIds : []
      const photos = item.photos.filter(photo => ids.indexOf(photo.id) >= 0 && photo.category === (completion ? 'COMPLETION_PHOTO' : 'PROGRESS_PHOTO'))
      if (item.status !== 'REPAIRING' || data.assignmentVersion !== item.assignmentVersion || (completion && !photos.length) || photos.length !== ids.length) return failure(409, 'STATE_CONFLICT')
      const entry = { id: uid(), assignmentVersion: item.assignmentVersion, note: String(data.note ?? ''), createdAt: now(), photos: clone(photos) }
      if (completion) { item.completion = entry; item.status = 'WAITING_OWNER_CONFIRMATION' } else item.progress.unshift(entry)
    } else if (path.endsWith('/receipt')) {
      if (shop) return failure(403, 'FORBIDDEN')
      if (item.status !== 'WAITING_OWNER_CONFIRMATION' || !item.completion?.photos.length) return failure(409, 'STATE_CONFLICT')
      item.receipt = { confirmedBy: 'OWNER', createdAt: now(), withdrawn: false }; item.status = 'WAITING_INSURER_PAYMENT'
    } else if (path.endsWith('/review')) {
      if (shop) return failure(403, 'FORBIDDEN')
      if (!item.receipt || item.review) return failure(409, 'STATE_CONFLICT')
      item.review = { id: uid(), text: data.text == null ? null : String(data.text), score: data.score == null ? null : Number(data.score), source: 'OWNER', createdAt: now() }
    } else return failure(404, 'LOCAL_DEMO_UNSUPPORTED')
    item.repairVersion++; return success(repair(item))
  }
  return failure(404, 'LOCAL_DEMO_UNSUPPORTED')
}
export async function demoUpload(command: DemoUpload, active: () => boolean): Promise<string> {
  localOnly()
  if (isNativePreview) throw new Error('微信开发预览不上传真实图片，请使用示例案件查看照片和页面。')
  if (role !== 'REPAIR_SHOP' || !active()) throw new Error('演示账号已变化。')
  const existing = uploadKeys.get(command.idempotencyKey); if (existing) return existing
  const item = cases.find(value => value.id === command.caseId)
  if (!item || ['ARRIVAL_PHOTO', 'PROGRESS_PHOTO', 'COMPLETION_PHOTO'].indexOf(command.category ?? '') < 0) throw new Error('演示上传缺少有效案件或类别。')
  if (!command.file.path.startsWith('blob:') && !command.file.path.startsWith('data:image/')) throw new Error('演示仅接受本机所选图片。')
  const blob = await (await fetch(command.file.path)).blob()
  if (!active()) throw new Error('账号已退出，本次演示上传已取消。')
  const photo: Photo = { id: uid(), version: 1, category: command.category!, contentType: command.file.mimeType ?? blob.type, state: 'ACTIVE', createdAt: now() }
  files.set(photo.id, blob); item.photos.unshift(photo); uploadKeys.set(command.idempotencyKey, photo.id); return photo.id
}
export async function demoFile(path: string): Promise<string> {
  localOnly()
  const id = /^\/materials\/([a-f0-9-]+)\/download$/.exec(path)?.[1]
  if (!id || !cases.some(item => item.photos.some(photo => photo.id === id))) throw new Error('本机演示未提供此文件；OCR请使用手填报价检查。')
  const blob = files.get(id)
  return blob ? URL.createObjectURL(blob) : '/static/owner-progress-hero.webp'
}
