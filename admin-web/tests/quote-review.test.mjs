import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parse,compileScript} from '@vue/compiler-sfc'
import {compile} from '@vue/compiler-dom'
import ts from 'typescript'
import * as Vue from 'vue'
import * as helpers from '../src/features/quotation/quote-review.ts'

test('review calculation uses corrected lines and exact half-up cents, including zero and large values',()=>{
 assert.deepEqual(helpers.reviewCalculation([{description:'核对配件',quantity:'2',unitPrice:'15.25'}],'7.50'),{amounts:['30.50'],base:'30.50',markup:'2.29',total:'32.79'})
 assert.equal(helpers.reviewCalculation([{description:'小额',quantity:'1',unitPrice:'0.05'}],'10').total,'0.06')
 assert.equal(helpers.reviewCalculation([{description:'大额',quantity:'1',unitPrice:'9007199254740993.01'}],'0').total,'9007199254740993.01')
 assert.equal(helpers.reviewCalculation([{description:'零价',quantity:'2',unitPrice:'0'}],'100').total,'0.00')
 for(const bad of ['-1','100.01','1.001',''])assert.throws(()=>helpers.reviewCalculation([{description:'配件',quantity:'1',unitPrice:'1.00'}],bad))
 for(const bad of [{description:'',quantity:'1',unitPrice:'1'},{description:'配件',quantity:'0',unitPrice:'1'},{description:'配件',quantity:'1.5',unitPrice:'1'},{description:'配件',quantity:'2147483648',unitPrice:'1'},{description:'配件',quantity:'1',unitPrice:'1.001'}])assert.throws(()=>helpers.reviewPayload([bad]))
})
const {descriptor}=parse(readFileSync(new URL('../src/features/quotation/QuoteReviewWorkbench.vue',import.meta.url),'utf8'))
function fixture(){
 let code=ts.transpileModule(compileScript(descriptor,{id:'review-test'}).content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText
 code=code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>'const {'+names.replace(/\s+as\s+/g,':')+'}=__vue;').replace(/import .*from ['"].*['"];?/g,'').replace('export default','return')
 code='const {reviewCalculation}=__helpers;const httpClient=__client;const URL=__urls;'+code
 const props=Vue.reactive({originals:[],lines:[{description:'合成配件',quantity:'1',unitPrice:'20.00'}],value:'10',mode:'PERCENTAGE',readonly:false}),calls=[],revoked=[],urls=[];let unmount,resolve
 const component=new Function('__vue','__helpers','__client','__urls',code)({...Vue,watch(){},onUnmounted(fn){unmount=fn}},helpers,{async get(path){calls.push(path);return resolve?await resolve():{data:{type:'image/png'}}}},{createObjectURL(){const url='blob:synthetic-'+urls.length;urls.push(url);return url},revokeObjectURL(url){revoked.push(url)}})
 const context=component.setup(props,{expose(){},emit(name,value){if(name==='lines')props.lines=value;if(name==='value')props.value=value;if(name==='mode'){props.mode=value;props.value='0.00'}}})
 return {props,context,calls,revoked,urls,unmount(){unmount()},defer(fn){resolve=fn}}
}
test('editing rows retains original input snapshots and updates total; multi-image preview releases old URLs',async()=>{
 const f=fixture(),original=f.props.lines[0]
 f.context.edit(0,'unitPrice',{target:{value:'30.25'}});assert.equal(original.unitPrice,'20.00');assert.equal(f.context.calculation.value.value.total,'33.28')
 f.props.originals=[{id:'first',name:'第一页',contentType:'image/png'},{id:'second',name:'第二页',contentType:'image/png'}]
 await f.context.loadImage();assert.equal(f.context.image.value,'blob:synthetic-0')
 f.context.index.value=1;await f.context.loadImage();assert.deepEqual(f.calls,['/materials/first/download','/materials/second/download']);assert.deepEqual(f.revoked,['blob:synthetic-0'])
 let resolve;f.defer(()=>new Promise(r=>{resolve=r}));const pending=f.context.loadImage();f.unmount();resolve({data:{}});await pending;assert.equal(f.context.image.value,'');assert.equal(f.urls.length,2)
})
test('preview has only disabled row and rate inputs, while regular review permits edits',()=>{
 const f=fixture(),{code}=compile(descriptor.template.content,{mode:'function',prefixIdentifiers:true,expressionPlugins:['typescript']})
 const render=new Function('Vue',ts.transpileModule(code,{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText)(Vue)
 function inputs(){const out=[];function visit(v){if(!v||typeof v!=='object')return;if(v.type==='input')out.push(v);if(Array.isArray(v.children))v.children.forEach(visit)}visit(render(Vue.proxyRefs({...f.context,...f.props}),[]));return out}
 assert.equal(inputs().length,4);assert.equal(inputs().every(n=>!n.props.disabled),true)
 f.props.readonly=true;assert.equal(inputs().every(n=>n.props.disabled),true)
})
test('every workflow navigation retains current task, case materials and history without funds or complaint buttons',()=>{
 const {descriptor:parent}=parse(readFileSync(new URL('../src/features/workorders/WorkOrderDetailView.vue',import.meta.url),'utf8'))
 const nav=parent.template.content.match(/<nav class="related-links"[\s\S]*?<\/nav>/)[0]
 const {code}=compile(nav,{mode:'function',prefixIdentifiers:true}),render=new Function('Vue',code)(Vue)
 for(const activeSection of ['overview','dispatch','quotation','repair','history']){
  const nodes=[];function visit(v){if(!v||typeof v!=='object')return;if(v.props?.onClick)nodes.push(v);if(Array.isArray(v.children))v.children.forEach(visit)}
  const visits=[];visit(render({activeSection,order:{status:'QUOTE_REVIEWING'},route:{path:'/synthetic'},router:{replace(value){visits.push(value)}}},[]))
  for(const node of nodes)node.props.onClick();assert.deepEqual(visits.map(v=>v.query?.section??'current'),['current','overview','history'])
 }
})

test('completed navigation has only case details and records, highlights selection, and removes the task sidebar',()=>{
 const source=readFileSync(new URL('../src/features/workorders/WorkOrderDetailView.vue',import.meta.url),'utf8'),template=parse(source).descriptor.template.content
 const nav=template.match(/<nav class="related-links"[\s\S]*?<\/nav>/)[0],{code}=compile(nav,{mode:'function',prefixIdentifiers:true}),render=new Function('Vue',code)(Vue)
 for(const activeSection of ['overview','history']){
  const nodes=[],visits=[];function visit(v){if(!v||typeof v!=='object')return;if(v.props?.onClick)nodes.push(v);if(Array.isArray(v.children))v.children.forEach(visit)}
  visit(render({activeSection,receiptMode:false,order:{status:'COMPLETED'},route:{path:'/completed-test'},router:{replace(value){visits.push(value)}}},[]))
  assert.equal(nodes.length,2);for(const node of nodes)node.props.onClick()
  assert.deepEqual(visits.map(v=>v.query.section),['overview','history'])
  assert.equal(nodes.find(n=>n.props.type==='primary').key,activeSection)
 }
 const opening=template.match(/<aside v-if="[^"]+" class="control-panel glass"[^>]*>/)[0]
 const renderAside=new Function('Vue',compile(opening+'</aside>',{mode:'function',prefixIdentifiers:true}).code)(Vue)
 assert.notEqual(renderAside({order:{status:'COMPLETED'}},[]).type,'aside')
 assert.equal(renderAside({order:{status:'REPAIRING'}},[]).type,'aside')
})

test('fixed markup adds exact cents while percentage applies only to the reviewed total',()=>{
 const lines=[{description:'合成核对清单',quantity:'1',unitPrice:'460.00'}]
 assert.deepEqual(helpers.reviewCalculation(lines,'69.00','FIXED_AMOUNT'),{amounts:['460.00'],base:'460.00',markup:'69.00',total:'529.00'})
 assert.equal(helpers.reviewCalculation(lines,'15','PERCENTAGE').total,'529.00')
 assert.equal(helpers.reviewCalculation(lines,'150.25','FIXED_AMOUNT').total,'610.25')
 assert.equal(helpers.reviewCalculation(lines,'0','FIXED_AMOUNT').total,'460.00')
 assert.equal(helpers.reviewCalculation(lines,'9007199254740993.01','FIXED_AMOUNT').total,'9007199254741453.01')
 for(const value of ['-1','1.001','1x00',''])assert.throws(()=>helpers.reviewCalculation(lines,value,'FIXED_AMOUNT'))
 assert.throws(()=>helpers.reviewCalculation(lines,'100.01','PERCENTAGE'))
})
test('markup selector switches units, resets the input and respects read-only state',()=>{
 const f=fixture(),{code}=compile(descriptor.template.content,{mode:'function',prefixIdentifiers:true,expressionPlugins:['typescript']})
 const render=new Function('Vue',ts.transpileModule(code,{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText)(Vue)
 function nodes(){const found=[];function visit(v){if(!v||typeof v!=='object')return;if(v.type==='select'||v.type==='input')found.push(v);if(Array.isArray(v.children))v.children.forEach(visit)}visit(render(Vue.proxyRefs({...f.context,...f.props}),[]));return found}
 const selector=nodes().find(v=>v.type==='select')
 selector.props.onChange({target:{value:'FIXED_AMOUNT'}})
 assert.equal(f.props.mode,'FIXED_AMOUNT');assert.equal(f.props.value,'0.00')
 const fixed=nodes().find(v=>v.props['aria-label']==='加价金额（元）')
 fixed.props.onInput({target:{value:'69.00'}})
 assert.equal(f.context.calculation.value.value.total,'89.00')
 f.props.readonly=true;assert.equal(nodes().every(v=>v.props.disabled),true)
})
