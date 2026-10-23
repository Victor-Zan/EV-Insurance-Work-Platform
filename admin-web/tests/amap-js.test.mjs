import test from 'node:test'
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import {readFileSync} from 'node:fs'
import {pointFromEvent} from '../src/features/materials/amap-js.ts'
const module=async()=>import('../src/features/materials/amap-js.ts?test='+randomUUID())
const configuration=()=>({publicKey:randomUUID().replaceAll('-',''),servicePath:'/api/v1/maps/js-proxy',expires:new Date(Date.now()+60000).toISOString()})
const documentMock=(append)=>({createElement:()=>({remove(){},src:'',onerror:null}),head:{appendChild:append}})
test('SDK is loaded only on explicit invocation, uses fixed official host and never receives jscode',async()=>{
 const {loadAmapJs}=await module();const browser={};let count=0,script
 const doc=documentMock(s=>{count++;script=s;browser.AMap={Map:class{},Marker:class{}};queueMicrotask(()=>browser.__evAmapReady())})
 assert.equal(count,0);const config=configuration();await loadAmapJs(config,browser,doc,'http://localhost:5210')
 assert.equal(count,1);assert.match(script.src,/^https:\/\/webapi\.amap\.com\/maps\?v=2\.0&key=/);assert.equal(script.referrerPolicy,'no-referrer')
 assert.deepEqual(browser._AMapSecurityConfig,{serviceHost:'http://localhost:5210/api/v1/maps/js-proxy'});assert.equal('securityJsCode' in browser._AMapSecurityConfig,false)
 await loadAmapJs(config,browser,doc,'http://localhost:5210');assert.equal(count,1)
 await assert.rejects(loadAmapJs(configuration(),browser,doc,'http://localhost:5210'),/配置已改变/);assert.equal(count,1)
})
test('invalid proxy and pre-existing configuration are rejected before any script request',async()=>{
 const {loadAmapJs}=await module();let calls=0;const doc=documentMock(()=>calls++)
 await assert.rejects(loadAmapJs({...configuration(),servicePath:'https://evil.invalid'}, {},doc,'http://localhost'),/配置无效/)
 await assert.rejects(loadAmapJs(configuration(),{AMap:{}},doc,'http://localhost'),/已有不同地图配置/);assert.equal(calls,0)
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
test('map component waits for explicit bootstrap and completed map, never uses geolocation or client geocoder/routing',()=>{
 const source=readFileSync(new URL('../src/features/materials/AmapLocationMap.vue',import.meta.url),'utf8')
 assert.match(source,/onMounted\(configurationStatus\)/);assert.match(source,/@click="load"/);assert.match(source,/map\.on\('complete'/)
 assert.doesNotMatch(source,/Geolocation|navigator\.geolocation|AMap\.Geocoder|AMap\.Driving|securityJsCode|SECURITY_CODE|console\./)
 assert.match(source,/method:'DELETE'/)
})
