import { readFile, readdir, writeFile } from 'node:fs/promises'
import { fileURLToPath, URL } from 'node:url'
import { log } from 'node:console'

// Package this three-page design sample for offline review after the Vite build.
const dist = new URL('../../../tmp/desktop-upgrade-preview-dist/', import.meta.url)
let html = await readFile(new URL('index.html', dist), 'utf8')
const script = html.match(/<script[^>]+src="([^"]+)"[^>]*><\/script>/)
const stylesheet = html.match(/<link[^>]+href="([^"]+\.css)"[^>]*>/)
if (!script || !stylesheet) throw new Error('Build the preview before packaging it.')
let js = await readFile(new URL(script[1], dist), 'utf8')
const css = await readFile(new URL(stylesheet[1], dist), 'utf8')
for (const name of await readdir(new URL('assets/', dist))) {
  if (!name.endsWith('.png')) continue
  const data = await readFile(new URL(`assets/${name}`, dist))
  js = js.replaceAll(name, `data:image/png;base64,${data.toString('base64')}`)
}
html = html.replace(script[0], () => `<script type="module">${js.replaceAll('</script', '<\\/script')}</script>`)
html = html.replace(stylesheet[0], () => `<style>${css}</style>`)
const output = new URL('preview.html', import.meta.url)
await writeFile(output, html)
log(`Offline preview: ${fileURLToPath(output)}`)
