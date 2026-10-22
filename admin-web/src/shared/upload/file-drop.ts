// Transport only: the native change handler remains the business boundary.
export function isFileDrag(event: DragEvent): boolean {
  return Array.from(event.dataTransfer?.types ?? []).includes('Files')
}

export function selectionError(files: File[], input: HTMLInputElement, maxBytes?: number): string {
  if (!input.multiple && files.length > 1) return '此入口一次只能选择一个文件，请分别处理。'
  const accepted = input.accept.toLowerCase().split(',').map(value => value.trim()).filter(Boolean)
  const errors = files.flatMap(file => {
    const reasons: string[] = []
    if (file.size === 0) reasons.push('空文件')
    if (maxBytes !== undefined && file.size > maxBytes) reasons.push(`超过 ${maxBytes / 1048576} MiB`)
    if (accepted.length && !accepted.some(type => type.startsWith('.') ? file.name.toLowerCase().endsWith(type) : type.endsWith('/*') ? file.type.startsWith(type.slice(0, -1)) : file.type === type)) reasons.push('不支持的文件类型')
    return reasons.length ? [`${file.name}：${reasons.join('、')}`] : []
  })
  return errors.join('；')
}

export function droppedFiles(transfer: DataTransfer): File[] {
  const items = Array.from(transfer.items ?? [])
  for (const item of items) {
    if (item.kind !== 'file') continue
    const entry = item.webkitGetAsEntry?.()
    // Never traverse directories or lose their relative paths.
    if (entry?.isDirectory || !item.getAsFile()) throw Error('不支持拖入文件夹，请使用原“选择文件夹”入口；普通上传区请逐个选择文件。')
    if (!entry && !item.webkitGetAsEntry) throw Error('浏览器无法安全确认拖入内容，请使用“点击选择文件”或原“选择文件夹”入口。')
  }
  return Array.from(transfer.files)
}

export function deliverFiles(input: HTMLInputElement, files: File[], transfer: DataTransfer): void {
  if (input.disabled || !files.length) return
  files.forEach(file => transfer.items.add(file))
  input.files = transfer.files
  input.dispatchEvent(new Event('change', { bubbles: true }))
}

export function preventFileNavigation(target: Document): void {
  target.addEventListener('dragover', event => { if (isFileDrag(event)) event.preventDefault() })
  target.addEventListener('drop', event => {
    if (!isFileDrag(event)) return
    event.preventDefault()
    target.dispatchEvent(new Event('file-drop-reset'))
  })
}
