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

test('English failures show Chinese guidance while preserving status and code',async()=>{
 const session=new AuthSession(storage());session.set(result());const api=createClient(session,'/api/v1')
 api.client.defaults.adapter=async config=>{throw new AxiosError('failure','ERR_TEST',config,{}, {status:409,statusText:'',headers:{},config,data:{code:'REPAIR_VERSION_CONFLICT',message:'Refresh before submitting'}})}
 await assert.rejects(api.me(),error=>error instanceof ApiError && error.status===409 && error.code==='REPAIR_VERSION_CONFLICT' && /刷新/.test(error.message))
 assert.equal(session.valid(),true)
})

test('gateway failures explain backend availability without retrying login or clearing the session',async()=>{
 for(const status of [502,503,504])for(const body of ['Bad Gateway',null]){
  const session=new AuthSession(storage());session.set(result());const api=createClient(session,'/api/v1');let requests=0
  api.client.defaults.adapter=async config=>{requests++;throw new AxiosError('gateway failure','ERR_BAD_RESPONSE',config,{}, {status,statusText:'',headers:{},config,data:body})}
  await assert.rejects(api.login('test_account',randomUUID()),error=>error instanceof ApiError && error.status===status && /后台服务暂不可用/.test(error.message))
  assert.equal(requests,1);assert.equal(session.valid(),true)
 }
})

test('service unavailable retains the specific business message and code',async()=>{
 const session=new AuthSession(storage());const api=createClient(session,'/api/v1')
 api.client.defaults.adapter=async config=>{throw new AxiosError('unavailable','ERR_BAD_RESPONSE',config,{}, {status:503,statusText:'',headers:{},config,data:{code:'STORAGE_UNAVAILABLE',message:'文件服务暂不可用'}})}
 await assert.rejects(api.me(),error=>error instanceof ApiError && error.status===503 && error.code==='STORAGE_UNAVAILABLE' && error.message==='文件服务暂不可用')
})

test('an unavailable unified login endpoint is distinguished from invalid credentials without retry',async()=>{
 for(const code of ['UNAUTHORIZED','INVALID_CREDENTIALS']){
  const api=createClient(new AuthSession(storage()),'/api/v1');let requests=0
  api.client.defaults.adapter=async config=>{requests++;throw new AxiosError('rejected','ERR_BAD_RESPONSE',config,{}, {status:401,statusText:'',headers:{},config,data:{code,message:'Unauthorized'}})}
  await assert.rejects(api.client.post('/auth/sign-in',{username:'test_account',password:randomUUID()}),error=>error instanceof ApiError && error.status===401 && error.code===code && (code==='UNAUTHORIZED'?/登录服务暂未就绪/.test(error.message):error.message==='账号或密码错误'))
  assert.equal(requests,1)
 }
})

test('multipart uploads preserve file bytes instead of JSON serialization',async()=>{
 const api=createClient(new AuthSession(storage()),'/api/v1'),form=new FormData()
 form.append('file',new Blob(['SYNTHETIC,1.00\n'],{type:'text/csv'}),'synthetic.csv')
 api.client.defaults.adapter=async config=>{
  assert.ok(config.data instanceof FormData)
  assert.equal(await config.data.get('file').text(),'SYNTHETIC,1.00\n')
  assert.notEqual(config.headers.getContentType(),'application/json')
  return {status:200,statusText:'OK',headers:{},config,data:{code:'SUCCESS'}}
 }
 await api.client.post('/funds/imports',form)
})


test('controlled map errors retain distinct safe guidance, trace and status without retry or logout',async()=>{
 const expected={MAP_TIMEOUT:'请求超时',MAP_NETWORK:'无法连接',MAP_NO_RESULT:'未解析到位置',MAP_NO_ROUTE:'未找到可行驶路线',MAP_CREDENTIALS:'凭据无效',MAP_PERMISSION:'安全配置',MAP_QUOTA:'额度不足',MAP_RATE_LIMIT:'过于频繁',MAP_LOCAL_BUDGET:'预算未启用',MAP_BAD_RESPONSE:'数据格式异常',MAP_UPSTREAM:'服务返回异常'}
 for(const [code,hint] of Object.entries(expected))for(const status of [502,504]){
  const session=new AuthSession(storage());session.set(result());const api=createClient(session,'/api/v1');let calls=0
  api.client.defaults.adapter=async config=>{calls++;throw new AxiosError('secret URL must not be shown','ERR_BAD_RESPONSE',config,{}, {status,statusText:'',headers:{},config,data:{code,message:'供应商原文包含私密地址和密钥',traceId:'synthetic-trace-123'}})}
  await assert.rejects(api.client.post('/maps/parking/resolve',{}),error=>error instanceof ApiError && error.status===status && error.code===code && error.traceId==='synthetic-trace-123' && error.message.includes(hint) && error.message.includes(code) && error.message.includes('synthetic-trace-123') && !error.message.includes('供应商原文') && !error.message.includes('secret URL'))
  assert.equal(calls,1);assert.equal(session.valid(),true)
 }
})

test('map diagnostics reject untrusted trace identifiers and unknown map codes remain generic at gateway',async()=>{
 for(const code of ['MAP_TIMEOUT','MAP_UNKNOWN','__proto__']){
  const api=createClient(new AuthSession(storage()),'/api/v1')
  api.client.defaults.adapter=async config=>{throw new AxiosError('failure','ERR_BAD_RESPONSE',config,{}, {status:502,statusText:'',headers:{},config,data:{code,message:'不可信响应',traceId:'unsafe\nprivate-address'}})}
  await assert.rejects(api.client.get('/maps/status'),error=>error.traceId===undefined && !error.message.includes('private-address') && !error.message.includes('不可信响应') && (code==='MAP_TIMEOUT'?error.message.includes('请求超时'):error.message.includes('后台服务暂不可用')))
 }
})

test('controlled map codes do not override authentication or permission boundaries',async()=>{
 for(const status of [401,403]){
  const session=new AuthSession(storage());session.set(result());const api=createClient(session,'/api/v1')
  api.client.defaults.adapter=async config=>{throw new AxiosError('failure','ERR_TEST',config,{}, {status,statusText:'',headers:{},config,data:{code:'MAP_TIMEOUT',traceId:'synthetic'}})}
  await assert.rejects(api.me(),error=>status===401?error.message==='登录失效，请重新登录':error.message==='没有访问权限')
  assert.equal(session.valid(),status!==401)
 }
})


test('browser map timeout reports an unknown potentially charged attempt without retry',async()=>{
 const api=createClient(new AuthSession(storage()),'/api/v1');let calls=0
 api.client.defaults.adapter=async config=>{calls++;throw new AxiosError('timeout','ECONNABORTED',config)}
 await assert.rejects(api.client.post('/maps/locations/resolve',{}),error=>/结果未知/.test(error.message)&&/可能已计入调用量/.test(error.message))
 assert.equal(calls,1)
 await assert.rejects(api.me(),error=>error.message==='网络连接失败，请检查后重试')
})
