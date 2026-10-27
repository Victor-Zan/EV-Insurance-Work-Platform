import test from 'node:test'
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import {readFileSync} from 'node:fs'
import {pointFromEvent} from '../src/features/materials/amap-js.ts'
const module=async()=>import('../src/features/materials/amap-js.ts?test='+randomUUID())
const configuration=()=>({publicKey:randomUUID().replaceAll('-',''),servicePath:'/_AMapService',expires:new Date(Date.now()+60000).toISOString()})
const documentMock=(append)=>({createElement:()=>({remove(){},src:'',onerror:null}),head:{appendChild:append}})
test('SDK is loaded only on explicit invocation, uses fixed official host and never receives jscode',async()=>{
 const {loadAmapJs}=await module();const browser={};let count=0,script
 const doc=documentMock(s=>{count++;script=s;browser.AMap={Map:class{},Marker:class{}};queueMicrotask(()=>browser.__evAmapReady())})
 assert.equal(count,0);const config=configuration();await loadAmapJs(config,browser,doc,'http://localhost:5210')
 assert.equal(count,1);assert.match(script.src,/^https:\/\/webapi\.amap\.com\/maps\?v=2\.0&key=/);assert.equal(script.referrerPolicy,'no-referrer')
 assert.deepEqual(browser._AMapSecurityConfig,{serviceHost:'http://localhost:5210/_AMapService'});assert.equal('securityJsCode' in browser._AMapSecurityConfig,false)
 await loadAmapJs(config,browser,doc,'http://localhost:5210');assert.equal(count,1)
 await assert.rejects(loadAmapJs(configuration(),browser,doc,'http://localhost:5210'),/配置已改变/);assert.equal(count,1)
})
test('invalid proxy and pre-existing configuration are rejected before any script request',async()=>{
 const {loadAmapJs}=await module();let calls=0;const doc=documentMock(()=>calls++)
 await assert.rejects(loadAmapJs({...configuration(),servicePath:'https://evil.invalid'}, {},doc,'http://localhost'),/配置无效/)
 await assert.rejects(loadAmapJs({...configuration(),servicePath:'/api/v1/maps/js-proxy'}, {},doc,'http://localhost'),/配置无效/)
 await assert.rejects(loadAmapJs(configuration(),{AMap:{}},doc,'http://localhost'),/已有不同地图配置/);assert.equal(calls,0)
})

test('local SDK proxy matches only the two resources and explicit session endpoint',async()=>{
 const {default:configure}=await import('../vite.config.ts')
 const config=await configure({mode:'test',command:'serve'})
 const proxies=config.server.proxy
 const aliases=Object.keys(proxies).filter(path=>path!=='/api')
 assert.equal(aliases.length,1)
 const allowed=new RegExp(aliases[0])
 for(const path of ['/v4/map/styles','/v3/vectormap','/session']){
  assert.equal(allowed.test('/_AMapService'+path),true)
  assert.equal(allowed.test('/_AMapService'+path+'?key=synthetic'),true)
 }
 for(const path of ['/v3/geocode/geo','/v5/place/text','/v4/map/styles/extra','/session/extra','/anything'])assert.equal(allowed.test('/_AMapService'+path),false)
 assert.equal(proxies[aliases[0]].changeOrigin,false)
 assert.equal('rewrite' in proxies[aliases[0]],false)
})
test('loader failure does not silently become mock or automatically request again',async()=>{
 const {loadAmapJs}=await module();let calls=0;const doc=documentMock(s=>{calls++;queueMicrotask(()=>s.onerror())}),browser={},config=configuration()
 await assert.rejects(loadAmapJs(config,browser,doc,'http://localhost'),/未自动重试/);await assert.rejects(loadAmapJs(config,browser,doc,'http://localhost'),/未自动重试/);assert.equal(calls,1)
})
test('map events preserve longitude latitude order and reject missing or invalid coordinates',()=>{
 assert.deepEqual(pointFromEvent({lnglat:{getLng:()=>113.1,getLat:()=>23.1}}),{longitude:113.1,latitude:23.1})
 for(const [lon,lat] of [[NaN,23],[113,Infinity],[181,23],[113,91]])assert.equal(pointFromEvent({lnglat:{getLng:()=>lon,getLat:()=>lat}}),undefined)
 assert.equal(pointFromEvent({}),undefined)
})
test('map component supports optional dispatch autoload and completed map, never uses geolocation or client geocoder/routing',()=>{
 const source=readFileSync(new URL('../src/features/materials/AmapLocationMap.vue',import.meta.url),'utf8')
 assert.match(source,/onMounted\(async\(\)=>\{await configurationStatus\(\)/);assert.match(source,/props\.autoLoad&&configuration\.value\?\.enabled&&configuration\.value\.configured/);assert.match(source,/@click="load"/);assert.match(source,/map\.on\('complete'/)
 assert.doesNotMatch(source,/Geolocation|navigator\.geolocation|AMap\.Geocoder|AMap\.Driving|securityJsCode|SECURITY_CODE|console\./)
 assert.match(source,/method:'DELETE'/)
})
