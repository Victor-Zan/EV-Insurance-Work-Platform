import test from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { workOrderApi } from '../src/features/workorders/work-orders.ts'

test('editing UTC instants round-trips without timezone or millisecond changes',()=>{
  const moduleUrl=new URL('../src/features/workorders/work-orders.ts',import.meta.url).href
  for(const timezone of ['Asia/Shanghai','UTC','America/New_York']){
    const script=`import {draftPayload,localDateTimeInput} from ${JSON.stringify(moduleUrl)};
      const original='2026-10-13T06:30:00.123Z';
      const form={claimReportedAt:localDateTimeInput(original),accidentAt:localDateTimeInput(original),accidentRegionId:'',policyNo:'',vehicleVin:'',vehiclePlate:'',vehicleOtherIdentifier:'',accidentAddress:''};
      const result=draftPayload(form);process.stdout.write(JSON.stringify([result.claimReportedAt,result.accidentAt]));`
    const result=JSON.parse(execFileSync(process.execPath,['--input-type=module','-e',script],{env:{...process.env,TZ:timezone},encoding:'utf8'}))
    assert.deepEqual(result,['2026-10-13T06:30:00.123Z','2026-10-13T06:30:00.123Z'])
  }
})
test('regions, eligible shops and history can request pages beyond the former first 100 rows',async()=>{
  const calls=[]
  const client={get:async(url,config)=>{calls.push([url,config.params]);return {data:{data:{page:config.params.page,size:20,total:121,records:[{id:121}]}}}}}
  const api=workOrderApi(client)
  await api.regions(7);await api.shops('case',7);await api.history('case',7)
  assert.deepEqual(calls,[['/pricing/regions',{page:7,size:20}],['/work-orders/case/eligible-shops',{page:7,size:20}],['/work-orders/case/history',{page:7,size:20}]])
})
