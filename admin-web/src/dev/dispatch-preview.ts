import {createApp} from 'vue'
import DispatchPreview from './DispatchPreview.vue'
if(!import.meta.env.DEV)throw new Error('交互预览仅限开发环境')
createApp(DispatchPreview).mount('#app')
