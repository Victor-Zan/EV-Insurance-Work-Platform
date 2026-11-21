import { RequestError } from './client'
import type { UploadFile } from './upload'
import { ensureImagePrivacy } from './privacy'
import { sameSession, sessionGeneration } from './session'
export async function pickOriginalImages(count: number): Promise<UploadFile[]> {
  const stamp = sessionGeneration()
  await ensureImagePrivacy()
  if (!sameSession(stamp)) throw new RequestError('PICK_CANCELLED', '账号已变化，请重新选择图片。')
  const chosen = await new Promise<UniApp.ChooseImageSuccessCallbackResult>((resolve, reject) => uni.chooseImage({ count, sizeType: ['original'], success: resolve, fail: () => reject(new RequestError('PICK_CANCELLED', '未选择图片。')) }))
  const paths = Array.isArray(chosen.tempFilePaths) ? chosen.tempFilePaths : [chosen.tempFilePaths]
  const files = Array.isArray(chosen.tempFiles) ? chosen.tempFiles : []
  return Promise.all(paths.map(async (path, index) => {
    const info = await new Promise<UniApp.GetImageInfoSuccessData>((resolve, reject) => uni.getImageInfo({ src: path, success: resolve, fail: () => reject(new RequestError('IMAGE_FORMAT', '无法确认图片格式，请选择JPG/PNG原图。')) }))
    const type = (info.type ?? '').toLowerCase()
    const mimeType = ['jpg', 'jpeg'].indexOf(type) >= 0 ? 'image/jpeg' : type === 'png' ? 'image/png' : ''
    const size = files[index]?.size
    if (!mimeType || !size || size > 10485760) throw new RequestError('IMAGE_FORMAT', '请使用不超过10MiB的JPG/PNG原图。')
    return { path, size, mimeType, name: `photo-${Date.now()}-${index}.${mimeType === 'image/png' ? 'png' : 'jpg'}` }
  }))
}
