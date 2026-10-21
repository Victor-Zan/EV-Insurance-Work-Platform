import test from 'node:test'
import assert from 'node:assert/strict'
import {mapStatus} from '../src/features/materials/map-recommendation.ts'
import {workOrderApi} from '../src/features/workorders/work-orders.ts'
test('unknown capability and absent roads are labelled without claiming no repair ability or zero distance',()=>{
  assert.match(mapStatus('CAPABILITY_INCOMPLETE'),/资料未完善.*不代表没有维修能力/)
  assert.match(mapStatus('MOCK_NO_ROAD_DISTANCE'),/不提供真实道路距离/)
  assert.match(mapStatus('MAP_NO_ROUTE'),/距离未知/)
  assert.match(mapStatus('MAP_TIMEOUT'),/可能已计量.*未重试/)
})
test('recommended selection goes through original dispatch/reassign adapters with query revalidation',async()=>{
  const requests=[];const client={post:async(url,body,config)=>{requests.push({url,body,config});return {data:{data:{}}}}}
  const api=workOrderApi(client);await api.dispatch('case',7,false,null,'query');await api.dispatch('case',7)
  const reassign={shopId:7,assignmentVersion:2,reason:'合成原因',vehicleNotArrivedConfirmed:true,transferDescription:null,confirmPossibleDuplicate:false};await api.reassign('case',reassign,'query')
  assert.equal(requests[0].url,'/maps/recommendations/query/dispatch');assert.equal(requests[1].url,'/work-orders/case/dispatch');assert.equal(requests[2].url,'/maps/recommendations/query/reassign');assert.deepEqual(requests[2].body,reassign)
  assert.ok(requests.every(r=>r.config.headers['Idempotency-Key']));assert.equal(new Set(requests.map(r=>r.config.headers['Idempotency-Key'])).size,3)
})
