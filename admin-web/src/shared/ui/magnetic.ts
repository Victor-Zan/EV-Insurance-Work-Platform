import type { ObjectDirective } from 'vue'

type MagneticElement = HTMLElement & { __magneticCleanup?: () => void }

function supportsMagneticMotion() {
  return window.matchMedia('(hover: hover) and (pointer: fine)').matches
    && !window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

export const vMagnetic: ObjectDirective<MagneticElement> = {
  mounted(element) {
    if (!supportsMagneticMotion()) return
    let frame = 0
    const move = (event: PointerEvent) => {
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(() => {
        const rect = element.getBoundingClientRect()
        const x = (event.clientX - rect.left - rect.width / 2) * 0.08
        const y = (event.clientY - rect.top - rect.height / 2) * 0.08
        element.style.setProperty('--magnetic-x', `${x.toFixed(2)}px`)
        element.style.setProperty('--magnetic-y', `${y.toFixed(2)}px`)
      })
    }
    const reset = () => {
      cancelAnimationFrame(frame)
      element.style.setProperty('--magnetic-x', '0px')
      element.style.setProperty('--magnetic-y', '0px')
    }
    element.classList.add('magnetic-control')
    element.addEventListener('pointermove', move)
    element.addEventListener('pointerleave', reset)
    element.addEventListener('blur', reset)
    element.__magneticCleanup = () => {
      cancelAnimationFrame(frame)
      element.removeEventListener('pointermove', move)
      element.removeEventListener('pointerleave', reset)
      element.removeEventListener('blur', reset)
    }
  },
  unmounted(element) {
    element.__magneticCleanup?.()
  },
}
