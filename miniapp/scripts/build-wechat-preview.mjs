import { spawn } from 'node:child_process'
import { readFile, writeFile } from 'node:fs/promises'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const args = process.argv.slice(2)
if (args.length && (args.length !== 2 || args[0] !== '--app-id' || !/^wx[0-9a-f]{16}$/i.test(args[1]))) throw new Error('Usage: build:preview:mp [--app-id <devtools test AppID>]')
const output = resolve(root, '.task-log/wechat-preview/mp-weixin')
const child = spawn(process.execPath, [resolve(root, 'node_modules/@dcloudio/vite-plugin-uni/bin/uni.js'), 'build', '-p', 'mp-weixin', '--mode', 'local-demo'], {
  cwd: root, stdio: 'inherit', windowsHide: true,
  env: { ...process.env, UNI_PLATFORM: 'mp-weixin', UNI_OUTPUT_DIR: output, VITE_NATIVE_PREVIEW: 'true', VITE_API_BASE_URL: '', VITE_WECHAT_LOGIN_URL: '' },
})
child.on('error', error => { process.stderr.write(`${error.message}\n`); process.exitCode = 1 })
child.on('exit', async code => {
  if (code !== 0) { process.exitCode = code ?? 1; return }
  try {
    const appPath = resolve(output, 'app.json'), projectPath = resolve(output, 'project.config.json')
    const app = JSON.parse(await readFile(appPath, 'utf8'))
    if (!app.pages.includes('pages/index/index')) throw new Error('Preview home page missing')
    app.pages = ['pages/index/index', ...app.pages.filter(page => page !== 'pages/index/index')]
    const project = JSON.parse(await readFile(projectPath, 'utf8'))
    project.projectname = 'e保开发预览（免登录）'
    if (args[1]) project.appid = args[1]
    await writeFile(appPath, JSON.stringify(app, null, 2) + '\n')
    await writeFile(projectPath, JSON.stringify(project, null, 2) + '\n')
    console.log(`微信开发预览已生成：${output}\n直接进入车主首页，可切换网点视角；仅示例数据，不可发布。`)
  } catch (error) { process.stderr.write(`${error.message}\n`); process.exitCode = 1 }
})
