import type { DraftForm } from '../workorders/work-orders'

export const draftLabels: Record<keyof DraftForm,string>={insuranceCompany:'保险公司',claimNo:'保险报案号',claimReportedAt:'报案时间（中国时间）',ownerName:'车主姓名',ownerPhone:'车主手机号',policyNo:'保单号',vehicleBrand:'车辆品牌',vehicleModel:'车型',vehicleVin:'车架号 / VIN',vehiclePlate:'车牌号',vehicleOtherIdentifier:'车辆其他标识',accidentAt:'事故时间（中国时间）',accidentRegionId:'事故区域',accidentAddress:'事故地址',accidentDescription:'事故描述'}
export function blankOcrDraft():DraftForm{return Object.fromEntries(Object.keys(draftLabels).map(k=>[k,''])) as unknown as DraftForm}
export function prefillOcrDraft(fields:Record<string,unknown>):DraftForm{
  const draft=blankOcrDraft()
  for(const key of Object.keys(draftLabels) as (keyof DraftForm)[]){
    if(key==='accidentRegionId')continue // Region names are evidence; never guessed IDs.
    const value=fields[key];if(typeof value!=='string')continue
    if(key==='accidentAt'||key==='claimReportedAt'){
      if(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value)&&Number.isFinite(new Date(value).getTime()))draft[key]=new Date(new Date(value).getTime()+8*3600000).toISOString().slice(0,19)
    }else draft[key]=value
  }
  return draft
}
export function ocrDraftPayload(form:DraftForm){
  return {...form,claimReportedAt:form.claimReportedAt?new Date(form.claimReportedAt+'+08:00').toISOString():null,accidentAt:form.accidentAt?new Date(form.accidentAt+'+08:00').toISOString():null,accidentRegionId:form.accidentRegionId?Number(form.accidentRegionId):null}
}
export interface OcrFieldConflict { field:keyof DraftForm; incoming:string }
// Merge against the live form when recognition finishes, including edits made while waiting.
// Missing OCR values never clear a field. Non-empty differences always require a choice.
export function mergeOcrDraft(current:DraftForm,fields:Record<string,unknown>){
  const incoming=prefillOcrDraft(fields),merged={...current},conflicts:OcrFieldConflict[]=[]
  for(const field of Object.keys(draftLabels) as (keyof DraftForm)[]){
    const value=incoming[field]
    if(!value.trim())continue
    if(!current[field]?.trim())merged[field]=value
    else if(current[field]!==value)conflicts.push({field,incoming:value})
  }
  return {merged,incoming,conflicts}
}
export function evidenceText(value:unknown):string{
  const record=(input:unknown):Record<string,unknown>|undefined=>typeof input==='object'&&input!==null&&!Array.isArray(input)?input as Record<string,unknown>:undefined
  const entries=Array.isArray(value)?value:[value]
  return entries.map(entry=>{
    const source=record(entry);if(!source)return ''
    const text=typeof source.text==='string'?`原文“${source.text}”`:''
    const line=typeof source.line==='number'?`行 ${source.line+1}`:''
    const box=record(source.location),probability=record(source.probability),label=record(source.labelEvidence)
    const coordinates=box?['left','top','width','height'].filter(key=>typeof box[key]==='number').map(key=>`${key}=${box[key]}`).join(', '):'供应商未返回坐标'
    const confidence=probability&&typeof probability.average==='number'?`供应商该行平均置信度 ${probability.average}（不代表字段映射置信度）`:'供应商未返回置信度'
    return [line,text,coordinates,confidence,label&&typeof label.text==='string'?`标签“${label.text}”`:''].filter(Boolean).join(' · ')
  }).filter(Boolean).join('；')||'未识别 / 待人工填写'
}
export const ocrErrors:Record<string,string>={BAIDU_LOCAL_BUDGET:'本机设定的 OCR 调用上限已用完，不表示百度账户额度耗尽；请管理员确认调用预算',BAIDU_AUTH:'百度鉴权或接口授权失败，请管理员检查新凭据',BAIDU_QUOTA:'百度额度不足，请管理员检查额度',BAIDU_RATE_LIMIT:'百度请求限流，可稍后明确重试',BAIDU_TIMEOUT_POSSIBLY_CHARGED:'百度请求超时，可能已消耗额度',BAIDU_NETWORK_POSSIBLY_CHARGED:'网络异常，可能已消耗额度',BAIDU_LEASE_POSSIBLY_CHARGED:'任务租约过期，可能已消耗额度；系统没有自动重试',BAIDU_EMPTY_RESULT:'没有识别到文字，请检查原图',BAIDU_IMAGE_FORMAT:'百度模式仅支持 JPEG/PNG，不支持 PDF',BAIDU_IMAGE_DAMAGED:'图片损坏或不完整',BAIDU_IMAGE_BLANK:'图片为空白',BAIDU_IMAGE_DIMENSIONS:'图片边长须为 15–4096 像素',BAIDU_ENCODED_SIZE:'图片编码后的请求超过 4 MiB，请选用合适原图',SOURCE_ACCESS_CHANGED:'附件或案件状态、创建者权限已变化，结果未发布',SOURCE_HASH_MISMATCH:'原附件完整性校验失败',BAIDU_SYSTEM:'百度供应商系统故障',PROVIDER_OR_STORAGE_FAILURE:'供应商或私有存储不可用'}
