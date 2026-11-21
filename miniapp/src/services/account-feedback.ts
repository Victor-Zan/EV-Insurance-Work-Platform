import { ref } from 'vue'
import { onHide, onShow, onUnload } from '@dcloudio/uni-app'
import { authenticationBusy, cancelAuthentication } from './auth'
import { RequestError } from './client'

export function useAccountFeedback() {
  const error = ref(''), remaining = ref(0)
  let until = 0, timer: ReturnType<typeof setInterval> | undefined
  function stop() { if (timer) clearInterval(timer); timer = undefined }
  function tick() { remaining.value = Math.max(0, Math.ceil((until - Date.now()) / 1000)); if (!remaining.value) stop() }
  function resume() { stop(); tick(); if (remaining.value) timer = setInterval(tick, 1000) }
  function failed(cause: unknown) {
    error.value = cause instanceof Error ? cause.message : '操作未完成，请稍后重试。'
    if (cause instanceof RequestError && cause.retryAfter) { until = Date.now() + cause.retryAfter * 1000; resume() }
  }
  onHide(stop); onShow(resume); onUnload(() => { stop(); cancelAuthentication() })
  return { error, remaining, busy: authenticationBusy, failed }
}
