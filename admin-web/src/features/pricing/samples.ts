import { chinaToday } from './prices.ts'

export const sampleSources = { XIAOBANGBANG: '肖邦邦导入', MARKET: '市场询价', SHOP_QUOTE: '网点报价', HISTORICAL_ARCHIVE: '正式历史档案' } as const
export const sampleStates = { PENDING: '待核对', CONFIRMED: '已确认', EXCLUDED: '已排除' } as const
export const priceNatures = { MARKET_RETAIL: '市场零售单价', SHOP_QUOTED: '网点报价单价', SUPPLIER_QUOTED: '历史配件商报价', CP_QUOTED: '历史 CP 报价', UNCLASSIFIED: '待确认性质' } as const
export const importPeriods = { ALL: '不限', WEEK: '近一周', MONTH: '近一个月', QUARTER: '近三个月', YEAR: '近一年' } as const
export type ImportPeriod = keyof typeof importPeriods

export function importDateRange(period: ImportPeriod, now = new Date()) {
  if (period === 'ALL') return {}
  const today = chinaToday(now)
  const [year, month, day] = today.split('-').map(Number) as [number, number, number]
  let start: Date
  if (period === 'WEEK') start = new Date(Date.UTC(year, month - 1, day - 6))
  else {
    const offset = { MONTH: 1, QUARTER: 3, YEAR: 12 }[period]
    const first = new Date(Date.UTC(year, month - 1 - offset, 1))
    const lastDay = new Date(Date.UTC(first.getUTCFullYear(), first.getUTCMonth() + 1, 0)).getUTCDate()
    first.setUTCDate(Math.min(day, lastDay))
    start = first
  }
  return { from: start.toISOString().slice(0, 10), to: today }
}
export interface PriceSample {
  id: string; batchId: string; sourceKind: keyof typeof sampleSources; version: number
  status: keyof typeof sampleStates; original: Record<string, string>; hasFile: boolean; fileName?: string
  partId?: number; partName?: string; modelId?: number; modelName?: string; vehicleBrand?: string
  amount?: string; unit?: string; partBrand?: string; region?: string
  importedDate: string; priceNature?: keyof typeof priceNatures; reason: string
}
export interface ReferenceGroup {
  part_id: number; model_id: number; part_name: string; model_name: string; vehicle_brand: string
  unit: string; part_brand: string; region: string; price_nature: keyof typeof priceNatures
  records: number; minimum: string; maximum: string; latest: string
}
export const sampleFields = [
  ['partName', '原始配件名称'], ['vehicleModel', '原始车型名称'], ['amount', '原始金额'], ['unit', '计量单位'],
  ['partBrand', '配件品牌'], ['region', '地区'], ['sourceNote', '来源说明'],
] as const
export function sampleReview(row: PriceSample) {
  const original = row.original
  return {
    expectedVersion: row.version, status: row.status, partId: row.partId ?? null, modelId: row.modelId ?? null,
    amount: row.amount ?? original.amount ?? '', unit: row.unit ?? original.unit ?? '',
    partBrand: row.partBrand ?? original.partBrand ?? '', region: row.region ?? original.region ?? '',
    priceNature: row.priceNature ?? (Object.hasOwn(priceNatures, original.priceNature ?? '') ? original.priceNature as keyof typeof priceNatures : 'UNCLASSIFIED'),
    reason: '',
  }
}
