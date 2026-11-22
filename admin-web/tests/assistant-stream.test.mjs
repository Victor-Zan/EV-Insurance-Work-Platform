import test from 'node:test'
import assert from 'node:assert/strict'
import { readAssistantStream, readAssistantError } from '../src/features/assistant/assistant-stream.ts'

const bytes = text => new TextEncoder().encode(text)
const stream = text => new ReadableStream({ start(controller) { controller.enqueue(bytes(text)); controller.close() } })

test('split UTF8 and final reply without a newline still complete', async () => {
  const payload = bytes(JSON.stringify({ type: 'model', model: '国产模型' }) + '\n' +
    JSON.stringify({ type: 'reply', response: { data: { answer: '继续回答', ai: true } } }))
  const seen = []
  const source = new ReadableStream({ start(controller) {
    for (const byte of payload) controller.enqueue(new Uint8Array([byte]))
    controller.close()
  } })
  const reply = await readAssistantStream(source, model => seen.push(model))
  assert.equal(reply.answer, '继续回答')
  assert.deepEqual(seen, ['国产模型'])
})

test('a transport that hangs while closing cannot lock the next send after a complete reply', async () => {
  let cancelled = false
  const source = new ReadableStream({
    start(controller) { controller.enqueue(bytes('{"type":"reply","response":{"data":{"answer":"已经完成","ai":true}}}\n')) },
    cancel() { cancelled = true; return new Promise(() => {}) },
  })
  assert.equal((await readAssistantStream(source, () => {})).answer, '已经完成')
  assert.equal(cancelled, true)
  assert.equal(source.locked, false)
})

test('disconnects and malformed replies fail without returning a made-up answer', async () => {
  await assert.rejects(readAssistantStream(stream('{"type":"model","model":"glm"}\n'), () => {}), /连接已中断/)
  await assert.rejects(readAssistantStream(stream('broken\n'), () => {}), /格式异常/)
})

test('pre-model HTTP errors explain the stage and never echo arbitrary response text', async () => {
  assert.match((await readAssistantError(stream('{"code":"ASSISTANT_PRIVACY_UNAVAILABLE"}'), 503)).message, /未调用模型/)
  assert.match((await readAssistantError(stream('{"code":"ASSISTANT_MODEL_UNKNOWN"}'), 400)).message, /刷新模型列表/)
  assert.match((await readAssistantError(stream(''), 401)).message, /重新登录/)
  assert.match((await readAssistantError(stream(''), 403)).message, /无权/)
  assert.doesNotMatch((await readAssistantError(stream('{"message":"untrusted upstream secret"}'), 500)).message, /secret/)
})
