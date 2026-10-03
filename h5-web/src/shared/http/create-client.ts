import axios from 'axios'
import type { AuthSession, User, LoginResult } from '../auth/session.ts'
import { portal } from '../auth/session.ts'
export interface ApiResponse<T> { code: string; message: string; data: T; traceId: string }
export interface Page<T> { page: number; size: number; total: number; records: T[] }
export class ApiError extends Error {
  code: string
  status?: number
  constructor(message: string, code: string, status?: number) { super(message); this.code = code; this.status = status }
}
export function createClient(session: AuthSession, baseURL: string) {
  const client = axios.create({ baseURL, timeout: 10_000, headers: { 'Content-Type': 'application/json' } })
  client.interceptors.request.use(config => {
    if (session.valid() && !config.url?.endsWith('/auth/login')) config.headers.Authorization = 'Bearer ' + session.token
    return config
  })
  client.interceptors.response.use(response => response, error => {
    if (!axios.isAxiosError<ApiResponse<unknown>>(error)) return Promise.reject(error)
    const status = error.response?.status
    if (status === 401) session.clear()
    const message = status === 401 ? (error.config?.url?.endsWith('/auth/login') ? '账号或密码错误' : '登录失效，请重新登录')
      : status === 403 ? (error.response?.data.code === 'PORTAL_MISMATCH' ? '该账号不能登录当前端，请使用对应端登录' : '没有访问权限')
      : !error.response ? '网络连接失败，请检查后重试' : error.response.data.message || '请求失败，请重试'
    return Promise.reject(new ApiError(message, error.response?.data.code ?? 'NETWORK_ERROR', status))
  })
  return {
    client,
    async login(username: string, password: string) {
      const response = await client.post<ApiResponse<LoginResult>>('/auth/login', { username, password, portal })
      session.set(response.data.data)
      return response.data.data.user
    },
    async me() { return (await client.get<ApiResponse<User>>('/auth/me')).data.data },
    async portalUser() { return (await client.get<ApiResponse<User>>('/h5/session')).data.data },
  }
}
