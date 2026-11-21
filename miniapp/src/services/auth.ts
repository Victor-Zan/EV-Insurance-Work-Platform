import { ref } from 'vue'
import { acceptSession, clearSession, currentSession, isRecord, sameSession, sessionGeneration, updateCurrentUser } from './session'
import { apiBase, cancelRequests, RequestError, request } from './client'
import { isLocalDemo, isNativePreview, demoLogin, demoSession, type DemoRole } from '@local-preview'
export { clearSession, currentSession, session } from './session'
export type { BusinessRole } from './session'

export const authenticationBusy = ref(false)
export function enterNativePreview(role: DemoRole = 'OWNER') {
  if (!isNativePreview) return
  acceptSession(demoSession(role), sessionGeneration())
}
let attempt = 0
export function cancelAuthentication() { attempt++; cancelRequests('authentication') }
async function freshCode() {
  // #ifndef MP-WEIXIN
  throw new RequestError('WECHAT_ONLY', '请在微信小程序中完成微信登录与绑定。')
  // #endif
  // #ifdef MP-WEIXIN
  return new Promise<string>((resolve, reject) => uni.login({ provider: 'weixin',
    success: result => result.code ? resolve(result.code) : reject(new RequestError('WECHAT_CODE_INVALID', '未能获取微信登录凭证，请重新点击。')),
    fail: () => reject(new RequestError('WECHAT_CODE_INVALID', '微信登录未完成，请重新点击。')),
  }))
  // #endif
}
async function authenticate(path: string, body: Record<string, unknown> = {}) {
  if (authenticationBusy.value) throw new RequestError('BUSY', '正在办理，请稍候。')
  apiBase()
  const ticket = ++attempt, stamp = sessionGeneration()
  authenticationBusy.value = true
  try {
    const code = await freshCode()
    if (ticket !== attempt || !sameSession(stamp)) throw new RequestError('CANCELLED', '本次登录已取消。')
    const data = await request<unknown>(path, { method: 'POST', data: { ...body, code }, authenticated: false, scope: 'authentication' })
    if (ticket !== attempt || !sameSession(stamp)) throw new RequestError('CANCELLED', '本次登录已取消。')
    acceptSession(data, stamp)
  } finally { authenticationBusy.value = false }
}
export function loginWithWeChat() { return authenticate('/auth/wechat/login') }
export async function loginWithDemo(role: DemoRole) {
  if (!isLocalDemo || authenticationBusy.value) throw new RequestError('DEMO_DISABLED', '本机演示未启用。')
  const ticket = ++attempt, stamp = sessionGeneration(); authenticationBusy.value = true
  try { const value = await demoLogin(role); if (ticket !== attempt || !sameSession(stamp)) throw new RequestError('CANCELLED', '本次演示登录已取消。'); acceptSession(value, stamp) }
  finally { authenticationBusy.value = false }
}
export function validPassword(password: string) {
  let bytes = 0
  for (const char of password) { const value = char.codePointAt(0)!; bytes += value <= 0x7f ? 1 : value <= 0x7ff ? 2 : value <= 0xffff ? 3 : 4 }
  return password.trim().length > 0 && bytes <= 72
}
export function bindExisting(username: string, password: string, confirmed: boolean) {
  if (!username.trim() || username.length > 64 || !validPassword(password) || !confirmed)
    return Promise.reject(new RequestError('VALIDATION_ERROR', '请填写账号、有效密码并确认绑定；密码最多72个UTF-8字节。'))
  return authenticate('/auth/wechat/bindings', { username: username.trim(), password, confirmed: true })
}
export type InvitationPreview = { displayNameMasked: string; usernameMasked: string; role: 'OWNER' | 'REPAIR_SHOP'; shopNameMasked?: string; expiresAt: string }
export async function inspectInvitation(token: string): Promise<InvitationPreview> {
  if (!/^[A-Za-z0-9_-]{43}$/.test(token)) throw new RequestError('INVITATION_INVALID', '请填写工作人员提供的有效邀请凭证。')
  const result = await request<unknown>('/auth/wechat/activation/inspect', { method: 'POST', data: { token }, authenticated: false, scope: 'authentication' })
  if (!isRecord(result) || typeof result.displayNameMasked !== 'string' || typeof result.usernameMasked !== 'string'
    || ['OWNER', 'REPAIR_SHOP'].indexOf(String(result.role)) < 0 || typeof result.expiresAt !== 'string'
    || !Number.isFinite(Date.parse(result.expiresAt)) || Date.parse(result.expiresAt) <= Date.now())
    throw new RequestError('INVITATION_INVALID', '邀请身份或有效期异常，请联系工作人员核实。')
  return result as InvitationPreview
}
export function activateInvitation(token: string, password: string, confirmPassword: string, confirmed: boolean) {
  if (!/^[A-Za-z0-9_-]{43}$/.test(token) || !validPassword(password) || password !== confirmPassword || !confirmed)
    return Promise.reject(new RequestError('VALIDATION_ERROR', '请核对邀请与两次密码，并确认激活绑定；密码最多72个UTF-8字节。'))
  return authenticate('/auth/wechat/activation/complete', { token, password, confirmPassword, confirmed: true })
}
let validation: { stamp: number; promise: Promise<void> } | undefined
export function revalidateSession(): Promise<void> {
  if (!currentSession()) return Promise.reject(new RequestError('NO_SESSION', '请先登录。', 401))
  const stamp = sessionGeneration()
  if (validation?.stamp === stamp) return validation.promise
  const promise = (async () => {
    try { updateCurrentUser(await request<unknown>('/auth/me'), stamp) }
    finally { if (validation?.stamp === stamp) validation = undefined }
  })()
  validation = { stamp, promise }; return promise
}
let redirecting = false
export function returnToLogin() {
  if (redirecting) return
  redirecting = true
  uni.reLaunch({ url: '/pages/login/index', complete: () => { redirecting = false } })
}
export function logout() { cancelAuthentication(); clearSession(); returnToLogin() }
let entryInvitation = ''
export function holdInvitationEntry(value: string) { entryInvitation = value.slice(0, 128) }
export function takeInvitationEntry() { const value = entryInvitation; entryInvitation = ''; return value }
