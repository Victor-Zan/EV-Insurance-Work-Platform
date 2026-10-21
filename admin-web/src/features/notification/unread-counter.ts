import { computed, ref } from 'vue'
import type { InjectionKey } from 'vue'

export function createUnreadCounter(loadCount: () => Promise<number>) {
  const count = ref(0)
  const label = computed(() => count.value > 99 ? '99+' : String(count.value))
  let version = 0
  async function refresh() {
    const request = ++version
    try {
      const next = await loadCount()
      if (request === version) count.value = next
    } catch { /* Keep the last confirmed count until the next successful refresh. */ }
  }
  function reset() { version++; count.value = 0 }
  return { count, label, refresh, reset }
}

export const unreadNotificationsKey: InjectionKey<ReturnType<typeof createUnreadCounter>> = Symbol('unreadNotifications')
