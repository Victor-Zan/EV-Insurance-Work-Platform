import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { statusLabels } from '../src/features/workorders/work-orders.ts'
import { AuthSession } from '../src/shared/auth/session.ts'
import { guard } from '../src/shared/auth/guard.ts'

test('shop and owner can enter scoped work-order routes on H5',async()=>{
  for(const role of ['REPAIR_SHOP','OWNER']){
    const map=new Map(),storage={getItem:key=>map.get(key)??null,setItem:(key,value)=>map.set(key,value),removeItem:key=>map.delete(key)}
    const session=new AuthSession(storage),user={id:1,username:'h5',displayName:'H5 用户',roles:[role]}
    session.set({accessToken:'token',expiresAt:new Date(Date.now()+60000).toISOString(),user})
    assert.equal(await guard(session,'/work-orders',['REPAIR_SHOP','OWNER'],async()=>user),true)
    session.clear()
  }
  assert.equal(statusLabels.PENDING_ACCEPTANCE,'待接单');assert.equal(statusLabels.ARRIVAL_EXCEPTION,'到店异常')
  const router=readFileSync(new URL('../src/router/index.ts',import.meta.url),'utf8')
  assert.match(router,/\/work-orders\/:id/);assert.match(router,/roles: \['REPAIR_SHOP', 'OWNER'\]/)
})

test('shop actions carry assignment version and remain within phase four',()=>{
  const api=readFileSync(new URL('../src/features/workorders/work-orders.ts',import.meta.url),'utf8')
  const view=readFileSync(new URL('../src/features/workorders/WorkOrderDetailView.vue',import.meta.url),'utf8')
  assert.match(api,/assignmentVersion/);assert.match(api,/continue-waiting/)
  assert.doesNotMatch(api,/materials|uploadArrival|transport/);assert.doesNotMatch(view,/Uploader|上传到店照片/)
})
