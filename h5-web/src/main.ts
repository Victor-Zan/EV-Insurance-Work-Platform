import FileDropZone from './shared/upload/FileDropZone.vue'
import { preventFileNavigation } from './shared/upload/file-drop'
import { createApp, nextTick } from 'vue'
import { createPinia } from 'pinia'
import 'vant/lib/index.css'
import App from './App.vue'
import router from './router'
import './styles.css'

export async function mountPlatform() {
  preventFileNavigation(document)
  const app = createApp(App).component('FileDropZone', FileDropZone).use(createPinia()).use(router)
  await router.isReady()
  app.mount('#app')
  await nextTick()
}

