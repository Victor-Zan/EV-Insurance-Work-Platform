import test from 'node:test'
import assert from 'node:assert/strict'
import {quotePayload,chinaInput,deadlinePayload} from '../src/shared/ui/presentation.ts'
test('quote input keeps money exact, including zero and quantities greater than one',()=>{
 assert.deepEqual(quotePayload([{description:' 配件 ',quantity:'3',unitPrice:'90071992547409.99'},{description:'免费检查',quantity:'1',unitPrice:'0.00'}]),[{description:'配件',quantity:3,unitPrice:'90071992547409.99'},{description:'免费检查',quantity:1,unitPrice:'0.00'}])
 for(const quantity of ['0','-1','1.2','2147483648','']) assert.throws(()=>quotePayload([{description:'配件',quantity,unitPrice:'1.00'}]),/正整数/)
 for(const unitPrice of ['-1','1.001','1e2','']) assert.throws(()=>quotePayload([{description:'配件',quantity:'1',unitPrice}]),/最多2位/)
 assert.throws(()=>quotePayload([]),/至少/)
})
test('deadline editing preserves China timezone; clearing explicitly sends null',()=>{
 const input=chinaInput('2026-10-10T06:30:00Z')
 assert.equal(input,'2026-10-10T14:30')
 assert.equal(deadlinePayload(input),'2026-10-10T14:30:00+08:00')
 assert.equal(deadlinePayload(''),null)
 assert.throws(()=>deadlinePayload('2026-10-10'),/中国时间/)
})
