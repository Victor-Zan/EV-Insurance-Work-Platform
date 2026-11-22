import { httpClient, authSession } from '@/shared/http/client'
import type { ApiResponse } from '@/shared/http/create-client'
import type { WorkOrderStatus } from '@/features/workorders/work-orders'
import { readAssistantStream, readAssistantError } from './assistant-stream'

export interface AssistantModel { id: string; name: string; provider: string; enabled: boolean }

export interface AssistantReply {
  blocked?: boolean
  errorCode?: string | null
  contextEligible?: boolean
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
    return (await httpClient.get<ApiResponse<{ enabled: boolean; model?: string; defaultModel: string; models: AssistantModel[] }>>('/admin/assistant/status')).data.data
  },
  async chat(message: string, signal: AbortSignal, onModel: (model: string) => void, history: { message: string; answer: string }[], model?: string) {
    const deadline = AbortSignal.timeout(95_000)
    try {
      const response = await httpClient.post<ReadableStream<Uint8Array>>('/admin/assistant/chat/stream', { message, history, model }, {
        adapter: 'fetch', responseType: 'stream', timeout: 0, signal: AbortSignal.any([signal, deadline]), validateStatus: () => true,
        headers: { Accept: 'application/x-ndjson, application/json' },
      })
      if (response.status < 200 || response.status >= 300) {
        if (response.status === 401) authSession.clear()
        throw await readAssistantError(response.data, response.status)
      }
      return await readAssistantStream(response.data, onModel)
    } catch (failure) {
      if (deadline.aborted && !signal.aborted) throw new Error('等待回答超时，问题已保留。请重试或切换模型。', { cause: failure })
      throw failure
    }
  },
}
