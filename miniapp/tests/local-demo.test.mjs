import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdir, readFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { build } from 'esbuild'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const output = path.join(root, '.task-log/local-demo-tests/services.mjs')
await mkdir(path.dirname(output), { recursive: true })
await build({ stdin: { contents: `export * as auth from './src/services/auth'; export * as state from './src/services/session'; export * as owner from './src/services/owner'; export * as shop from './src/services/shop'; export * as repair from './src/services/shop-repair';`, resolveDir: root },
  bundle: true, platform: 'node', format: 'esm', target: 'es2015', external: ['vue'], outfile: output,
  alias: { '@local-preview': path.join(root, 'dev/local-preview.ts') }, define: { 'import.meta.env': '{}' },
  plugins: [{ name: 'h5-demo-target', setup(builder) { builder.onLoad({ filter: /services[\/\\].*\.ts$/ }, async args => ({ loader: 'ts', contents: (await readFile(args.path, 'utf8')).replace(/\/\/ #ifdef MP-WEIXIN[\s\S]*?\/\/ #endif/g, '') })) } }],
})
const { auth, state, owner, shop, repair } = await import(pathToFileURL(output).href)

test('loopback demo uses existing sessions/pages services, shares in-memory actions, retries once and never calls a business server', async () => {
  const previousLocation = globalThis.location, previousUni = globalThis.uni
  globalThis.location = { hostname: '127.0.0.1' }
  globalThis.uni = { request: () => { throw Error('Unexpected network request') }, reLaunch: value => value.complete?.() }
  try {
    await auth.loginWithDemo('OWNER'); await auth.revalidateSession()
    assert.equal(state.currentSession().user.id, 90001)
    const ownerCases = await owner.ownerApi.cases()
    assert.equal(ownerCases.total, 5)
    const pending = ownerCases.records.find(row => row.status === 'WAITING_OWNER_CONFIRMATION')
    const before = await owner.ownerApi.repair(pending.id, 'demo-check')
    const command = owner.receiptCommand(before)
    const once = await command.execute(), twice = await command.execute()
    assert.equal(once.status, 'WAITING_INSURER_PAYMENT'); assert.equal(twice.version, once.version)
    auth.logout(); assert.equal(state.currentSession(), null)
    await auth.loginWithDemo('REPAIR_SHOP'); await auth.revalidateSession()
    const shopCases = await shop.shopApi.cases()
    assert.equal(shopCases.records.find(row => row.id === pending.id).status, 'WAITING_INSURER_PAYMENT')
    const incoming = shopCases.records.find(row => row.status === 'PENDING_ACCEPTANCE')
    const accepted = await shop.assignmentCommand(incoming, 'accept').execute()
    const arrived = await shop.assignmentCommand(accepted, 'arrive').execute()
    assert.equal(arrived.status, 'ARRIVED')
    const repairing = shopCases.records.find(row => row.status === 'REPAIRING')
    const context = await shop.shopApi.quote(repairing.id, 'demo-check')
    const stateBefore = await repair.shopRepairApi.get(repairing, 'demo-check')
    const progress = await repair.repairCommand(repairing, stateBefore, context, 'progress', '演示：试车完成', [], []).execute()
    assert.equal(progress.version, stateBefore.version + 1)
    auth.logout(); globalThis.location = { hostname: 'example.com' }
    await assert.rejects(auth.loginWithDemo('OWNER'), /本机回环/)
    assert.equal(state.currentSession(), null)
  } finally { state.clearSession(); globalThis.location = previousLocation; globalThis.uni = previousUni }
})
