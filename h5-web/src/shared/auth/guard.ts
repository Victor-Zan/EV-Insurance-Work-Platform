import { permitsPortal } from './session.ts'
import type { AuthSession, Role, User } from './session.ts'
export async function guard(session: AuthSession, path: string, required: Role[], load: () => Promise<User>) {
  if (path === '/login') return true
  if (!session.valid()) return '/login'
  try {
    const user = await load()
    if (!permitsPortal(user.roles)) { session.clear(); return '/login' }
    session.updateUser(user)
    if (required.length && !required.some(role => user.roles.includes(role))) return '/forbidden'
    return true
  } catch (error) {
    if (error && typeof error === 'object' && 'status' in error && error.status === 401) { session.clear(); return '/login' }
    if (error && typeof error === 'object' && 'status' in error && error.status === 403) { session.clear(); return '/login' }
    return '/connection-error'
  }
}
