import { defineConfig } from 'vite'
import uniModule from '@dcloudio/vite-plugin-uni'
import { candidateReceipt } from './scripts/build-receipt'
import { resolve } from 'node:path'

// The published CJS package exposes its plugin under module.default in Node 24.
const uni = ((uniModule as unknown as { default?: typeof uniModule }).default ?? uniModule)

export default defineConfig(({ mode }) => ({
  resolve: { alias: { '@local-preview': resolve(process.cwd(), mode === 'local-demo' && (process.env.UNI_PLATFORM === 'h5' || (process.env.UNI_PLATFORM === 'mp-weixin' && process.env.VITE_NATIVE_PREVIEW === 'true')) ? 'dev/local-preview.ts' : 'src/services/local-preview.ts') } },
  plugins: [uni(), candidateReceipt()],
  server: {
    host: '127.0.0.1',
    port: 5196,
    strictPort: true,
  },
}))
