import test from 'node:test'
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'
import {unifiedLogin,consumeHandoff} from '../src/shared/auth/account-login.ts'
import {AuthSession,portal,allowedRoles} from '../src/shared/auth/session.ts'
import {createClient,ApiError} from '../src/shared/http/create-client.ts'
import {AxiosError} from 'axios'
const storage=()=>{const map=new Map();return {getItem:k=>map.get(k)??null,setItem:(k,v)=>map.set(k,v),removeItem:k=>map.delete(k)}}
const loginResult=()=>({user:{id:1,username:'synthetic_account',displayName:'合成用户',roles:[allowedRoles[0]]},accessToken:'synthetic-session',expiresAt:new Date(Date.now()+60000).toISOString()})

test('unified login follows server portal without sending a role or persisting a cross-portal JWT',async()=>{
 const session=new AuthSession(storage()),calls=[],code=randomUUID(),password=randomUUID()
 const client={async post(path,body){calls.push([path,body]);return {data:{data:path==='/auth/sign-in'?{portal,code,loginUrl:'https://example.test/login'}:loginResult()}}}}
 assert.equal(await unifiedLogin(client,session,'synthetic_account',password),null)
 assert.deepEqual(calls[0],['/auth/sign-in',{username:'synthetic_account',password}]);assert.deepEqual(calls[1],['/auth/handoff',{code,portal}]);assert.equal(session.valid(),true);session.clear()
 client.post=async(path,body)=>{calls.push([path,body]);return {data:{data:{portal:portal==='ADMIN'?'H5':'ADMIN',code,loginUrl:'https://other.example.test/login'}}}}
 const redirect=await unifiedLogin(client,session,'synthetic_account',password)
 assert.equal(redirect,'https://other.example.test/login#handoff='+code);assert.equal(session.valid(),false);assert.equal(calls.length,3);assert.ok(!redirect.includes(password))
})
test('handoff removes browser fragment before redeeming; errors never create a local session',async()=>{
 const session=new AuthSession(storage()),code=randomUUID(),replacements=[];session.set(loginResult())
 globalThis.location={hash:'#handoff='+code,pathname:'/login',search:''};globalThis.history={replaceState(...args){replacements.push(args);globalThis.location.hash=''}}
 const client={async post(path,body){assert.equal(globalThis.location.hash,'');assert.equal(session.valid(),false);assert.deepEqual(body,{code,portal});return {data:{data:loginResult()}}}}
 assert.equal(await consumeHandoff(client,session),true);assert.equal(replacements[0][2],'/login');session.clear()
 globalThis.location.hash='#handoff='+code;client.post=async()=>{throw Error('已失效')};await assert.rejects(consumeHandoff(client,session),/已失效/);assert.equal(session.valid(),false)
 delete globalThis.location;delete globalThis.history
})
test('sign-in uses uniform credential message and sends no stale bearer credential',async()=>{
 const session=new AuthSession(storage());session.set(loginResult());const api=createClient(session,'/api/v1')
 api.client.defaults.adapter=async config=>{assert.equal(config.headers.Authorization,undefined);throw new AxiosError('failure','ERR_TEST',config,{}, {status:401,statusText:'',headers:{},config,data:{code:'INVALID_CREDENTIALS',message:'Account or password is incorrect'}})}
 await assert.rejects(unifiedLogin(api.client,session,'absent',randomUUID()),error=>error instanceof ApiError&&error.message==='账号或密码错误');assert.equal(session.valid(),false)
})

function activationFixture({failure=false,missing=false}={}){
 const {descriptor}=parse(readFileSync(new URL('../src/views/ActivateView.vue',import.meta.url),'utf8'))
 let code=ts.transpileModule(compileScript(descriptor,{id:'activation-test'}).content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>'const {'+names.replace(/\s+as\s+/g,':')+'}=__vue;').replace(/import .*from ['"].*['"];?/g,'').replace('export default','return')
 const calls=[],credential=randomUUID(),state={hash:missing?'':'#token='+credential,pathname:'/activate',search:''};let mounted,cleared=false
 const client={async post(path,body){calls.push([path,body]);if(failure)throw Error('邀请已失效');return {data:{data:path.endsWith('inspect')?{username:'sy***',displayName:'合**',roles:['OWNER'],loginUrl:'https://mobile.example.test/login'}:{username:'synthetic_owner'}}}}}
 const component=new Function('__vue','httpClient','authSession','location','history',code)({...Vue,onMounted(fn){mounted=fn}},client,{clear(){cleared=true}},state,{replaceState(){state.hash=''}})
 const context=component.setup({}, {expose(){}})
 return {context,calls,state,credential,mounted,cleared:()=>cleared}
}
test('activation page only inspects on open; explicit password submission activates and clears secrets',async()=>{
 const f=activationFixture();await f.mounted();assert.equal(f.state.hash,'');assert.equal(f.calls.length,1);assert.equal(f.calls[0][0],'/auth/activation/inspect');assert.equal(f.context.success.value,false)
 f.context.password.value=randomUUID();f.context.confirmPassword.value='mismatch';await f.context.activate();assert.equal(f.calls.length,1)
 const password=randomUUID();f.context.password.value=password;f.context.confirmPassword.value=password;await f.context.activate()
 assert.deepEqual(f.calls[1],['/auth/activation/complete',{token:f.credential,password,confirmPassword:password}]);assert.equal(f.context.success.value,true);assert.equal(f.context.activatedUsername.value,'synthetic_owner');assert.equal(f.context.password.value,'');assert.equal(f.context.token.value,'');assert.equal(f.cleared(),true)
})
test('invalid or missing invitations stay on activation page without public registration',async()=>{
 const invalid=activationFixture({failure:true});await invalid.mounted();assert.match(invalid.context.error.value,/失效/);assert.equal(invalid.context.success.value,false)
 const missing=activationFixture({missing:true});await missing.mounted();assert.equal(missing.calls.length,0);assert.match(missing.context.error.value,/不能自行注册/)
})
