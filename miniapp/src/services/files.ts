import { apiUrl, decodeResponse, RequestError, trackTask } from './client'
import { currentSession, onSessionCleared, sameSession, sessionGeneration } from './session'
import { isLocalDemo, demoFile } from '@local-preview'

const temporary = new Set<string>()
function discard(path: string) {
  temporary.delete(path)
  // #ifdef MP-WEIXIN
  uni.getFileSystemManager().unlink({ filePath: path, fail: () => {} })
  // #endif
  // #ifdef H5
  if (path.startsWith('blob:')) URL.revokeObjectURL(path)
  // #endif
}
onSessionCleared(() => { [...temporary].forEach(discard) })
export function releasePrivateFiles() { [...temporary].forEach(discard) }
export function releasePrivateFile(path: string) { if (temporary.has(path)) discard(path) }
export function downloadPrivateFile(fileId: string): Promise<string> {
  if (!/^[0-9a-f-]{36}$/i.test(fileId)) return Promise.reject(new RequestError('VALIDATION_ERROR', '文件标识无效。'))
  return downloadResource(`/materials/${fileId}/download`)
}
export function downloadQuoteOcrImage(caseId: string, jobId: string): Promise<string> {
  if (currentSession()?.user.role !== 'REPAIR_SHOP' || !/^[0-9a-f-]{36}$/i.test(caseId) || !/^[0-9a-f-]{36}$/i.test(jobId))
    return Promise.reject(new RequestError('FORBIDDEN', '无权查看此报价原件。', 403))
  return downloadResource(`/quotations/cases/${caseId}/ocr/${jobId}/image`)
}
function downloadResource(path: string): Promise<string> {
  const active = currentSession(), stamp = sessionGeneration()
  if (!active) return Promise.reject(new RequestError('NO_SESSION', '请先登录。', 401))
  if (isLocalDemo) return demoFile(path).then(value => { if (!sameSession(stamp)) { discard(value); throw new RequestError('STALE_SESSION', '账号已变化。') }; temporary.add(value); return value })
  let url: string
  try { url = apiUrl(path) } catch (error) { return Promise.reject(error) }
  return new Promise((resolve, reject) => {
    let settled = false, release = () => {}
    const finish = (action: () => void) => { if (settled) return; settled = true; release(); action() }
    const task = uni.downloadFile({ url, header: { Authorization: `Bearer ${active.accessToken}` }, timeout: 30000,
      success: response => {
        if (settled) { if (response.tempFilePath) discard(response.tempFilePath); return }
        if (!sameSession(stamp)) { if (response.tempFilePath) discard(response.tempFilePath); finish(() => reject(new RequestError('STALE_SESSION', '账号已变化，文件已移除。'))); return }
        finish(() => {
          try {
            if (response.statusCode !== 200 || !response.tempFilePath) {
              if (response.tempFilePath) discard(response.tempFilePath)
              decodeResponse(response.statusCode, null, undefined, true, stamp)
              throw new RequestError('INVALID_RESPONSE', '未取得有效文件，请重试。')
            }
            temporary.add(response.tempFilePath); resolve(response.tempFilePath)
          } catch (error) { reject(error) }
        })
      },
      fail: () => finish(() => reject(new RequestError('NETWORK', '文件下载未完成，请稍后重试。'))),
    })
    task.onProgressUpdate(progress => { if (progress.totalBytesWritten > 10 * 1024 * 1024) { finish(() => reject(new RequestError('FILE_LIMIT', '文件超过可预览大小。'))); task.abort() } })
    if (!settled) release = trackTask(task, () => finish(() => reject(new RequestError('CANCELLED', '账号已退出，文件下载已取消。'))))
  })
}
export async function previewPrivateImage(fileId: string) {
  const stamp = sessionGeneration(), path = await downloadPrivateFile(fileId)
  if (!sameSession(stamp)) { discard(path); throw new RequestError('STALE_SESSION', '账号已变化，已移除临时文件。') }
  try {
    await new Promise<void>((resolve, reject) => uni.previewImage({ urls: [path], current: path,
      success: () => resolve(), fail: () => reject(new RequestError('PREVIEW_FAILED', '此文件暂不能作为图片预览。')) }))
  } catch (error) { discard(path); throw error }
  // Native preview success means opened, not closed. Retain until page cleanup or logout.
}
