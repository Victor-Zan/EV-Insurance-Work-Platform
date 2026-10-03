export type Role = 'ADMIN' | 'CUSTOMER_SERVICE' | 'REPAIR_SHOP' | 'OWNER'
export interface User { id: number; username: string; displayName: string; roles: Role[]; shopId?: number; enabled?: boolean }
export interface LoginResult { accessToken: string; expiresAt: string; user: User }
export const portal = 'H5'
export const allowedRoles: Role[] = ['REPAIR_SHOP', 'OWNER']
export const permitsPortal = (roles: Role[]) => roles.length > 0 && roles.every(role => allowedRoles.includes(role))
type StorageLike = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

export class AuthSession {
  token: string | null = null
  expiresAt = 0
  user: User | null = null
  private timer: ReturnType<typeof setTimeout> | undefined
  private listeners = new Set<() => void>()
  private storage: StorageLike
  private now: () => number
  constructor(storage: StorageLike, now = Date.now) {
    this.storage = storage; this.now = now
    try {
      const raw = storage.getItem('ev-auth')
      if (raw) {
        const saved: unknown = JSON.parse(raw)
        if (saved && typeof saved === 'object' && 'token' in saved && typeof saved.token === 'string'
            && 'expiresAt' in saved && typeof saved.expiresAt === 'number' && saved.expiresAt > now()) {
          this.token = saved.token; this.expiresAt = saved.expiresAt; this.schedule()
        } else this.clear()
      }
    } catch { this.clear() }
  }
  subscribe(listener: () => void) { this.listeners.add(listener); return () => this.listeners.delete(listener) }
  private changed() { this.listeners.forEach(listener => listener()) }
  set(result: LoginResult) {
    if (!permitsPortal(result.user.roles)) { this.clear(); throw new Error('该账号不能登录当前端，请使用对应端登录') }
    const expiresAt = Date.parse(result.expiresAt)
    if (!Number.isFinite(expiresAt) || expiresAt <= this.now()) { this.clear(); throw new Error('登录已过期，请重新登录') }
    this.token = result.accessToken; this.expiresAt = expiresAt; this.user = result.user
    this.storage.setItem('ev-auth', JSON.stringify({ token: this.token, expiresAt: this.expiresAt }))
    this.schedule(); this.changed()
  }
  updateUser(user: User) { this.user = user; this.changed() }
  valid() { if (this.token && this.expiresAt <= this.now()) this.clear(); return this.token !== null }
  clear() {
    if (this.timer) clearTimeout(this.timer)
    this.token = null; this.expiresAt = 0; this.user = null
    this.storage.removeItem('ev-auth'); this.changed()
  }
  private schedule() {
    if (this.timer) clearTimeout(this.timer)
    this.timer = setTimeout(() => this.clear(), Math.min(this.expiresAt - this.now(), 2_147_483_647))
  }
}
