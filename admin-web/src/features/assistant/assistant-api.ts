import { httpClient } from '@/shared/http/client'
import type { ApiResponse } from '@/shared/http/create-client'
import type { WorkOrderStatus } from '@/features/workorders/work-orders'

export interface AssistantReply {
  blocked?: boolean
  answer: string
  ai: boolean
  model?: string
  source?: string
  reference?: string
  facts?: {
    total: number
    counts: { status: WorkOrderStatus; count: number }[]
    orders: { businessNo: string; status: WorkOrderStatus; updatedAt: string }[]
    totalCny?: string
  }
}

export const assistantApi = {
  async status() {
    return (await httpClient.get<ApiResponse<{ enabled: boolean; model?: string }>>('/admin/assistant/status')).data.data
  },
  async chat(message: string, signal: AbortSignal, onModel: (model: string) => void, history: { message: string; answer: string }[]) {
    const response = await httpClient.post<ReadableStream<Uint8Array>>('/admin/assistant/chat/stream', { message, history }, {
      adapter: 'fetch', responseType: 'stream', timeout: 50_000, signal,
      headers: { Accept: 'application/x-ndjson, application/json' },
    })
    const reader = response.data.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    try {
      for (;;) {
        const chunk = await reader.read()
        if (chunk.done) throw new Error('回答连接已中断，请重试。')
        buffer += decoder.decode(chunk.value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop()!
        for (const line of lines) {
          if (!line.trim()) continue
          const event = JSON.parse(line) as { type: string; model?: string; response?: ApiResponse<AssistantReply> }
          if (event.type === 'model' && event.model) onModel(event.model)
          if (event.type === 'reply' && event.response?.data) return event.response.data
        }
      }
    } finally {
      await reader.cancel().catch(() => {})
      reader.releaseLock()
    }
  },
}
