import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'
const {descriptor}=parse(readFileSync(new URL('../src/features/funds/AnalyticsView.vue',import.meta.url),'utf8'))
function fixture(roles=['ADMIN']) {
  let code=ts.transpileModule(compileScript(descriptor,{id:'analytics-test'}).content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText
  code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>'const {'+names.replace(/\s+as\s+/g,':')+'}=__vue;').replace(/import .*from ['"].*['"];?/g,'').replace('export default','return')
  code='const {httpClient,currentUser}=__deps;const RouterLink={},AnalyticsChart={},FundsNavigation={},ElButton={},ElDatePicker={},ElSelect={},ElOption={},ElTable={},ElTableColumn={},ElPagination={},ElCheckbox={},ElProgress={};'+code
  const calls=[],cleanup=[]
  let nextFailure=false,hold
  const deps={currentUser:Vue.ref({roles}),httpClient:{async get(path,{params}){
    calls.push({path,params:structuredClone(params)})
    if(path.endsWith('/groups'))return {data:{data:{records:[{id:1,name:'合成网点',profit:'10.00',completed:1}],total:1}}}
    if(nextFailure){nextFailure=false;throw Error('合成网络错误')}
    if(hold){const wait=hold;hold=null;await wait}
    return {data:{data:{summary:{completed:params.shop??1,accounted:1,insurance:'100.00',payable:'90.00',profit:'10.00',margin:'10.00'},grain:'day',trend:[{bucket:'2026-11-01',completed:1,profit:'10.00'}],details:{records:[],total:0}}}}
  }}}
  const scope=Vue.effectScope()
  const component=new Function('__vue','__deps',code)({...Vue,onBeforeUnmount(fn){cleanup.push(fn)}},deps)
  const context=scope.run(()=>component.setup({}, {expose(){}}))
  return {context,calls,fail(){nextFailure=true},hold(promise){hold=promise},stop(){cleanup.forEach(fn=>fn());scope.stop()}}
}
const settle=async()=>{await Vue.nextTick();await new Promise(resolve=>setImmediate(resolve));await Vue.nextTick()}
test('dashboard default and reset span previous months using Shanghai calendar dates', async () => {
  const f = fixture()
  try {
    await settle()
    assert.deepEqual(f.context.recentCompletionDates(new Date('2026-11-11T16:01:00Z')), ['2026-08-15', '2026-11-12'])
    const initial = [...f.context.dates.value]
    f.context.dates.value = ['2026-11-01', '2026-11-12']
    await settle()
    f.context.reset()
    await settle()
    const request = f.calls.filter(x => x.path === '/funds/analytics').at(-1).params
    assert.deepEqual([request.from, request.to], initial)
  } finally {
    f.stop()
  }
})
test('analytics automatically refreshes filters and retains summary scope during date drilldown',async()=>{
  const f=fixture();await settle()
  f.context.dates.value=['2026-11-01','2026-11-11'];await settle()
  f.context.shop.value=7;await settle()
  assert.equal(f.calls.filter(x=>!x.path.endsWith('/groups')).at(-1).params.shop,7)
  f.context.selectDate(0);await settle()
  const request=f.calls.filter(x=>!x.path.endsWith('/groups')).at(-1).params
  assert.equal(request.from,'2026-11-01');assert.equal(request.to,'2026-11-11')
  assert.equal(request.detailFrom,'2026-11-01');assert.equal(request.detailTo,'2026-11-01')
  f.context.pending.value=true;await settle()
  assert.equal(f.calls.filter(x=>!x.path.endsWith('/groups')).at(-1).params.pending,true)
  f.stop()
})
test('analytics ignores stale responses, surfaces failures and recovers on retry',async()=>{
  const f=fixture();await settle()
  let release;f.hold(new Promise(resolve=>{release=resolve}))
  f.context.shop.value=2;await Vue.nextTick()
  f.context.shop.value=3;await settle()
  release();await settle()
  assert.equal(f.context.data.value.summary.completed,3)
  f.fail();await f.context.load();assert.match(f.context.error.value,/网络错误/)
  assert.equal(f.context.data.value,undefined)
  await f.context.load();assert.equal(f.context.error.value,'');assert.equal(f.context.data.value.summary.completed,3)
  f.stop()
})
test('staff dashboard does not request staff rankings or expose another creator filter',async()=>{
  const f=fixture(['CUSTOMER_SERVICE']);await settle()
  assert.equal(f.context.admin.value,false)
  assert.equal(f.calls.some(x=>x.params.dimension==='creator'),false)
  f.context.selectGroup({id:1,name:'合成网点'});await settle()
  assert.equal(f.context.shop.value,1);assert.equal(f.context.view.value,'shop')
  f.stop()
})
