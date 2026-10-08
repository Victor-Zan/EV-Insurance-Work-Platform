import test from 'node:test'
import { randomUUID } from 'node:crypto'
import assert from 'node:assert/strict'
import { AxiosError } from 'axios'
import { AuthSession, permitsPortal, allowedRoles } from '../src/shared/auth/session.ts'
import { createClient, ApiError } from '../src/shared/http/create-client.ts'
import { guard } from '../src/shared/auth/guard.ts'
const storage = () => { const map = new Map(); return { getItem: key => map.get(key) ?? null, setItem: (key,value) => map.set(key,value), removeItem: key => map.delete(key) } }
const user = { id: 1, username: 'test_account', displayName: '测试账号', roles: [allowedRoles[0]] }
const result = () => ({ user, accessToken: 'simulated-test-token', expiresAt: new Date(Date.now()+60000).toISOString() })
test('all four roles are restricted to the correct portal', () => {
  assert.equal(permitsPortal([]),false); assert.equal(permitsPortal(['ADMIN','OWNER']),false)
  for(const role of ['ADMIN','CUSTOMER_SERVICE','REPAIR_SHOP','OWNER']) assert.equal(permitsPortal([role]),allowedRoles.includes(role))
})
test('wrong portal is rejected and does not persist authentication', () => {
  const store=storage(), session=new AuthSession(store)
  const wrong=['ADMIN','CUSTOMER_SERVICE','REPAIR_SHOP','OWNER'].find(role => !allowedRoles.includes(role))
  assert.throws(() => session.set({ ...result(), user: { ...user, roles: [wrong] } }),/当前端/)
  assert.equal(session.valid(),false); assert.equal(store.getItem('ev-auth'),null)
})
test('expiry, malformed persisted state and logout clear all authentication', () => {
  const store=storage(); let now=Date.now(); const session=new AuthSession(store,() => now)
  session.set(result()); now+=120000; assert.equal(session.valid(),false); assert.equal(session.user,null); assert.equal(store.getItem('ev-auth'),null)
  store.setItem('ev-auth','broken'); assert.equal(new AuthSession(store).valid(),false)
  session.set({ ...result(), expiresAt:new Date(now+60000).toISOString() }); session.clear(); assert.equal(session.token,null)
})
test('guard blocks unauthenticated, wrong role and server rejected sessions', async () => {
  const session=new AuthSession(storage()); assert.equal(await guard(session,'/',[],async () => user),'/login')
  session.set(result()); assert.equal(await guard(session,'/',[],async () => user),true)
  const wrong=['ADMIN','CUSTOMER_SERVICE','REPAIR_SHOP','OWNER'].find(role => !allowedRoles.includes(role))
  assert.equal(await guard(session,'/users',[wrong],async () => user),'/forbidden')
  assert.equal(await guard(session,'/',[],async () => { throw new ApiError('network','NETWORK_ERROR') }),'/connection-error')
  assert.equal(await guard(session,'/',[],async () => { throw new ApiError('denied','FORBIDDEN',403) }),'/login')
  assert.equal(session.valid(),false)
  session.set(result())
  assert.equal(await guard(session,'/',[],async () => { throw new ApiError('disabled','SESSION_INVALID',401) }),'/login')
  assert.equal(session.valid(),false)
})
test('login sends credentials and portal to API; authenticated calls attach Bearer', async () => {
  const session=new AuthSession(storage()), api=createClient(session,'/api/v1'); let sent
  api.client.defaults.adapter=async config => {
    sent=config
    return { status:200,statusText:'OK',headers:{},config,data:{code:'SUCCESS',data: config.url==='/auth/login' ? result() : user} }
  }
  await api.login('test_account',randomUUID())
  assert.equal(JSON.parse(sent.data).username,'test_account'); assert.ok(JSON.parse(sent.data).portal)
  assert.equal(sent.headers.Authorization,undefined)
  await api.portalUser(); assert.equal(sent.headers.Authorization,'Bearer simulated-test-token')
  session.clear()
})
test('API 401 clears state, 403 preserves valid state and network failures have explicit messages', async () => {
  for(const status of [401,403,undefined]) {
    const session=new AuthSession(storage());session.set(result());const api=createClient(session,'/api/v1')
    api.client.defaults.adapter=async config => { throw new AxiosError('failure','ERR_TEST',config,{},status ? {status,statusText:'',headers:{},config,data:{code:'FORBIDDEN',message:'denied'}} : undefined) }
    await assert.rejects(api.me(),error => status===401 ? /登录失效/.test(error.message) : status===403 ? /权限/.test(error.message) : /网络连接/.test(error.message))
    assert.equal(session.valid(),status!==401);session.clear()
  }
})
