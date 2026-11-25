import { clearSession, currentSession, isRecord, onSessionCleared, sameSession, sessionGeneration } from './session'
import { isLocalDemo, demoRequest } from '@local-preview'
import { RequestError } from './request-error'
export { RequestError } from './request-error'

const messages: Record<string, string> = {
  WECHAT_BINDING_REQUIRED: '微信身份已变化，请重新点击微信登录。',
  WECHAT_IDENTITY_REVOKED: '此微信的服务身份已解除，请联系工作人员核实。',
  INVALID_CREDENTIALS: '账号或密码不正确，或账号尚未开通，请核实后重试。',
  INVITATION_INVALID: '邀请不可用、已过期或已办理，请联系邀请人员核实。',
  WECHAT_BINDING_CONFLICT: '微信或服务账号已有其他绑定，请联系工作人员核实。',
  WECHAT_IDENTITY_CONFLICT: '账号身份或网点归属需要核实，请联系工作人员。',
  WECHAT_CODE_INVALID: '微信登录凭证已失效，请重新点击办理。',
  WECHAT_UNAVAILABLE: '微信登录服务尚未开通或暂不可用，请稍后重试。',
  WECHAT_PROVIDER_ERROR: '微信登录暂未完成，请稍后重新点击办理。',
  VALIDATION_ERROR: '填写内容不符合要求，请核对后重试。',
}
export function apiBase() {
  if (isLocalDemo) return 'https://local-demo.invalid/api/v1'
  const configured = import.meta.env.VITE_API_BASE_URL?.trim()
  const legacy = import.meta.env.VITE_WECHAT_LOGIN_URL?.trim()
  const value = configured || (legacy?.endsWith('/auth/wechat/login') ? legacy.slice(0, -'/auth/wechat/login'.length) : '')
  if (!value) throw new RequestError('NO_ENDPOINT', '服务尚未接入，请联系工作人员。')
  const base = value.replace(/\/$/, '')
  if (!/^https:\/\/[A-Za-z0-9.-]+(?::[0-9]{1,5})?(?:\/[A-Za-z0-9_~-]+)*\/api\/v1$/.test(base))
    throw new RequestError('CONFIGURATION', '服务地址配置异常，请联系工作人员。')
  return base
}
export function serviceConfigured() { try { apiBase(); return true } catch { return false } }
export function apiUrl(path: string) {
  if (!/^\/[A-Za-z0-9_~/-]*$/.test(path) || path.includes('//') || path.split('/').indexOf('..') >= 0)
    throw new RequestError('INVALID_PATH', '请求地址无效。')
  return apiBase() + path
}
type Task = { abort: () => void }
type Pending = { scope: string; cancel: () => void }
const pending = new Set<Pending>()
const cooldown = new Map<string, number>()
export function cancelRequests(scope?: string) { [...pending].filter(item => !scope || item.scope === scope).forEach(item => item.cancel()) }
onSessionCleared(() => cancelRequests())
export function trackTask(task: Task, cancel: () => void, scope = 'session') {
  const item = { scope, cancel: () => { pending.delete(item); cancel(); task.abort() } }
  pending.add(item)
  return () => { pending.delete(item) }
}
function retrySeconds(headers?: Record<string, unknown>) {
  const key = Object.keys(headers ?? {}).find(name => name.toLowerCase() === 'retry-after')
  const seconds = key ? Number(headers![key]) : NaN
  return Number.isFinite(seconds) && seconds > 0 ? Math.min(3600, Math.ceil(seconds)) : 600
}
export function decodeResponse<T>(status: number, body: unknown, headers: Record<string, unknown> | undefined, authenticated: boolean, stamp: number): T {
  if (!sameSession(stamp)) throw new RequestError('STALE_SESSION', '账号已变化，本次结果已丢弃。')
  if (status >= 200 && status < 300 && isRecord(body) && body.code === 'SUCCESS') return body.data as T
  const code = isRecord(body) && typeof body.code === 'string' ? body.code : 'INVALID_RESPONSE'
  if (status === 401 && authenticated) { clearSession(); throw new RequestError(code, '登录已失效，请重新登录。', status) }
  if (status === 429) throw new RequestError(code, '操作较频繁，请稍后再试。', status, retrySeconds(headers))
  if (messages[code]) throw new RequestError(code, messages[code], status)
  if (status === 403) throw new RequestError(code, '当前账号无权执行此操作，请联系工作人员。', status)
  if (status === 409) throw new RequestError(code, '信息已经变化，请刷新并核对后重新操作。', status)
  throw new RequestError(code, status >= 500 ? '服务暂不可用，请稍后重试。' : '未能确认服务返回结果，请重试或联系工作人员。', status)
}
export type RequestOptions = { method?: 'GET' | 'POST' | 'PUT' | 'DELETE'; data?: Record<string, unknown>; authenticated?: boolean; idempotencyKey?: string; scope?: string }
export function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  let url: string
  try { url = apiUrl(path) } catch (error) { return Promise.reject(error) }
  const authenticated = options.authenticated !== false
  const active = currentSession(), stamp = sessionGeneration()
  if (authenticated && !active) return Promise.reject(new RequestError('NO_SESSION', '请先登录。', 401))
  if (isLocalDemo) return demoRequest(path, options).then(response => decodeResponse<T>(response.statusCode, response.data, undefined, authenticated, stamp))
  const remaining = Math.ceil(((cooldown.get(path) ?? 0) - Date.now()) / 1000)
  if (remaining > 0) return Promise.reject(new RequestError('ACCOUNT_RATE_LIMIT', '操作较频繁，请稍后再试。', 429, remaining))
  return new Promise((resolve, reject) => {
    let settled = false, release = () => {}
    const finish = (action: () => void) => { if (settled) return; settled = true; release(); action() }
    const task = uni.request({ url, method: options.method ?? 'GET', data: options.data, timeout: 15000,
      header: { 'Content-Type': 'application/json', ...(authenticated ? { Authorization: `Bearer ${active!.accessToken}` } : {}),
        ...(options.idempotencyKey ? { 'Idempotency-Key': options.idempotencyKey } : {}) },
      success: response => finish(() => {
        try { resolve(decodeResponse<T>(response.statusCode, response.data, response.header, authenticated, stamp)) }
        catch (error) { if (error instanceof RequestError && error.retryAfter) cooldown.set(path, Date.now() + error.retryAfter * 1000); reject(error) }
      }),
      fail: () => finish(() => reject(new RequestError(sameSession(stamp) ? 'NETWORK' : 'STALE_SESSION',
        '网络连接未完成；写入结果可能尚未确认，请保留原操作后重试。'))),
    })
    if (!settled) release = trackTask(task, () => finish(() => reject(new RequestError('CANCELLED', '本次请求已取消。'))), options.scope)
  })
}
let commandCounter = 0
export function commandKey() { return `mini-${Date.now().toString(36)}-${++commandCounter}-${Math.random().toString(36).slice(2, 14)}` }
export function createCommand(path: string, data: Record<string, unknown>) {
  const stamp = sessionGeneration(), payload = JSON.stringify(data), key = commandKey()
  let inFlight: Promise<unknown> | null = null
  let conflict = false
  return { key, execute<T>() {
    if (!sameSession(stamp)) return Promise.reject(new RequestError('STALE_SESSION', '账号已变化，请重新办理。')) as Promise<T>
    if (conflict) return Promise.reject(new RequestError('COMMAND_CONFLICT', '信息已变化，请刷新核对后创建新操作。', 409)) as Promise<T>
    if (!inFlight) inFlight = (async () => {
      try { return await request<T>(path, { method: 'POST', data: JSON.parse(payload), idempotencyKey: key }) }
      catch (error) { if (error instanceof RequestError && error.status === 409) conflict = true; throw error }
      finally { inFlight = null }
    })()
    return inFlight as Promise<T>
  } }
}
