import { createHash } from 'node:crypto'
import { readFile, readdir, mkdir, writeFile, realpath } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { isIP } from 'node:net'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const output = path.join(root, '.task-log/s08-candidate/inspection.json')
const args = process.argv.slice(2)
if (args.length && (args.length !== 2 || args[0] !== '--dir')) throw new Error('Usage: release:inspect [--dir <generated directory inside miniapp>]')
const directory = path.resolve(root, args[1] || 'dist/build/mp-weixin')
const inside = value => value.startsWith(root + path.sep)
if (!inside(directory)) throw new Error('Inspection directory must remain inside miniapp')
const findings = [], files = []
const sha = bytes => createHash('sha256').update(bytes).digest('hex')
const json = async name => JSON.parse(await readFile(path.join(directory, name), 'utf8'))
async function visit(base, names) {
  for (const entry of (await readdir(base, { withFileTypes: true })).sort((a, b) => a.name.localeCompare(b.name))) {
    if (entry.isSymbolicLink()) throw new Error('Inspection refuses symbolic links')
    const file = path.join(base, entry.name)
    if (entry.isDirectory()) await visit(file, names); else if (entry.isFile()) names.push(file)
  }
}
async function fingerprint() {
  const input = []; await visit(path.join(root, 'src'), input)
  await visit(path.join(root, 'dev'), input)
  input.push(...['package.json', 'package-lock.json', 'vite.config.ts', 'scripts/build-receipt.ts', 'scripts/build-demo.mjs', 'scripts/build-wechat-preview.mjs'].map(name => path.join(root, name)))
  const hash = createHash('sha256')
  const names = input.map(file => path.relative(root, file).replaceAll('\\', '/')).sort()
  for (const name of names) hash.update(name).update('\0').update(await readFile(path.join(root, name))).update('\0')
  return hash.digest('hex')
}
let receipt = null, project = null, app = null
try {
  if (!inside(await realpath(directory))) throw new Error('Resolved directory escaped miniapp')
  await visit(directory, files)
  ;[receipt, project, app] = await Promise.all([json('build-receipt.json'), json('project.config.json'), json('app.json')])
  if (receipt.format !== 1 || receipt.target !== 'mp-weixin') findings.push('BUILD_RECEIPT_TARGET_INVALID')
  if (receipt.localDemo === true) findings.push('LOCAL_DEMO_BUILD_NOT_PUBLISHABLE')
  if (receipt.sourceSha256 !== await fingerprint()) findings.push('SOURCE_CHANGED_SINCE_BUILD')
  if (receipt.lockSha256 !== sha(await readFile(path.join(root, 'package-lock.json')))) findings.push('LOCK_CHANGED_SINCE_BUILD')
  if (!receipt.sourceAppId || !/^wx[0-9a-f]{16}$/i.test(receipt.sourceAppId)) findings.push('SOURCE_APPID_NOT_CONFIGURED')
  if (receipt.sourceAppId !== project.appid) findings.push('SOURCE_AND_GENERATED_APPID_DIFFER')
  if (project.setting?.urlCheck !== true) findings.push('DOMAIN_CHECK_DISABLED')
  if (app.pages?.[0] !== 'pages/login/index') findings.push('LOGIN_NOT_FIRST_PAGE')
  const manifest = JSON.parse(await readFile(path.join(root, 'src/manifest.json'), 'utf8'))
  if (manifest.uniStatistics?.enable !== false) findings.push('UNREVIEWED_ANALYTICS')
  if (!receipt.apiBase) findings.push(receipt.configured ? 'API_CONFIGURATION_INVALID' : 'API_NOT_CONFIGURED')
  else {
    const url = new URL(receipt.apiBase)
    if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash || !url.pathname.endsWith('/api/v1')) { findings.push('API_CONFIGURATION_INVALID'); receipt.apiBase = null }
    if (isIP(url.hostname) || url.hostname === 'localhost' || !url.hostname.includes('.') || /\.(?:test|invalid|localhost|local|example)$/.test(url.hostname) || /(?:^|\.)example\.(?:com|net|org)$/.test(url.hostname)) findings.push('API_IS_LOCAL_OR_RESERVED')
  }
} catch (error) { findings.push('BUILD_INPUT_UNREADABLE'); }
const inventory = [], markers = []
for (const file of files) {
  const content = await readFile(file), name = path.relative(directory, file).replaceAll('\\', '/')
  inventory.push({ path: name, bytes: content.length, sha256: sha(content) })
  if (/\.(?:js|json|wxml|wxss)$/.test(name) && /__s0[0-9]Transport|synthetic\.example\.test|synthetic-not-a-real-token|local-demo-owner-not-a-server-token|DEMO-20261107|mockWxMethod|BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY/.test(content.toString('utf8'))) markers.push(name)
}
if (markers.length) findings.push('TEST_OR_PRIVATE_KEY_MARKER_IN_PACKAGE')
const externalGates = ['OFFICIAL_SUBJECT_AND_CATEGORY', 'APPID_OWNERSHIP_AND_FILING', 'OFFICIAL_PACKAGE_LIMIT', 'PRIVACY_CONFIGURATION_AND_CONSENT', 'HTTPS_CERTIFICATE_AND_THREE_DOMAIN_LISTS', 'REAL_WECHAT_LOGIN', 'PRIVATE_STORAGE_AND_FILE_TRANSFER', 'PHYSICAL_DEVICE_LEAST_PRIVILEGE', 'FULL_S07_UI_INTERACTION', 'DATABASE_AND_OBJECT_RESTORE', 'LOG_REDACTION_AND_MONITORING', 'APPROVED_TRIAL_AND_PUBLICATION']
const report = { format: 1, status: findings.length ? 'BLOCKED' : 'LOCAL_CHECKS_PASSED_EXTERNAL_GATES_REQUIRED', createdAt: new Date().toISOString(),
  publicationApproved: false, sourceSha256: receipt?.sourceSha256 || null, artifactSha256: sha(JSON.stringify(inventory)),
  receipt, localFindings: [...new Set(findings)], markerFiles: markers, filesystemBytes: inventory.reduce((sum, file) => sum + file.bytes, 0),
  packageSizeLimitVerified: false, externalGates: externalGates.map(id => ({ id, status: 'MANUAL_EVIDENCE_REQUIRED' })), files: inventory }
await mkdir(path.dirname(output), { recursive: true }); await writeFile(output, JSON.stringify(report, null, 2) + '\n')
console.log(JSON.stringify({ status: report.status, publicationApproved: false, localFindings: report.localFindings, bytes: report.filesystemBytes, files: inventory.length, report: path.relative(root, output) }))
process.exitCode = findings.length ? 2 : 0
