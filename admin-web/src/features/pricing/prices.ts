import type { Role } from '../../shared/auth/session.ts'
export const PRICE_READ_ROLES: Role[] = ['ADMIN', 'CUSTOMER_SERVICE']
export const PRICE_WRITE_ROLES: Role[] = ['ADMIN']
export const priceTypes = {
  REPAIR_SHOP_RAW_REFERENCE: '网点原始参考价',
  PLATFORM_EXTERNAL_REFERENCE: '平台对外参考价',
  INSURER_HISTORICAL_ASSESSED: '历史保险核损参考价',
  REPAIR_SHOP_SETTLEMENT_REFERENCE: '网点结算参考价',
} as const
export type PriceType = keyof typeof priceTypes
export type Scope = 'NATIONAL' | 'REGION' | 'SHOP'
export interface Price {
 id: number; recordId: number; partId: number; internalCode: string; partName: string
 modelId: number; modelName: string; brandId: number; brandName: string
 priceType: PriceType; scope: Scope; regionId?: number; regionName?: string; shopId?: number; shopName?: string
 amount: string; currency: 'CNY'; sourceId: number; sourceCode: string; sourceName: string; sourceKind: string
 effectiveFrom: string; effectiveTo?: string; versionNo: number; previousVersionId?: number; closedByVersionId?: number
 batchId?: number; createdAt: string; createdBy: number
}
export interface Preview { id: string; fileName: string; source: string; totalRows: number; validRows: number; errorRows: number; duplicateRows: number; expiresAt: string }
export interface ImportError { rowNumber: number; field: string; reason: string; originalValue: string }
export interface PreviewRow { rowNumber: number; values: Record<string, string>; errors: ImportError[]; action: string }
export interface Batch { id: number; fileName: string; source: string; status: 'SUCCESS' | 'FAILED'; totalRows: number; successCount: number; failureCount: number; actorId: number; createdAt: string; completedAt: string }
export function scopeLabel(price: Pick<Price, 'scope' | 'regionId' | 'regionName' | 'shopId' | 'shopName'>): string {
 if (price.scope === 'NATIONAL') return '全国通用'
 if (price.scope === 'REGION') return '区域：' + (price.regionName ?? '') + '（ID ' + price.regionId + '）'
 return '网点：' + (price.shopName ?? '') + '（ID ' + price.shopId + '）'
}
export function chinaToday(now = new Date()): string {
 const parts = new Intl.DateTimeFormat('en', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(now)
 const value = (type: string) => parts.find(part => part.type === type)?.value ?? ''
 return value('year') + '-' + value('month') + '-' + value('day')
}
export function positiveId(raw: string): number { const n = Number(raw); if (!Number.isSafeInteger(n) || n < 1) throw new Error('ID 必须为正整数'); return n }
export function validDate(value: string): boolean {
 if (!/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(value)) return false
 const [year, month, day] = value.split('-').map(Number)
 if (!year || !month || !day || month > 12) return false
 const days = [31, year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
 return day <= (days[month - 1] ?? 0)
}
export function versionPayload(form: { amount: string; sourceId: string; effectiveFrom: string; effectiveTo: string }) {
 const amount = form.amount.trim(), integer = amount.split('.')[0]?.replace(/^0+/, '') ?? ''
 if (!/^[0-9]+(\.[0-9]{1,2})?$/.test(amount) || !/[1-9]/.test(amount) || integer.length > 16) throw new Error('金额必须大于零，整数最多 16 位、小数最多 2 位；不会自动舍入')
 if (!validDate(form.effectiveFrom) || form.effectiveTo && (!validDate(form.effectiveTo) || form.effectiveTo < form.effectiveFrom)) throw new Error('请输入有效的中国自然日，截止日不得早于开始日')
 return { amount, sourceId: positiveId(form.sourceId), effectiveFrom: form.effectiveFrom, effectiveTo: form.effectiveTo || null }
}
export function filterPayload(filters: Record<string, string>, page: number): Record<string, string | number> {
 const result: Record<string, string | number> = { page, size: 20 }
 for (const [key, value] of Object.entries(filters)) {
  if (!value.trim()) continue
  if (['brandId', 'modelId', 'regionId', 'shopId'].includes(key)) result[key] = positiveId(value)
  else { if (key === 'queryDate' && !validDate(value)) throw new Error('查询日期无效'); result[key] = value.trim() }
 }
 return result
}
export function mayConfirm(preview: Preview | null, checked: boolean, busy: boolean): boolean { return !!preview && checked && !busy }
