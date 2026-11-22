import type { AssistantReply } from './assistant-api'

export async function readAssistantError(stream: ReadableStream<Uint8Array>, status: number): Promise<Error> {
  const fallback = status === 401 ? '登录已失效，请重新登录后发送。'
    : status === 403 ? '当前账号无权使用管理员助手。'
      : status === 400 ? '提问或上下文不符合要求，请缩短内容后重试。' : '平台暂未完成请求，问题已保留，请稍后重试。'
  if (status === 401 || status === 403) { void stream.cancel().catch(() => {}); return new Error(fallback) }
  const reader = stream.getReader()
  const decoder = new TextDecoder()
  let body = ''
  try {
    for (;;) {
      const chunk = await reader.read()
      body += decoder.decode(chunk.value, { stream: !chunk.done })
      if (body.length > 8192) return new Error(fallback)
      if (chunk.done) break
    }
    const messages: Record<string, string> = {
      ASSISTANT_PRIVACY_UNAVAILABLE: '本地隐私检查暂不可用，本次未调用模型。请稍后重试。',
      ASSISTANT_MODEL_UNKNOWN: '所选模型已变化，请刷新模型列表后重新选择。',
      ASSISTANT_DISABLED: '所选模型尚未配置，请切换到已配置的模型。',
    }
    try { return new Error(messages[JSON.parse(body)?.code] ?? fallback) }
    catch { return new Error(fallback) }
  } finally {
    void reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}

// A reply can span network chunks; the final JSON line need not end in a newline.
export async function readAssistantStream(stream: ReadableStream<Uint8Array>, onModel: (model: string) => void): Promise<AssistantReply> {
  const reader = stream.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  const parse = (line: string) => {
    if (!line.trim()) return
    let event: { type?: string; model?: string; response?: { data?: AssistantReply } }
    try { event = JSON.parse(line) }
    catch { throw new Error('回答格式异常，问题已保留，请重试。') }
    if (event.type === 'model' && event.model) onModel(event.model)
    if (event.type === 'reply' && typeof event.response?.data?.answer === 'string') return event.response.data
  }
  try {
    for (;;) {
      const chunk = await reader.read()
      buffer += decoder.decode(chunk.value, { stream: !chunk.done })
      const lines = buffer.split('\n')
      buffer = lines.pop()!
      for (const line of lines) {
        const reply = parse(line)
        if (reply) return reply
      }
      if (chunk.done) {
        const reply = parse(buffer)
        if (reply) return reply
        throw new Error('回答连接已中断，问题已保留，请重试或切换模型。')
      }
    }
  } finally {
    // Closing a transport must not keep the composer locked after the final reply.
    void reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}
