import { nextPaint, prepareStartupImages } from './startup-readiness'

const screen = document.getElementById('startup')!
const app = document.getElementById('app')!
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)')

function progress(value: number) {
  if (screen.dataset.state === 'failed') return
  screen.querySelector('[role="progressbar"]')!.setAttribute('aria-valuenow', String(value))
  screen.querySelector('[data-percent]')!.textContent = `${value}%`
  screen.style.setProperty('--progress', String(value))
}

async function start() {
  const { mountPlatform } = await import('./main')
  progress(20)
  await mountPlatform()
  if (!document.querySelector('.login-page')) screen.querySelector('.startup-title')!.textContent = '正在准备平台页面'
  // Login may consume an account handoff and navigate before its final DOM is ready.
  while (document.querySelector('.login-page[data-page-ready="false"]')) {
    if (screen.dataset.state === 'failed') return
    await nextPaint()
  }
  progress(40)
  await document.fonts.ready
  progress(50)
  await prepareStartupImages([...document.querySelectorAll<HTMLImageElement>('#app img, #startup img')], progress)
  const animations = app.getAnimations({ subtree: true }).filter(animation =>
    animation.effect?.getTiming().iterations !== Infinity)
  await Promise.all(animations.map(animation => animation.finished.catch(() => undefined)))
  await nextPaint()
  if (screen.dataset.state === 'failed') return
  progress(100)
  window.dispatchEvent(new Event('startup-ready'))
  await nextPaint()
  // The progress arc reaches its actual final value before the overlay fades away.
  if (!reducedMotion.matches) {
    await Promise.all(screen.getAnimations({ subtree: true }).filter(animation =>
      animation.effect?.getTiming().iterations !== Infinity).map(animation => animation.finished.catch(() => undefined)))
    await screen.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 280, easing: 'ease-out', fill: 'forwards' }).finished
  }
  screen.remove()
  app.inert = false
  app.removeAttribute('aria-hidden')
  document.documentElement.classList.remove('starting')
}

start().catch(() => window.dispatchEvent(new Event('startup-failed')))
window.addEventListener('pageshow', event => {
  if (event.persisted && document.getElementById('startup')) location.reload()
})
