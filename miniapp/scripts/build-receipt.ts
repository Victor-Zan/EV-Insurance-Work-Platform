import { createHash } from 'node:crypto'
import { readFileSync, readdirSync } from 'node:fs'
import { resolve, relative } from 'node:path'
import type { Plugin } from 'vite'

export function sourceFingerprint(root: string) {
  const paths = ['package.json', 'package-lock.json', 'vite.config.ts', 'scripts/build-receipt.ts', 'scripts/build-demo.mjs', 'scripts/build-wechat-preview.mjs']
  function visit(dir: string) {
    for (const entry of readdirSync(resolve(root, dir), { withFileTypes: true })) {
      if (entry.isSymbolicLink()) throw new Error('Candidate inputs must not contain symbolic links')
      const name = `${dir}/${entry.name}`
      if (entry.isDirectory()) visit(name); else if (entry.isFile()) paths.push(name)
    }
  }
  visit('src')
  visit('dev')
  const hash = createHash('sha256')
  for (const path of paths.sort()) hash.update(path).update('\0').update(readFileSync(resolve(root, path))).update('\0')
  return hash.digest('hex')
}
// Only public build configuration is emitted. Invalid URLs are redacted rather than copied.
export function candidateReceipt(): Plugin {
  let root = '', apiBase: string | null = null, configured = false, localDemo = false
  return {
    name: 'miniapp-candidate-receipt', apply: 'build', enforce: 'post',
    configResolved(config) {
      root = config.root
      localDemo = config.mode === 'local-demo' && (process.env.UNI_PLATFORM === 'h5' || (process.env.UNI_PLATFORM === 'mp-weixin' && process.env.VITE_NATIVE_PREVIEW === 'true'))
      const preferred = config.env.VITE_API_BASE_URL?.trim(), legacy = config.env.VITE_WECHAT_LOGIN_URL?.trim()
      const value = preferred || (legacy?.endsWith('/auth/wechat/login') ? legacy.slice(0, -'/auth/wechat/login'.length) : '')
      configured = Boolean(preferred || legacy)
      const normalized = value.replace(/\/$/, '')
      if (/^https:\/\/[A-Za-z0-9.-]+(?::[0-9]{1,5})?(?:\/[A-Za-z0-9_~-]+)*\/api\/v1$/.test(normalized)) {
        try { const url = new URL(normalized); if (!url.username && !url.password && !url.search && !url.hash) apiBase = normalized } catch { /* Do not copy malformed configuration into the package. */ }
      }
    },
    generateBundle() {
      const manifest = JSON.parse(readFileSync(resolve(root, 'src/manifest.json'), 'utf8'))
      this.emitFile({ type: 'asset', fileName: 'build-receipt.json', source: JSON.stringify({ format: 1,
        target: process.env.UNI_PLATFORM, version: manifest.versionName, sourceAppId: manifest['mp-weixin']?.appid || null,
        apiBase, configured, localDemo, sourceSha256: sourceFingerprint(root), lockSha256: createHash('sha256').update(readFileSync(resolve(root, 'package-lock.json'))).digest('hex'),
        compatibility: { identity: 'MINIAPP_S02_CONTRACT v2', requiredMigration: 'V42', http: '/api/v1' },
        inputs: relative(root, resolve(root, 'src')).replaceAll('\\', '/') }, null, 2) })
    },
  }
}
