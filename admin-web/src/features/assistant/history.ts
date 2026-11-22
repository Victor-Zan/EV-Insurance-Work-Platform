import type { AssistantReply } from './assistant-api'

export interface AssistantTurn { message: string; reply: AssistantReply }
export interface AssistantDraft { id: string; text: string; updatedAt: number; turns?: AssistantTurn[]; model?: string }
export interface AssistantHistory { activeId: string | null; drafts: AssistantDraft[] }
type HistoryStorage = Pick<Storage, 'getItem' | 'setItem'>
const storageKey = (userId: number) => `ev-admin-assistant:v1:${userId}`

export function readHistory(storage: HistoryStorage, userId: number): AssistantHistory {
  const raw = storage.getItem(storageKey(userId))
  if (!raw) return { activeId: null, drafts: [] }
  const saved = JSON.parse(raw) as AssistantHistory
  if (!saved || !Array.isArray(saved.drafts) || !saved.drafts.every(draft => draft &&
    typeof draft.id === 'string' && typeof draft.text === 'string' && Number.isFinite(draft.updatedAt) &&
    (draft.model === undefined || typeof draft.model === 'string') &&
    (draft.turns === undefined || (Array.isArray(draft.turns) && draft.turns.every(turn =>
      typeof turn?.message === 'string' && typeof turn.reply?.answer === 'string')))) ||
    new Set(saved.drafts.map(draft => draft.id)).size !== saved.drafts.length) {
    throw new Error('本地会话记录无法读取')
  }
  return { drafts: saved.drafts, activeId: saved.drafts.some(draft => draft.id === saved.activeId) ? saved.activeId : null }
}

export function writeHistory(storage: HistoryStorage, userId: number, history: AssistantHistory) {
  storage.setItem(storageKey(userId), JSON.stringify(history))
}
