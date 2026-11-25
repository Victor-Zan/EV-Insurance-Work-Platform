import { createCommand, apiUrl, commandKey, decodeResponse, request, RequestError, trackTask } from './client'
import { currentSession, onSessionCleared, sameSession, sessionGeneration } from './session'
import { assignment, id, integer, parsePage, rawQuoteCommand, quoteEditable, record, requireShop, string, validateLines, type QuoteContext, type QuoteLine, type ShopCase, type ShopCommand } from './shop'
import { money, quantity } from './quote-money'
import type { UploadFile } from './upload'
import { isLocalDemo } from '@local-preview'
export type OcrItem = { description: string; quantity: string; unitPrice: string; unit: string; lineAmount: string; evidence: string }
export type OcrProgram = { items: OcrItem[]; warnings: string[]; lines: string[]; total: string }
export type OcrJob = { id: string; state: string; provider: string; reviewVersion: number; confirmed: boolean; rerunsUsed: number; pageNo: number; errorCode: string }
export const ocrPending = new Map<string, ShopCommand<OcrJob>>()
onSessionCleared(() => ocrPending.clear())
function base(caseId: string) { requireShop(); return `/quotations/cases/${id(caseId)}/ocr` }
export function parseJob(value: unknown): OcrJob {
  const row = record(value)
  if (['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED'].indexOf(string(row.state)) < 0) throw new RequestError('INVALID_RESPONSE', '识别状态需要核对。')
  return { id: id(row.id), state: string(row.state), provider: string(row.provider), reviewVersion: integer(row.reviewVersion), confirmed: row.confirmed === true,
    rerunsUsed: integer(row.rerunsUsed), pageNo: integer(row.pageNo, 1), errorCode: string(row.errorCode) }
}
function program(value: unknown): OcrProgram {
  const row = record(value), fields = row.fields == null ? {} : record(row.fields)
  if (row.items != null && (!Array.isArray(row.items) || row.items.length > 200)) throw new RequestError('INVALID_RESPONSE', '识别明细过多，请手工核对。')
  return { items: Array.isArray(row.items) ? row.items.map(value => { const item = record(value); return { description: string(item.description), quantity: string(item.quantity), unitPrice: string(item.unitPrice), unit: string(item.unit), lineAmount: string(item.lineAmount), evidence: item.evidence == null ? '' : JSON.stringify(item.evidence) } }) : [],
    warnings: Array.isArray(row.warnings) ? row.warnings.filter((item): item is string => typeof item === 'string') : [],
    lines: Array.isArray(row.lines) ? row.lines.map(item => string(record(item).text)) : [], total: string(fields.amountCandidate ?? row.amountCandidate) }
}
export const ocrApi = {
  configuration: async (caseId: string, scope: string) => { const row = record(await request(`${base(caseId)}/configuration`, { scope })); return string(row.provider) },
  list: async (caseId: string, version: number, page: number, scope: string) => { integer(version, 1); return parsePage(await request(base(caseId), { scope, data: { assignmentVersion: version, page, size: 20 } }), parseJob) },
  get: async (caseId: string, jobId: string, scope: string) => parseJob(await request(`${base(caseId)}/${id(jobId)}`, { scope })),
  program: async (caseId: string, jobId: string, scope: string) => program(await request(`${base(caseId)}/${id(jobId)}/program`, { scope })),
  raw: async (caseId: string, jobId: string, scope: string) => string(record(await request(`${base(caseId)}/${id(jobId)}/raw`, { scope })).text),
}
export function historicalMock(job: OcrJob, provider: string) { return job.provider === 'MOCK_V1' && provider !== '' && provider !== 'MOCK_V1' }
export function sourceLabel(job: OcrJob, provider: string) { return job.provider === 'MOCK_V1' ? historicalMock(job, provider) ? '历史模拟结果 · 非真实识别' : '模拟识别结果 · 非真实识别' : job.provider === 'BAIDU_HANDWRITING_V1' ? '百度手写识别' : '清单识别结果' }
const errors: Record<string, string> = { BAIDU_QUOTA: '识别额度不足，可继续手填。', BAIDU_LOCAL_BUDGET: '本机识别授权次数已用完，可继续手填。', BAIDU_AUTH: '识别服务权限异常，请联系工作人员。', BAIDU_RATE_LIMIT: '识别请求较多，请稍后明确重试。', BAIDU_EMPTY_RESULT: '未识别到可靠文字，请对照原图手填。', BAIDU_FORMAT: '图片格式不符合识别要求。', BAIDU_TIMEOUT_POSSIBLY_CHARGED: '识别超时，可能已消耗额度，不会自动重识别。', BAIDU_NETWORK_POSSIBLY_CHARGED: '识别连接异常，可能已消耗额度。', SOURCE_ACCESS_CHANGED: '案件、派单或原件已变化，不能采用旧结果。' }
export const ocrError = (code: string) => errors[code] ?? '识别未完成，表单已保留，可手工填写。'
export function ocrRetryCommand(caseId: string, job: OcrJob): ShopCommand<OcrJob> {
  requireShop()
  if (job.rerunsUsed >= 3 || ['QUEUED', 'RUNNING'].indexOf(job.state) >= 0) throw new RequestError('OCR_RETRY_LIMIT', '处理中不可重新识别；首次之外最多重新识别3次。', 409)
  const command = createCommand(`${base(caseId)}/${id(job.id)}/retry`, { expectedVersion: job.reviewVersion })
  return { execute: async () => { requireShop(); const result = parseJob(await command.execute()); if (result.id !== job.id) throw new RequestError('INVALID_RESPONSE', '识别任务不匹配。'); return result } }
}
export function ocrUploadCommand(row: ShopCase, file: UploadFile, pageNo: number, category: 'SHOP_QUOTE' | 'ASSESSMENT'): ShopCommand<OcrJob> {
  if (isLocalDemo) throw new RequestError('LOCAL_DEMO_OCR', '本机演示暂不模拟OCR服务，可继续手工填写报价；未发送图片。')
  const version = assignment(row, true), stamp = sessionGeneration(), key = commandKey(), source = { ...file }, endpoint = apiUrl(base(row.id))
  if (!source.path || !/\.(jpe?g|png)$/i.test(source.name) || ['image/jpeg', 'image/png'].indexOf(source.mimeType ?? '') < 0 || !source.size || source.size > 10485760 || pageNo < 1 || pageNo > 20 || !Number.isInteger(pageNo)) throw new RequestError('VALIDATION_ERROR', '请使用不超过10MiB的JPG/PNG原件，最多20张。')
  let inFlight: Promise<OcrJob> | null = null, conflict = false
  return { execute() {
    requireShop()
    if (!sameSession(stamp)) return Promise.reject(new RequestError('STALE_SESSION', '账号已变化。'))
    if (conflict) return Promise.reject(new RequestError('COMMAND_CONFLICT', '原件状态已变化，请刷新核对。', 409))
    if (!inFlight) inFlight = (async () => {
      try { return await new Promise<OcrJob>((resolve, reject) => {
        let done = false, release = () => {}
        const finish = (action: () => void) => { if (done) return; done = true; release(); action() }
        const task = uni.uploadFile({ url: endpoint, filePath: source.path, name: 'file', timeout: 30000,
          header: { Authorization: `Bearer ${currentSession()!.accessToken}`, 'Idempotency-Key': key }, formData: { assignmentVersion: String(version), pageNo: String(pageNo), category },
          success: response => finish(() => {
            try { const data = decodeResponse<unknown>(response.statusCode, JSON.parse(response.data), undefined, true, stamp); resolve(parseJob(data)) }
            catch (cause) { reject(cause instanceof RequestError ? cause : new RequestError('INVALID_RESPONSE', '上传识别结果尚未确认，请保留原命令重试。', 502)) }
          }), fail: () => finish(() => reject(new RequestError('NETWORK', '上传识别未确认，请保留原件并重试原操作。'))),
        })
        if (!done) release = trackTask(task, () => finish(() => reject(new RequestError('CANCELLED', '上传已取消。'))), `shop-ocr-upload:${row.id}`)
      }) } catch (cause) { if (cause instanceof RequestError && cause.status === 409) conflict = true; throw cause }
      finally { inFlight = null }
    })()
    return inFlight
  } }
}
export function adoptCandidate(lines: QuoteLine[], item: OcrItem, job: OcrJob, index: number, provider: string, target?: number): QuoteLine[] {
  requireShop()
  if (!provider || historicalMock(job, provider) || job.state !== 'SUCCEEDED' || index < 0 || index >= 200) throw new RequestError('OCR_SOURCE', '请采用当前有效识别结果。')
  if (lines.some((line, n) => n !== target && line.ocr?.jobId === job.id && line.ocr.itemIndex === index)) throw new RequestError('VALIDATION_ERROR', '此识别行已采用，请修改原行，勿重复计费。')
  const existing = target === undefined ? undefined : lines[target]
  if (target !== undefined && !existing) throw new RequestError('VALIDATION_ERROR', '替换目标已变化。')
  const recognizedQuantity = item.quantity.trim(), quantity = recognizedQuantity || existing?.quantity
  const row: QuoteLine = { description: item.description, quantity: quantity || '1', unitPrice: item.unitPrice, unit: item.unit, lineAmount: item.lineAmount, ocr: { jobId: job.id, itemIndex: index } }
  if (!recognizedQuantity && (!quantity || existing?.quantityDefaulted)) row.quantityDefaulted = true
  if (target !== undefined) return lines.map((line, n) => n === target ? row : line)
  return lines.length === 0 || lines.every(line => !line.description && !line.quantity && !line.unitPrice) ? [row] : [...lines, row]
}
export function preparedQuoteCommand(row: ShopCase, quote: QuoteContext, source: QuoteLine[], jobs: OcrJob[], provider: string): ShopCommand<QuoteContext> {
  assignment(row, true); validateLines(source)
  if (!quoteEditable(row, quote) || !quote.arrivalPhotoUploaded) throw new RequestError('QUOTE_STATE_CONFLICT', '请先上传本次派单到店照片并刷新报价阶段。', 409)
  const draft: QuoteLine[] = JSON.parse(JSON.stringify(source)), groups = new Map<string, QuoteLine[]>()
  draft.forEach(line => { line.quantity = String(quantity(line.quantity)); line.unitPrice = money(line.unitPrice); if (line.ocr) groups.set(line.ocr.jobId, [...(groups.get(line.ocr.jobId) ?? []), line]) })
  const commands = [...groups].map(([jobId, lines]) => {
    const job = jobs.find(job => job.id === jobId)
    if (!job || !provider || job.state !== 'SUCCEEDED' || historicalMock(job, provider)) throw new RequestError('OCR_SOURCE', '识别原件已变化，请重新核对或转为手填。')
    const command = createCommand(`${base(row.id)}/${id(jobId)}/apply`, { expectedVersion: job.reviewVersion, items: lines.map(line => ({ index: line.ocr!.itemIndex, description: line.description.trim(), quantity: line.quantity, unitPrice: line.unitPrice, unit: line.unit ?? '', lineAmount: line.lineAmount ?? '' })) })
    return { jobId, lines, command, completed: false }
  })
  let raw: ShopCommand<QuoteContext> | undefined
  return { execute: async () => {
    requireShop()
    for (const item of commands) {
      if (item.completed) continue
      const job = parseJob(await item.command.execute())
      if (job.id !== item.jobId || !job.confirmed) throw new RequestError('INVALID_RESPONSE', '人工核对快照尚未确认，请重试原操作。', 502)
      item.lines.forEach(line => { line.ocr!.revision = job.reviewVersion }); item.completed = true
    }
    if (!raw) raw = rawQuoteCommand(row, quote, draft)
    return raw.execute()
  } }
}
