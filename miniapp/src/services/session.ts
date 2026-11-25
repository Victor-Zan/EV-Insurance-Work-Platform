import { shallowRef } from 'vue'

export type BusinessRole = 'OWNER' | 'REPAIR_SHOP'
export type SessionUser = { id: number; username: string; displayName: string; role: BusinessRole; shopId: number | null }
export type Session = { accessToken: string; expiresAt: number; user: SessionUser }
export const session = shallowRef<Session | null>(null)
let generation = 0
let timer: ReturnType<typeof setTimeout> | undefined
const cleanup = new Set<() => void>()
export const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null
export const sessionGeneration = () => generation
export function onSessionCleared(callback: () => void) { cleanup.add(callback); return () => { cleanup.delete(callback) } }
export function clearSession() {
  generation++
  if (timer) clearTimeout(timer)
  timer = undefined
  session.value = null
  cleanup.forEach(callback => callback())
}
export function currentSession() {
  if (session.value && session.value.expiresAt <= Date.now()) clearSession()
  return session.value
}
export function sameSession(stamp: number) { currentSession(); return generation === stamp }
function userFrom(value: unknown, login: boolean): SessionUser {
  if (!isRecord(value) || typeof value.id !== 'number' || !Number.isSafeInteger(value.id) || value.id <= 0
    || !Array.isArray(value.roles) || value.roles.length !== 1 || ['OWNER', 'REPAIR_SHOP'].indexOf(value.roles[0]) < 0
    || (login && (value.enabled !== true || value.activated !== true))) throw new Error('账号身份无效，请联系工作人员核实。')
  const role = value.roles[0] as BusinessRole
  if (role === 'REPAIR_SHOP' && (typeof value.shopId !== 'number' || !Number.isSafeInteger(value.shopId) || value.shopId <= 0))
    throw new Error('维修网点归属无效，请联系工作人员核实。')
  if (role === 'OWNER' && value.shopId != null) throw new Error('账号归属异常，请联系工作人员核实。')
  return { id: value.id, username: typeof value.username === 'string' ? value.username : '',
    displayName: typeof value.displayName === 'string' ? value.displayName : '', role, shopId: role === 'OWNER' ? null : value.shopId as number }
}
export function acceptSession(data: unknown, expectedGeneration: number) {
  if (!sameSession(expectedGeneration)) throw new Error('登录操作已取消，请重新登录。')
  if (!isRecord(data) || typeof data.accessToken !== 'string' || !data.accessToken.trim() || typeof data.expiresAt !== 'string')
    throw new Error('登录结果无效，请重试。')
  const user = userFrom(data.user, true), expiresAt = Date.parse(data.expiresAt)
  if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) throw new Error('登录已过期，请重试。')
  clearSession()
  session.value = { accessToken: data.accessToken, expiresAt, user }
  timer = setTimeout(clearSession, Math.min(expiresAt - Date.now(), 2_147_483_647))
}
export function updateCurrentUser(data: unknown, stamp: number) {
  if (!sameSession(stamp)) throw new Error('账号已变化，请重新登录。')
  const active = currentSession()
  if (!active) throw new Error('请先登录。')
  try {
    const user = userFrom(data, false)
    if (user.id !== active.user.id || user.role !== active.user.role || user.shopId !== active.user.shopId)
      throw new Error('账号身份或网点已变化，请重新登录。')
    session.value = { ...active, user }
  } catch (error) { clearSession(); throw error }
}
