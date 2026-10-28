import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = path => readFileSync(new URL(path, import.meta.url), 'utf8')

test('management navigation exposes only existing role-scoped routes', () => {
  const routes = source('../src/router/index.ts')
  assert.match(routes, /path: '\/funds\/imports'.*roles: \['CUSTOMER_SERVICE'\]/)
  for (const path of ['/admin/users', '/admin/regions', '/admin/shops', '/admin/audit-logs', '/admin/settings/work-orders']) {
    assert.match(routes, new RegExp(`path: '${path.replaceAll('/', '\\/')}'.*roles: \\['ADMIN'\\]`))
  }
  assert.match(routes, /path: '\/notifications'.*roles: \['ADMIN', 'CUSTOMER_SERVICE'\]/)
  assert.doesNotMatch(routes, /'OWNER'.*\/funds|\/funds.*'OWNER'/)
})

test('workbench uses live paginated APIs and avoids invented metrics', () => {
  const view = source('../src/views/HomeView.vue')
  assert.match(view, /'\/notifications\/todos'/)
  assert.match(view, /'\/notifications'/)
  assert.match(view, /api\.list\(1,'','',true\)/)
  assert.doesNotMatch(view, /今日回款额|平均处理时长|维修完成率/)
})

test('case summary has exactly the confirmed three repair authorization conditions', () => {
  const view = source('../src/features/workorders/CaseSummaryPanel.vue')
  for (const key of ['finalAssessmentAmountRecorded', 'insurerConfirmationRecorded', 'customerServiceConfirmationRecorded']) assert.match(view, new RegExp(key))
  assert.doesNotMatch(view, /回款已到账|网点二次确认|报价已审核/)
  assert.match(view, /quotationApi\(httpClient, props\.caseId\)\.get\(\)/)
  assert.match(view, /api\.history\(props\.caseId\)/)
})

test('new admin views use the matching real APIs and service-region updates', () => {
  assert.match(source('../src/views/RegionsView.vue'), /'\/admin\/regions'/)
  const shops = source('../src/views/ShopsView.vue')
  assert.match(shops, /'\/admin\/shops'/)
  assert.match(shops, /service-regions/)
  assert.match(shops, /regionIds:selectedRegions\.value/)
  assert.match(source('../src/views/AuditView.vue'), /'\/admin\/audit-logs'/)
  assert.match(source('../src/views/WorkOrderSettingsView.vue'), /api\.updateConfiguration/)
})

test('shell supports keyboard-accessible navigation and reduced motion', () => {
  const shell = source('../src/shared/ui/AppShell.vue')
  const css = source('../src/styles.css')
  assert.match(shell, /aria-expanded/)
  assert.match(shell, /@keyup\.esc/)
  assert.match(shell, /class="skip-link"/)
  assert.match(css, /prefers-reduced-motion:reduce/)
  assert.doesNotMatch(css, /min-width: 1024px/)
})

test('design refinement retains engineering artwork, layered glass and safe motion', () => {
  const shell = source('../src/shared/ui/AppShell.vue')
  const login = source('../src/views/LoginView.vue')
  const home = source('../src/views/HomeView.vue')
  const app = source('../src/App.vue')
  const magnetic = source('../src/shared/ui/magnetic.ts')
  const css = source('../src/styles.css')
  assert.match(shell, /e-scooter-graphite\.png/)
  assert.match(login, /e-scooter-exploded-login\.webp/)
  assert.match(home, /e-scooter-parts-empty\.webp/)
  assert.match(app, /Transition name="page-shift"/)
  assert.match(css, /backdrop-filter:blur\(18px\)/)
  assert.match(magnetic, /\(hover: hover\) and \(pointer: fine\)/)
  assert.match(magnetic, /prefers-reduced-motion: reduce/)
})
