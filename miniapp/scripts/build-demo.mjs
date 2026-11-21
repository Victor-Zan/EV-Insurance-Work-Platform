import { spawn } from 'node:child_process'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const child = spawn(process.execPath, [resolve(root, 'node_modules/@dcloudio/vite-plugin-uni/bin/uni.js'), 'build', '--mode', 'local-demo'], {
  cwd: root, stdio: 'inherit', windowsHide: true,
  env: { ...process.env, UNI_PLATFORM: 'h5', UNI_OUTPUT_DIR: resolve(root, '.task-log/browser-demo/h5'), VITE_NATIVE_PREVIEW: 'false' },
})
child.on('error', error => { process.stderr.write(`${error.message}\n`); process.exitCode = 1 })
child.on('exit', code => { process.exitCode = code ?? 1 })
