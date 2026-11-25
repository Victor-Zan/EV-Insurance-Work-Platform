import { createServer } from 'node:http'
import { spawn } from 'node:child_process'
import { createHash, randomUUID } from 'node:crypto'
import { createReadStream, closeSync, openSync } from 'node:fs'
import { mkdir, readFile, stat, writeFile } from 'node:fs/promises'
import { dirname, extname, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { setTimeout as delay } from 'node:timers/promises'

const script = fileURLToPath(import.meta.url)
const root = resolve(dirname(script), '..')
const demo = process.argv.includes('--demo') || process.argv[2] === 'start-demo'
const contentRoot = resolve(root, demo ? '.task-log/browser-demo/h5' : 'dist/build/h5')
const runtimeRoot = resolve(root, '.task-log')
const statePath = resolve(runtimeRoot, 'preview-state.json')
const workspace = createHash('sha256').update(root.toLowerCase()).digest('hex').slice(0, 20)
const baseUrl = 'http://127.0.0.1:5196'
const marker = 'ev-insurance-miniapp-local-preview'
const contentTypes = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.webp': 'image/webp',
  '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.ico': 'image/x-icon',
  '.woff': 'font/woff', '.woff2': 'font/woff2',
}

async function currentServer() {
  let response
  try {
    response = await fetch(`${baseUrl}/__preview_status`, { signal: AbortSignal.timeout(1500) })
  } catch (error) {
    if (error.cause?.code === 'ECONNREFUSED') return null
    throw new Error('无法确认5196端口身份；请检查本机网络，未停止或替换任何进程。', { cause: error })
  }
  let info
  try { info = await response.json() } catch { /* An unrelated service must remain untouched. */ }
  if (!response.ok || info?.application !== marker || info.workspace !== workspace) {
    throw new Error('5196已被其他服务占用；未停止或替换该服务。')
  }
  return info
}

async function serve(instanceId) {
  if (!instanceId) throw new Error('缺少本次启动标识。')
  await stat(resolve(contentRoot, 'index.html'))
  const info = { application: marker, workspace, mode: demo ? 'demo' : 'normal', instanceId, pid: process.pid, startedAt: new Date().toISOString() }
  const server = createServer(async (request, response) => {
    response.setHeader('X-Content-Type-Options', 'nosniff')
    response.setHeader('Cache-Control', 'no-store')
    try {
      const url = new URL(request.url ?? '/', baseUrl)
      if (url.pathname === '/__preview_status' && request.method === 'GET') {
        response.setHeader('Content-Type', 'application/json; charset=utf-8')
        response.end(JSON.stringify(info))
        return
      }
      if (url.pathname === '/__preview_stop' && request.method === 'POST') {
        if (request.headers['x-preview-instance'] !== instanceId) {
          response.writeHead(403).end('Preview instance mismatch')
          return
        }
        response.setHeader('Connection', 'close')
        response.end('Preview stopped')
        server.close()
        server.closeIdleConnections()
        return
      }
      if (!['GET', 'HEAD'].includes(request.method)) {
        response.writeHead(405, { Allow: 'GET, HEAD' }).end('Method not allowed')
        return
      }
      const pathname = decodeURIComponent(url.pathname)
      if (pathname.includes('\0') || pathname.includes('\\')) {
        response.writeHead(400).end('Invalid path')
        return
      }
      const target = resolve(contentRoot, `.${pathname === '/' ? '/index.html' : pathname}`)
      const fromRoot = relative(contentRoot, target)
      if (fromRoot === '..' || fromRoot.startsWith(`..${sep}`)) {
        response.writeHead(403).end('Forbidden')
        return
      }
      let metadata
      try { metadata = await stat(target) } catch {
        response.writeHead(404).end('Not found')
        return
      }
      if (!metadata.isFile()) {
        response.writeHead(404).end('Not found')
        return
      }
      response.setHeader('Content-Type', contentTypes[extname(target).toLowerCase()] ?? 'application/octet-stream')
      response.setHeader('Content-Length', metadata.size)
      if (request.method === 'HEAD') { response.end(); return }
      const stream = createReadStream(target)
      stream.on('error', () => response.destroy())
      stream.pipe(response)
    } catch {
      if (!response.headersSent) response.writeHead(400)
      response.end('Invalid request')
    }
  })
  server.on('error', (error) => { process.stderr.write(`Preview startup failed: ${error.message}\n`); process.exitCode = 1 })
  server.listen(5196, '127.0.0.1', async () => {
    try { await writeFile(statePath, JSON.stringify(info, null, 2) + '\n') } catch (error) {
      process.stderr.write(`Cannot save preview ownership: ${error.message}\n`)
      server.close()
      process.exitCode = 1
    }
  })
}

async function main() {
  const action = process.argv[2] === 'start-demo' ? 'start' : process.argv[2] ?? 'status'
  if (action === 'serve') { await serve(process.argv[3]); return }
  if (!['start', 'status', 'stop'].includes(action)) throw new Error('用法：node scripts/preview.mjs start|status|stop')
  let running = await currentServer()
  if (action === 'status') {
    process.stdout.write(JSON.stringify({ running: Boolean(running), url: baseUrl, mode: running?.mode ?? 'normal', pid: running?.pid ?? null }) + '\n')
    return
  }
  if (action === 'stop' || (action === 'start' && running && (running.mode ?? 'normal') !== (demo ? 'demo' : 'normal'))) {
    if (!running) { process.stdout.write('本机预览未运行。\n'); return }
    const recorded = JSON.parse(await readFile(statePath, 'utf8'))
    if (recorded.instanceId !== running.instanceId || recorded.pid !== running.pid) {
      throw new Error('预览归属记录不匹配，未停止进程。')
    }
    const stopped = await fetch(`${baseUrl}/__preview_stop`, {
      method: 'POST', headers: { 'X-Preview-Instance': recorded.instanceId, Connection: 'close' }, signal: AbortSignal.timeout(2000),
    })
    if (!stopped.ok) throw new Error('预览未确认停止，请保留现场检查。')
    process.stdout.write('已停止本项目本机预览；其他服务未操作。\n')
    if (action === 'stop') return
    for (let attempt = 0; attempt < 30; attempt++) { await delay(100); running = await currentServer(); if (!running) break }
    if (running) throw new Error('原预览尚未停止，未启动另一个实例。')
  }
  if (running) { process.stdout.write(`本机预览已运行：${baseUrl}/\n`); return }
  try { await stat(resolve(contentRoot, 'index.html')) } catch {
    throw new Error(`缺少浏览器构建产物，请先在miniapp执行 npm run ${demo ? 'build:demo' : 'build:h5'}。`)
  }
  await mkdir(runtimeRoot, { recursive: true })
  const log = openSync(resolve(runtimeRoot, 'preview-server.log'), 'a')
  const instanceId = randomUUID()
  const child = spawn(process.execPath, [script, 'serve', instanceId, ...(demo ? ['--demo'] : [])], {
    cwd: root, detached: true, windowsHide: true, stdio: ['ignore', log, log],
  })
  let spawnError
  child.on('error', (error) => { spawnError = error })
  child.unref()
  closeSync(log)
  for (let attempt = 0; attempt < 30; attempt += 1) {
    await delay(150)
    if (spawnError) throw spawnError
    const info = await currentServer()
    if (info?.instanceId === instanceId) {
      process.stdout.write(`本机预览已独立启动：${baseUrl}/\n`)
      return
    }
    if (info) throw new Error('端口已被其他启动占用，未停止任何进程。')
  }
  throw new Error('本机预览未就绪，请查看miniapp/.task-log/preview-server.log。')
}

main().catch((error) => { process.stderr.write(`${error.message}\n`); process.exitCode = 1 })
