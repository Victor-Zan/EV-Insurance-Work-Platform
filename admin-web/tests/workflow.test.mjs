import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compile } from '@vue/compiler-dom'
import { parse } from '@vue/compiler-sfc'
import { safeCaseSection } from '../src/features/workorders/workflow.ts'
import * as Vue from 'vue'

test('old links only open the current task; associated records remain accessible',()=>{
  for(const link of ['dispatch','quotation','repair','materials','unknown'])assert.equal(safeCaseSection(link,'INSURER_ASSESSMENT'),'quotation')
  for(const link of ['funds','complaint','history'])assert.equal(safeCaseSection(link,'QUOTE_REVIEW'),link)
  for(const link of ['funds','complaint'])assert.equal(safeCaseSection(link,'CREATE'),'overview')
  assert.equal(safeCaseSection('repair','CREATE'),'overview')
  assert.equal(safeCaseSection('quotation','REPAIR'),'repair')
  assert.equal(safeCaseSection('', 'REPAIR','WAITING_REPAIR_AUTHORIZATION'),'quotation')
  assert.equal(safeCaseSection('', 'REPAIR','REPAIRING'),'repair')
  assert.equal(safeCaseSection('', 'FUNDS','WAITING_INSURER_PAYMENT'),'funds')
  assert.equal(safeCaseSection('', 'FUNDS','WAITING_SHOP_SETTLEMENT'),'funds')
})
test('real timeline renderer has nine noninteractive nodes, distinct actor states and material-independent green',()=>{
  const source=readFileSync(new URL('../src/features/workorders/WorkflowTimeline.vue',import.meta.url),'utf8')
  const {descriptor}=parse(source),{code}=compile(descriptor.template.content,{mode:'function',prefixIdentifiers:true})
  const render=new Function('Vue',code)(Vue)
  const nodes=Array.from({length:9},(_,index)=>({key:'NODE'+index,label:'节点'+index,state:index<4?'DONE':index===4?'CURRENT':'LOCKED'}))
  const vnode=render({workflow:{nodes,currentNode:'NODE4',missing:['缺少核损单']}},[])
  const elements=[];function visit(node){if(!node||typeof node!=='object')return;if(typeof node.type==='string')elements.push(node);if(Array.isArray(node.children))node.children.forEach(visit)}visit(vnode)
  assert.equal(elements.filter(el=>el.type==='li').length,9)
  assert.equal(elements.some(el=>['button','a'].includes(el.type)),false)
  assert.equal(elements.find(el=>el.props?.['aria-current']==='step').props.class,'current')
  nodes[4].state='WAITING';const waiting=render({workflow:{nodes,currentNode:'NODE4'}},[]);elements.length=0;visit(waiting)
  assert.equal(elements.find(el=>el.props?.['aria-current']==='step').props.class,'waiting')
})
