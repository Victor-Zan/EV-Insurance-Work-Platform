import { shallowRef } from 'vue'
import { AuthSession } from '../auth/session.ts'
import { createClient } from './create-client.ts'
export const authSession = new AuthSession(sessionStorage)
export const currentUser = shallowRef(authSession.user)
authSession.subscribe(() => { currentUser.value = authSession.user })
export const api = createClient(authSession, import.meta.env.VITE_API_BASE_URL ?? '/api/v1')
export const httpClient = api.client
