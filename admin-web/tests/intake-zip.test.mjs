import test from 'node:test'
import assert from 'node:assert/strict'
import {BlobWriter,TextReader,Uint8ArrayReader,ZipWriter} from '@zip.js/zip.js'
import {readChatFiles} from '../src/features/workorders/intake.ts'

async function archive(entries,options={}){
 const writer=new ZipWriter(new BlobWriter(),{useWebWorkers:false,...options})
 for(const [name,value] of entries)await writer.add(name,typeof value==='string'?new TextReader(value):new Uint8ArrayReader(value))
 return new File([await writer.close()],'合成聊天.zip',{type:'application/zip'})
}
test('ZIP and folder imports preserve the same nested Chinese text paths and photo bytes',async()=>{
 const photo=new Uint8Array([255,216,255,0])
 const entries=[['聊天/乙/记录.txt','案件号：乙'],['聊天/甲/记录.txt','案件号：甲'],['聊天/图片/物损.jpg',photo]]
 const files=entries.map(([name,value])=>{const file=new File([value],name.split('/').at(-1));Object.defineProperty(file,'webkitRelativePath',{value:name});return file})
 const zipped=await readChatFiles([await archive(entries)]),folder=await readChatFiles(files)
 assert.equal(zipped.text,folder.text);assert.match(zipped.text,/聊天\/甲\/记录.txt/)
 assert.equal(zipped.photos[0].name,'物损.jpg');assert.equal(zipped.photos[0].type,'image/jpeg')
 assert.deepEqual(new Uint8Array(await zipped.photos[0].arrayBuffer()),photo)
})
test('ZIP rejects missing text, unsupported files, traversal paths, encryption and malformed archives',async()=>{
 await assert.rejects(readChatFiles([await archive([])]),/没有UTF-8 TXT/)
 await assert.rejects(readChatFiles([await archive([['image.png','image']])]),/没有UTF-8 TXT/)
 await assert.rejects(readChatFiles([await archive([['a.txt','text'],['a.pdf','pdf']])]),/仅支持TXT/)
 await assert.rejects(readChatFiles([await archive([['../a.txt','text']])]),/异常文件路径/)
 await assert.rejects(readChatFiles([await archive([['a.txt','text']],{password:'synthetic-only',zipCrypto:true})]),/加密/)
 await assert.rejects(readChatFiles([new File(['invalid'],'broken.zip')]),/ZIP无法读取/)
 await assert.rejects(readChatFiles([new File(['invalid'],'a.zip'),new File(['text'],'a.txt')]),/一次选择一个ZIP/)
})
test('ZIP applies photo count, per-photo size and total text limits before accepting a batch',async()=>{
 await assert.rejects(readChatFiles([await archive([['a.txt','text'],...Array.from({length:21},(_,i)=>[`${i}.png`,'image'])])]),/最多20张/)
 await assert.rejects(readChatFiles([await archive([['a.txt','text'],['large.jpg',new Uint8Array(10485761)]])]),/10 MiB/)
 await assert.rejects(readChatFiles([await archive([['a.txt','x'.repeat(100001)],['b.txt','x'.repeat(100000)]])]),/200000字节/)
 await assert.rejects(readChatFiles([await archive([['a.txt',new Uint8Array([255])]])]),TypeError)
 await assert.rejects(readChatFiles([await archive([['a.txt','x'.repeat(50001)]])]),/50000字符/)
 await assert.rejects(readChatFiles([await archive([['a.txt',Array(1001).fill('行').join('\n')]])]),/1000行/)
})
test('ZIP detects damaged contents and cannot evade output limits by forging the declared size',async()=>{
 const original=await archive([['a.txt','synthetic-content']],{level:0})
 const bytes=Buffer.from(await original.arrayBuffer());bytes[bytes.indexOf('synthetic-content')]=88
 await assert.rejects(readChatFiles([new File([bytes],'damaged.zip')]),/校验失败/)
 const bomb=Buffer.from(await (await archive([['a.txt','x'.repeat(200001)]])).arrayBuffer())
 const central=bomb.indexOf(Buffer.from([0x50,0x4b,0x01,0x02]));assert.ok(central>=0)
 bomb.writeUInt32LE(1,central+24)
 await assert.rejects(readChatFiles([new File([bomb],'forged.zip')]),/200000字节|校验失败/)
})
