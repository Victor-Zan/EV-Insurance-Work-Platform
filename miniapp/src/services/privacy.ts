import { shallowRef } from 'vue'
import { RequestError } from './client'
import { onSessionCleared, sameSession, sessionGeneration } from './session'

export const privacyButtonId = 'miniapp-privacy-agree'
export const privacyPrompt = shallowRef<{ name: string; error: string; submitting: boolean; canAgree: boolean } | null>(null)
type PrivacyResolve = (result: { event: 'agree' | 'disagree' | 'exposureAuthorization'; buttonId?: string }) => void
// The installed DCloud type incorrectly describes the listener argument as GeneralCallbackResult.
// Its bundled API documentation and the native contract describe a resolver function instead.
type PrivacyApi = {
  getPrivacySetting?: (options: { success: (value: { needAuthorization: boolean; privacyContractName: string }) => void; fail: () => void }) => void
  onNeedPrivacyAuthorization?: (listener: (resolve: PrivacyResolve) => void) => void
  requirePrivacyAuthorize?: (options: { success: () => void; fail: () => void }) => void
  openPrivacyContract?: (options: { success: () => void; fail: () => void }) => void
}
const hosts = new Set<symbol>()
let installed = false, inFlight: Promise<void> | null = null, abort: ((code: string) => void) | undefined, nativeResolve: PrivacyResolve | undefined, policyName = ''
export function cancelPrivacy(code = 'PRIVACY_CANCELLED') {
  const resolve = nativeResolve; nativeResolve = undefined
  try { resolve?.({ event: 'disagree' }) } catch { /* A closed native prompt must not prevent session cleanup. */ }
  finally { abort?.(code); privacyPrompt.value = null }
}
onSessionCleared(() => cancelPrivacy())
export function privacyHost(host: symbol, active: boolean) { if (active) hosts.add(host); else hosts.delete(host); if (!hosts.size) cancelPrivacy() }
function api() { return uni as unknown as PrivacyApi }
function install() {
  if (installed) return
  if (typeof api().onNeedPrivacyAuthorization !== 'function') throw new RequestError('PRIVACY_UNAVAILABLE', '请升级微信后再选择图片。')
  api().onNeedPrivacyAuthorization!(resolve => {
    if (typeof resolve !== 'function') { abort?.('PRIVACY_UNAVAILABLE'); return }
    if (!hosts.size || !abort || !policyName) { resolve({ event: 'disagree' }); return }
    nativeResolve = resolve; privacyPrompt.value = { name: policyName, error: '', submitting: false, canAgree: true }
  })
  installed = true
}
export function privacyExposed() { if (privacyPrompt.value) { try { nativeResolve?.({ event: 'exposureAuthorization' }) } catch { cancelPrivacy('PRIVACY_UNAVAILABLE') } } }
export function agreePrivacy() {
  if (!privacyPrompt.value?.canAgree || privacyPrompt.value.submitting || !nativeResolve) return
  privacyPrompt.value = { ...privacyPrompt.value, submitting: true }
  // Only the native agreePrivacyAuthorization event calls this; the platform verifies buttonId.
  try { nativeResolve({ event: 'agree', buttonId: privacyButtonId }) } catch { cancelPrivacy('PRIVACY_UNAVAILABLE') }
}
export function viewPrivacy() {
  if (!privacyPrompt.value || privacyPrompt.value.submitting) return
  const resolver = nativeResolve, stamp = sessionGeneration()
  const current = () => privacyPrompt.value && nativeResolve === resolver && sameSession(stamp)
  if (typeof api().openPrivacyContract !== 'function') { privacyPrompt.value = { ...privacyPrompt.value, error: '暂时无法打开隐私指引，请稍后再试。', canAgree: false }; return }
  api().openPrivacyContract!({ success: () => { if (current() && privacyPrompt.value) privacyPrompt.value = { ...privacyPrompt.value, error: '', canAgree: true } }, fail: () => { if (current() && privacyPrompt.value) privacyPrompt.value = { ...privacyPrompt.value, error: '暂时无法打开隐私指引，请稍后再试。', canAgree: false } } })
}
export function ensureImagePrivacy(): Promise<void> {
  // #ifdef MP-WEIXIN
  if (inFlight) return inFlight
  const stamp = sessionGeneration()
  const promise = new Promise<void>((resolve, reject) => {
    let done = false
    const finish = (error?: RequestError) => { if (done) return; done = true; abort = undefined; nativeResolve = undefined; privacyPrompt.value = null; policyName = ''; error ? reject(error) : resolve() }
    abort = code => finish(new RequestError(code, code === 'PRIVACY_DECLINED' ? '你已取消图片授权，本次未选择或上传图片。' : '图片操作已取消，请在当前账号重新选择。'))
    try {
      if (!hosts.size || !sameSession(stamp)) { abort('PRIVACY_CANCELLED'); return }
      install()
      if (typeof api().getPrivacySetting !== 'function' || typeof api().requirePrivacyAuthorize !== 'function') throw new RequestError('PRIVACY_UNAVAILABLE', '请升级微信后再选择图片。')
      api().getPrivacySetting!({ success: value => {
        if (done) return
        if (!hosts.size || !sameSession(stamp)) { abort?.('PRIVACY_CANCELLED'); return }
        if (typeof value.needAuthorization !== 'boolean' || typeof value.privacyContractName !== 'string' || !value.privacyContractName.trim()) { finish(new RequestError('PRIVACY_UNCONFIGURED', '图片服务的隐私指引尚未配置，请稍后再试。')); return }
        policyName = value.privacyContractName
        if (!value.needAuthorization) { finish(); return }
        api().requirePrivacyAuthorize!({ success: () => { if (!done) finish(hosts.size && sameSession(stamp) ? undefined : new RequestError('PRIVACY_CANCELLED', '图片操作已取消。')) }, fail: () => finish(new RequestError('PRIVACY_DECLINED', '你已取消图片授权，本次未选择或上传图片。')) })
      }, fail: () => finish(new RequestError('PRIVACY_UNAVAILABLE', '暂时无法确认隐私授权，请稍后再试。')) })
    } catch (cause) { finish(cause instanceof RequestError ? cause : new RequestError('PRIVACY_UNAVAILABLE', '暂时无法确认隐私授权，请稍后再试。')) }
  })
  inFlight = promise
  void promise.then(() => { if (inFlight === promise) inFlight = null }, () => { if (inFlight === promise) inFlight = null })
  return promise
  // #endif
  // #ifndef MP-WEIXIN
  return Promise.resolve()
  // #endif
}
