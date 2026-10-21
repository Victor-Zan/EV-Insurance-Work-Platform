import test from 'node:test'
import assert from 'node:assert/strict'
import { createUnreadCounter } from '../src/features/notification/unread-counter.ts'

test('unread count follows new notices, read updates and the 99+ display threshold',async()=>{
 let serverCount=0
 const counter=createUnreadCounter(async()=>serverCount)
 await counter.refresh();assert.equal(counter.count.value,0)
 serverCount=12;await counter.refresh();assert.equal(counter.count.value,12);assert.equal(counter.label.value,'12')
 serverCount=11;await counter.refresh();assert.equal(counter.count.value,11)
 serverCount=100;await counter.refresh();assert.equal(counter.count.value,100);assert.equal(counter.label.value,'99+')
 serverCount=0;await counter.refresh();assert.equal(counter.count.value,0)
})

test('a late response cannot overwrite a newer count',async()=>{
 const pending=[]
 const counter=createUnreadCounter(()=>new Promise(resolve=>pending.push(resolve)))
 const old=counter.refresh(),fresh=counter.refresh()
 pending[1](3);await fresh;pending[0](9);await old
 assert.equal(counter.count.value,3)
})

test('account reset discards old requests, including requests that fail after reset',async()=>{
 const pending=[]
 const counter=createUnreadCounter(()=>new Promise((resolve,reject)=>pending.push({resolve,reject})))
 const old=counter.refresh();counter.reset()
 const fresh=counter.refresh();pending[1].resolve(2);await fresh
 pending[0].resolve(40);await old;assert.equal(counter.count.value,2)
 const failed=counter.refresh();counter.reset();pending[2].reject(new Error('offline'));await failed
 assert.equal(counter.count.value,0)
})

test('a failed refresh retains the last server count instead of inventing a value',async()=>{
 let fail=false
 const counter=createUnreadCounter(async()=>{if(fail)throw new Error('offline');return 8})
 await counter.refresh();fail=true;await counter.refresh();assert.equal(counter.count.value,8)
})
