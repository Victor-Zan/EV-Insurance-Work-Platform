import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { draftPayload, idempotencyKey, statusLabels } from '../src/features/workorders/work-orders.ts'
import { AuthSession } from '../src/shared/auth/session.ts'
import { guard } from '../src/shared/auth/guard.ts'

const form={insuranceCompany:'平安保险',claimNo:'PA-1',claimReportedAt:'',ownerName:'测试车主',ownerPhone:'13800000000',policyNo:'  ',vehicleBrand:'测试品牌',vehicleModel:'测试车型',vehicleVin:'VIN-1',vehiclePlate:' ',vehicleOtherIdentifier:' ',accidentAt:'2026-10-07T09:00:00',accidentRegionId:'3',accidentAddress:' ',accidentDescription:'测试事故'}
test('draft payload keeps exact business values and sends instants, numeric region and null optionals',()=>{
  const payload=draftPayload(form)
  assert.equal(payload.claimReportedAt,null);assert.match(payload.accidentAt,/Z$/)
  assert.equal(payload.accidentRegionId,3);assert.equal(payload.policyNo,null);assert.equal(payload.vehiclePlate,null)
  assert.equal(payload.vehicleVin,'VIN-1');assert.equal(payload.accidentAddress,null);assert.equal(statusLabels.ARRIVAL_EXCEPTION,'到店异常')
})
test('state-changing operations receive distinct idempotency keys',()=>{
  const first=idempotencyKey('submit'),second=idempotencyKey('submit')
  assert.match(first,/^submit-/);assert.notEqual(first,second)
})
test('work-order routes are limited to admin-side staff roles',async()=>{
  const map=new Map(),storage={getItem:key=>map.get(key)??null,setItem:(key,value)=>map.set(key,value),removeItem:key=>map.delete(key)}
  const session=new AuthSession(storage),user={id:1,username:'cs',displayName:'客服',roles:['CUSTOMER_SERVICE']}
  session.set({accessToken:'token',expiresAt:new Date(Date.now()+60000).toISOString(),user})
  assert.equal(await guard(session,'/work-orders',['ADMIN','CUSTOMER_SERVICE'],async()=>user),true)
  const router=readFileSync(new URL('../src/router/index.ts',import.meta.url),'utf8')
  assert.match(router,/\/work-orders\/:id/);assert.match(router,/roles: \['ADMIN', 'CUSTOMER_SERVICE'\]/)
  session.clear()
})

test('phase-four UI does not expose phase-five materials or transport operations',()=>{
  const source=readFileSync(new URL('../src/features/workorders/work-orders.ts',import.meta.url),'utf8')
  assert.doesNotMatch(source,/materials|transport|uploadArrival/)
  assert.match(source,/continue-waiting/);assert.match(source,/critical-fields/);assert.match(source,/cancel-assignment/);assert.match(source,/work-orders\/configuration/)
})
