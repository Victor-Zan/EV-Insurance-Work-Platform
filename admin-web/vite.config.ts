import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig, loadEnv } from 'vite'

export default defineConfig(({mode}) => {
  const env=loadEnv(mode, '..', 'VITE_API_BASE_URL')
  const backendOrigin=new URL(env.VITE_API_BASE_URL??'/api/v1','http://localhost:8080').origin
  return {
  plugins: [vue()],
  envDir: '..',
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    proxy: { '/api': {target:backendOrigin,changeOrigin:false} },
    port: 5173,
  },
  }
})

