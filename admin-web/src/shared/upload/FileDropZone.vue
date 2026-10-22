<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { deliverFiles, droppedFiles, isFileDrag, selectionError } from './file-drop'

const props = defineProps<{ hint: string; busy?: boolean; disabled?: boolean; maxBytes?: number; folder?: boolean; error?: string; feedback?: string; status?: string; files?: File[] }>()
const root = ref<HTMLElement>(), active = ref(false), localError = ref(''), selected = ref<File[]>([])
let depth = 0
const input = () => root.value?.querySelector<HTMLInputElement>('input[type=file]')
const disabled = computed(() => props.busy || props.disabled)
const visibleFiles = computed(() => props.files ?? selected.value)
function reset() { depth = 0; active.value = false }
function keydown(event: KeyboardEvent) { if (event.key === 'Escape') reset() }
watch(disabled, value => { if (value) reset() })
function enter(event: DragEvent) {
  if (!isFileDrag(event)) return
  event.preventDefault()
  if (!disabled.value) { depth++; active.value = true }
}
function leave(event: DragEvent) {
  if (!isFileDrag(event)) return
  if (--depth <= 0) reset()
}
function over(event: DragEvent) {
  if (!isFileDrag(event)) return
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = disabled.value ? 'none' : 'copy'
}
function change(event: Event) {
  const control = event.target as HTMLInputElement
  if (control.type !== 'file') return
  const files = Array.from(control.files ?? [])
  if (!files.length) return
  const error = disabled.value || control.disabled ? '当前不可操作，请等待处理完成或检查权限。' : props.folder ? '' : selectionError(files, control, props.maxBytes)
  if (error) { localError.value = error; control.value = ''; event.stopImmediatePropagation(); return }
  selected.value = files; localError.value = ''
}
function drop(event: DragEvent) {
  if (!isFileDrag(event)) return
  event.preventDefault(); event.stopPropagation(); reset(); document.dispatchEvent(new Event('file-drop-reset'))
  if (disabled.value) { localError.value = '当前不可操作，请等待处理完成或检查权限。'; return }
  const control = input()
  if (!control || control.disabled || !event.dataTransfer) return
  try {
    if (props.folder) throw Error('文件夹拖入暂不支持，请使用下方原“选择文件夹”按钮；相对路径将由原入口保留。')
    const files = droppedFiles(event.dataTransfer)
    const error = selectionError(files, control, props.maxBytes)
    if (error) throw Error(error)
    if (typeof DataTransfer === 'undefined') throw Error('浏览器不支持文件拖入，请点击选择文件。')
    deliverFiles(control, files, new DataTransfer())
  } catch (error) { localError.value = error instanceof Error ? error.message : '无法拖入，请点击选择文件。' }
}
onMounted(() => {
  document.addEventListener('file-drop-reset', reset)
  window.addEventListener('dragend', reset); window.addEventListener('blur', reset)
  window.addEventListener('keydown', keydown)
})
onUnmounted(() => {
  document.removeEventListener('file-drop-reset', reset)
  window.removeEventListener('dragend', reset); window.removeEventListener('blur', reset)
  window.removeEventListener('keydown', keydown)
})
</script>
<template>
  <div ref="root" class="file-drop-zone" :class="{ 'is-dragging': active, 'is-disabled': disabled }" :aria-busy="busy || undefined" :aria-disabled="disabled || undefined" @dragenter="enter" @dragleave="leave" @dragover="over" @drop="drop" @change.capture="change">
    <p class="drop-prompt">{{ folder ? '文件夹请使用原选择入口（暂不支持拖入）' : '将文件拖到这里，或点击选择文件' }}</p>
    <p class="drop-hint">{{ hint }}</p>
    <button type="button" class="drop-picker" :disabled="disabled" @click="input()?.click()">{{ folder ? '选择文件夹' : '点击选择文件' }}</button>
    <slot />
    <p v-if="disabled" class="drop-hint">当前不可选择文件：处理中、已锁定或无操作权限。</p>
    <ul v-if="visibleFiles.length" class="drop-files" aria-live="polite">
      <li v-for="(file, index) in visibleFiles.slice(0, 20)" :key="index"><span>{{ file.webkitRelativePath || file.name }} · {{ file.size }} B</span><span>{{ busy ? '上传／处理中' : error && !files ? '失败（可按原入口重试）' : feedback || (status === 'success' ? '成功' : '已选择／待处理') }}</span></li>
      <li v-if="visibleFiles.length > 20">共 {{ visibleFiles.length }} 个文件，仅展开前 20 个；原处理流程仍校验整个批次。</li>
    </ul>
    <p v-if="feedback" class="drop-feedback" role="status">{{ feedback }}</p>
    <p v-if="localError" class="drop-error" role="alert">{{ localError }} 未处理本次批次，原有内容保留。</p>
    <p v-if="error" class="drop-error" role="alert">{{ error }}</p>
  </div>
</template>
<style scoped>
.file-drop-zone{box-sizing:border-box;min-width:0;padding:14px;margin:10px 0;border:2px dashed #aabfbd;border-radius:10px;background:#f8fbfa;transition:border-color .12s,background .12s}
.file-drop-zone.is-dragging{border-color:#146a63;background:#dff3ec;box-shadow:0 0 0 3px #146a6320}
.file-drop-zone.is-disabled{opacity:.65}.file-drop-zone:focus-within{outline:2px solid #146a63;outline-offset:2px}
.drop-prompt{margin:0 0 6px;font-weight:600;font-size:14px}.drop-hint,.drop-feedback{margin:6px 0;font-size:12px;overflow-wrap:anywhere;color:#465f5b}
.drop-picker{min-height:44px;padding:8px 12px;margin:6px 8px 6px 0;border:1px solid #819e98;border-radius:6px;background:#fff;color:#274f47;cursor:pointer}.drop-picker:disabled{cursor:not-allowed}
.drop-files{list-style:none;padding:0;margin:8px 0;font-size:12px}.drop-files li{display:flex;flex-wrap:wrap;justify-content:space-between;gap:6px;padding:5px 0;overflow-wrap:anywhere;border-top:1px solid #dce7e4}.drop-error{color:#a13030;font-size:13px;white-space:pre-wrap;overflow-wrap:anywhere}
</style>
