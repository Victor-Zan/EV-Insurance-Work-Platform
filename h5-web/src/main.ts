import FileDropZone from './shared/upload/FileDropZone.vue'
import { preventFileNavigation } from './shared/upload/file-drop'
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'vant/lib/index.css'
import App from './App.vue'
import router from './router'
import './styles.css'

preventFileNavigation(document)

createApp(App).component('FileDropZone', FileDropZone).use(createPinia()).use(router).mount('#app')

