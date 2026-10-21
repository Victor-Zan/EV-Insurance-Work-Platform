import vue from '../../../admin-web/node_modules/@vitejs/plugin-vue/dist/index.mjs'
import { fileURLToPath, URL } from 'node:url'
const local = (name) => fileURLToPath(new URL(name, import.meta.url))
export default {
  root: local('./'),
  base: './',
  plugins: [vue()],
  resolve: { alias: {
    vue: local('../../../admin-web/node_modules/vue/dist/vue.runtime.esm-bundler.js'),
    'element-plus': local('../../../admin-web/node_modules/element-plus'),
  } },
  server: { host: '127.0.0.1', port: 5196, strictPort: true, fs: { allow: [local('../../../')] } },
  build: { outDir: local('../../../tmp/desktop-upgrade-preview-dist'), emptyOutDir: true },
}
