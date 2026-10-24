import test from 'node:test'
import assert from 'node:assert/strict'
import {workOrderApi} from '../src/features/workorders/work-orders.ts'

test('manual choice is sent through original quote-independent dispatch contract with recommendation provenance',async()=>{
 const requests=[];const client={post:async(...args)=>{requests.push(args);return {data:{data:{id:'synthetic-case',shopId:22,status:'PENDING_ACCEPTANCE'}}}}};
 const api=workOrderApi(client);
 await api.dispatch('synthetic-case',22,false,null,'synthetic-query');
 assert.equal(requests[0][0],'/maps/recommendations/synthetic-query/dispatch');
 assert.equal(requests[0][1].shopId,22);assert.equal(typeof requests[0][2].headers['Idempotency-Key'],'string');
 await api.dispatch('synthetic-case',33);
 assert.equal(requests[1][0],'/work-orders/synthetic-case/dispatch');assert.equal(requests[1][1].shopId,33);
 await api.dispatch('synthetic-case',44,false,null,'');assert.equal(requests[2][0],'/work-orders/synthetic-case/dispatch');assert.equal(requests[2][1].shopId,44)
})

test('manual eligible candidates explicitly omit mock distance enrichment and retain stable paging',async()=>{
 const calls=[],api=workOrderApi({async get(path,options){calls.push({path,params:options.params});return {data:{data:{records:[],total:0}}}}})
 await api.shops('synthetic-case',2,false)
 assert.deepEqual(calls,[{path:'/work-orders/synthetic-case/eligible-shops',params:{page:2,size:20,includeMockDistances:false}}])
})

test('reassignment keeps assignment version and manual choice, with no map lookup',async()=>{
 const requests=[];const api=workOrderApi({post:async(...args)=>{requests.push(args);return {data:{data:{}}}}});
 const request={shopId:44,assignmentVersion:2,reason:'合成测试改选',vehicleNotArrivedConfirmed:true,transferDescription:null,confirmPossibleDuplicate:false};
 await api.reassign('synthetic-case',request,'synthetic-query');
 assert.equal(requests.length,1);assert.equal(requests[0][0],'/maps/recommendations/synthetic-query/reassign');assert.deepEqual(requests[0][1],request);
})
