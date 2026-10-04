import type { User } from '../../shared/auth/session.ts'

export type CatalogueKind = 'brands' | 'models' | 'parts' | 'aliases' | 'sources'
export interface CatalogueItem {
  id: number
  name: string
  code?: string
  internalCode?: string
  brandId?: number
  partId?: number
  kind?: string
  enabled?: boolean
}
export interface CatalogueField { key: 'name' | 'code' | 'internalCode' | 'brandId' | 'partId' | 'kind'; label: string; numeric?: boolean }
export const catalogueDefinitions: Record<CatalogueKind, { title: string; fields: CatalogueField[]; enabled: boolean }> = {
  brands: { title: '车辆品牌', fields: [{ key: 'code', label: '品牌编码' }, { key: 'name', label: '品牌名称' }], enabled: true },
  models: { title: '车型', fields: [{ key: 'brandId', label: '品牌 ID', numeric: true }, { key: 'code', label: '车型编码' }, { key: 'name', label: '车型名称' }], enabled: true },
  parts: { title: '标准配件', fields: [{ key: 'internalCode', label: '内部配件编号' }, { key: 'name', label: '标准名称' }], enabled: true },
  aliases: { title: '配件别名', fields: [{ key: 'partId', label: '标准配件 ID', numeric: true }, { key: 'name', label: '别名' }], enabled: false },
  sources: { title: '数据来源', fields: [{ key: 'code', label: '来源编码' }, { key: 'name', label: '来源名称' }, { key: 'kind', label: '来源类型' }], enabled: true },
}
export function canMaintain(user: User | null): boolean { return !!user?.roles.includes('ADMIN') }
export function catalogueKind(value: unknown): CatalogueKind {
  if (typeof value === 'string' && Object.hasOwn(catalogueDefinitions, value)) return value as CatalogueKind
  throw new Error('未知价格基础资料类型')
}
export function cataloguePayload(kind: CatalogueKind, values: Record<string, string>, enabled: boolean): Record<string, string | number | boolean> {
  const definition = catalogueDefinitions[kind]
  const result: Record<string, string | number | boolean> = {}
  for (const field of definition.fields) {
    const raw = values[field.key]?.trim() ?? ''
    if (!raw) throw new Error(field.label + '必填')
    if (raw.length > (field.key === 'name' ? 120 : 64)) throw new Error(field.label + '过长')
    if (field.numeric) {
      const id = Number(raw)
      if (!Number.isSafeInteger(id) || id < 1) throw new Error(field.label + '必须为正整数')
      result[field.key] = id
    } else result[field.key] = raw
  }
  if (kind === 'sources' && !['MANUAL', 'CSV', 'EXCEL', 'HISTORICAL_CASE'].includes(values.kind ?? '')) throw new Error('来源类型无效')
  if (definition.enabled) result.enabled = enabled
  return result
}
