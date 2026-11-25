import { shallowRef } from 'vue'
import { apiUrl, cancelRequests, commandKey, decodeResponse, RequestError, trackTask } from './client'
import { currentSession, isRecord, onSessionCleared, sameSession, sessionGeneration } from './session'
import { isLocalDemo, demoUpload } from '@local-preview'

export type UploadFile = { path: string; name: string; mimeType?: string; size?: number }
export type UploadCommand = { readonly file: UploadFile; readonly idempotencyKey: string; readonly caseId?: string;
  readonly category?: 'ARRIVAL_PHOTO' | 'PROGRESS_PHOTO' | 'COMPLETION_PHOTO'; readonly assignmentVersion?: number; readonly stamp?: number }
export type UploadResult = { status: 'uploaded'; remoteId: string } | { status: 'failed'; code: string; message: string; retryable: boolean; retryAfter?: number }
export type UploadConfig = { endpoint?: string }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const failed = (code: string, message: string, retryable = false, retryAfter = 0): UploadResult => ({ status: 'failed', code, message, retryable, retryAfter })
export function createMaterialUpload(caseId: string, category: NonNullable<UploadCommand['category']>, assignmentVersion: number, file: UploadFile): UploadCommand {
  const active = currentSession()
  if (!active || active.user.role !== 'REPAIR_SHOP') throw new RequestError('FORBIDDEN', '仅维修网点可上传维修材料。', 403)
  if (!uuid.test(caseId) || ['ARRIVAL_PHOTO', 'PROGRESS_PHOTO', 'COMPLETION_PHOTO'].indexOf(category) < 0
    || !Number.isInteger(assignmentVersion) || assignmentVersion < 1 || !file.path
    || ['image/jpeg', 'image/png'].indexOf(file.mimeType ?? '') < 0 || !/\.(jpe?g|png)$/i.test(file.name)
    || (file.size != null && (!Number.isFinite(file.size) || file.size < 1 || file.size > 10 * 1024 * 1024)))
    throw new RequestError('VALIDATION_ERROR', '请使用有效案件、本次派单和不超过10MiB的JPG/PNG原图。')
  return Object.freeze({ file: Object.freeze({ ...file }), caseId, category, assignmentVersion, stamp: sessionGeneration(), idempotencyKey: commandKey() })
}
/** The unconfigured S01 call remains a local failure; real material uploads require a scoped immutable command. */
export async function uploadFile(command: UploadCommand, config: UploadConfig = {}): Promise<UploadResult> {
  if (!command.caseId) return failed('NO_ENDPOINT', '未配置上传服务；文件仅用于本机临时预览。')
  let endpoint: string
  try { endpoint = apiUrl(`/materials/cases/${command.caseId}`) } catch { return failed('NO_ENDPOINT', '材料服务尚未接入，未上传文件。') }
  if (config.endpoint && config.endpoint !== endpoint) return failed('INVALID_ENDPOINT', '上传地址与服务配置不一致。')
  const active = currentSession()
  if (!active || active.user.role !== 'REPAIR_SHOP' || command.stamp == null || !sameSession(command.stamp))
    return failed('NO_SESSION', '账号已变化或无上传权限，请重新核实。')
  if (!uuid.test(command.caseId) || !command.category || ['ARRIVAL_PHOTO', 'PROGRESS_PHOTO', 'COMPLETION_PHOTO'].indexOf(command.category) < 0
    || !Number.isInteger(command.assignmentVersion) || command.assignmentVersion! < 1 || !command.idempotencyKey || command.idempotencyKey.length > 128)
    return failed('VALIDATION_ERROR', '上传命令缺少有效派单和材料类别。')
  const stamp = command.stamp
  if (isLocalDemo) {
    try { return { status: 'uploaded', remoteId: await demoUpload(command, () => sameSession(stamp)) } }
    catch (error) { return failed('LOCAL_DEMO_UPLOAD', error instanceof Error ? error.message : '演示图片未能保存。') }
  }
  return new Promise(resolve => {
    let settled = false, release = () => {}
    const finish = (result: UploadResult) => { if (settled) return; settled = true; release(); resolve(result) }
    const task = uni.uploadFile({ url: endpoint, filePath: command.file.path, name: 'file', timeout: 30000,
      header: { Authorization: `Bearer ${active.accessToken}`, 'Idempotency-Key': command.idempotencyKey },
      formData: { category: command.category!, assignmentVersion: String(command.assignmentVersion) },
      success: response => {
        if (settled) return
        release()
        try {
          const body: unknown = typeof response.data === 'string' ? JSON.parse(response.data) : response.data
          const data = decodeResponse<unknown>(response.statusCode, body, undefined, true, stamp)
          if (!isRecord(data) || typeof data.id !== 'string' || !uuid.test(data.id))
            throw new RequestError('INVALID_RESPONSE', '上传结果尚未确认，请保留原命令重试。', 502)
          finish({ status: 'uploaded', remoteId: data.id })
        } catch (error) {
          const typed = error instanceof RequestError ? error : new RequestError('INVALID_RESPONSE', '上传结果尚未确认，请保留原命令重试。', 502)
          finish(failed(typed.code, typed.message, typed.status >= 500 || typed.status === 429, typed.retryAfter))
        }
      },
      fail: () => finish(failed(sameSession(stamp) ? 'NETWORK' : 'STALE_SESSION', '上传未能确认，请保留原文件和命令后重试。', sameSession(stamp))),
    })
    if (!settled) release = trackTask(task, () => finish(failed('CANCELLED', '账号已退出，本次上传已取消。')), `upload:${command.idempotencyKey}`)
  })
}
export type UploadEntry = { command: UploadCommand; state: 'waiting' | 'uploading' | 'uploaded' | 'failed'; result?: UploadResult; retryAt?: number }
export class MaterialUploadQueue {
  readonly entries = shallowRef<UploadEntry[]>([])
  private readonly stamp = sessionGeneration()
  private running = 0
  private disposed = false
  private readonly unsubscribe = onSessionCleared(() => { this.disposed = true; this.entries.value = [] })
  enqueue(commands: UploadCommand[]) {
    if (this.disposed || !sameSession(this.stamp) || commands.some(command => command.stamp !== this.stamp)) throw new RequestError('STALE_SESSION', '上传队列所属账号已变化。')
    const keys = new Set(this.entries.value.map(entry => entry.command.idempotencyKey))
    const additions: UploadEntry[] = []
    for (const command of commands) { if (!keys.has(command.idempotencyKey)) { keys.add(command.idempotencyKey); additions.push({ command, state: 'waiting' }) } }
    this.entries.value = [...this.entries.value, ...additions]; this.pump()
  }
  retry(key: string) {
    const entry = this.entries.value.find(item => item.command.idempotencyKey === key)
    if (this.disposed || !sameSession(this.stamp) || entry?.state !== 'failed' || entry.result?.status !== 'failed'
      || !entry.result.retryable || (entry.retryAt ?? 0) > Date.now()) return
    this.replace(key, { ...entry, state: 'waiting' }); this.pump()
  }
  private replace(key: string, entry: UploadEntry) { this.entries.value = this.entries.value.map(item => item.command.idempotencyKey === key ? entry : item) }
  private pump() {
    if (this.disposed || !sameSession(this.stamp)) return
    while (this.running < 2) {
      const entry = this.entries.value.find(item => item.state === 'waiting')
      if (!entry) return
      const key = entry.command.idempotencyKey
      this.running++; this.replace(key, { ...entry, state: 'uploading' })
      void (async () => {
        try {
          const result = await uploadFile(entry.command)
          if (this.disposed || !sameSession(this.stamp)) return
          this.replace(key, { ...entry, state: result.status === 'uploaded' ? 'uploaded' : 'failed', result,
            retryAt: result.status === 'failed' && result.retryAfter ? Date.now() + result.retryAfter * 1000 : undefined })
        } finally { this.running--; this.pump() }
      })()
    }
  }
  dispose() {
    this.disposed = true
    this.entries.value.forEach(entry => cancelRequests(`upload:${entry.command.idempotencyKey}`))
    this.entries.value = []; this.unsubscribe()
  }
}
