export async function decodeStartupImage(image: HTMLImageElement): Promise<void> {
  if (!image.complete) {
    await new Promise<void>((resolve, reject) => {
      const cleanup = () => { image.removeEventListener('load', loaded); image.removeEventListener('error', failed) }
      const loaded = () => { cleanup(); resolve() }
      const failed = () => { cleanup(); reject(new Error('图片加载失败')) }
      image.addEventListener('load', loaded, { once: true })
      image.addEventListener('error', failed, { once: true })
    })
  }
  if (!image.naturalWidth) throw new Error('图片不可用')
  await image.decode()
}

export async function prepareStartupImages(images: HTMLImageElement[], progress: (value: number) => void) {
  let completed = 0
  await Promise.all(images.map(async image => {
    await decodeStartupImage(image)
    progress(50 + Math.floor(++completed / images.length * 40))
  }))
  progress(90)
}

export function nextPaint(): Promise<void> {
  return new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
}
