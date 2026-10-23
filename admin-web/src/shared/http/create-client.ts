import axios from 'axios'
import type { AuthSession, User, LoginResult } from '../auth/session.ts'
import { portal } from '../auth/session.ts'
export interface ApiResponse<T> { code: string; message: string; data: T; traceId: string }
export interface Page<T> { page: number; size: number; total: number; records: T[] }
// Only locally controlled map guidance is displayed; never expose upstream response text.
const mapFailureMessages: Record<string, string> = {
  MAP_CREDENTIALS: '高德凭据无效，请管理员检查后端配置',
  MAP_PERMISSION: '高德接口权限或安全配置不允许本次请求',
  MAP_QUOTA: '高德调用额度不足，请管理员核查',
  MAP_RATE_LIMIT: '地图请求过于频繁；未自动重试，请稍后明确重试',
  MAP_LOCAL_BUDGET: '本机地图调用预算未启用或已用完',
  MAP_TIMEOUT: '高德请求超时，可能已计入调用量；未自动重试',
  MAP_NETWORK: '无法连接高德服务，未自动重试',
  MAP_NO_RESULT: '未解析到位置，请补充详细地址',
  MAP_NO_ROUTE: '未找到可行驶路线，距离未知',
  MAP_REGION_MISMATCH: '位置不属于所选广东城市或区县，请核对',
  MAP_AMBIGUOUS: '位置不明确，请补充街道门牌或明确地标',
  MAP_STRUCTURED_ADDRESS_REQUIRED: '真实定位需要广东城市、区县和详细地址',
  MAP_INVALID_REQUEST: '高德拒绝定位参数，请核对详细地址或坐标',
  MAP_UNSUPPORTED_OPERATION: '当前地图供应商不支持此操作',
  MAP_UPSTREAM: '高德服务返回异常，本次未取得可信结果；未自动重试',
  MAP_BAD_RESPONSE: '高德返回的数据格式异常，本次未取得可信结果；未自动重试',
}
export class ApiError extends Error {
  code: string
  status?: number
  traceId?: string
  constructor(message: string, code: string, status?: number, traceId?: string) { super(message); this.code = code; this.status = status; this.traceId = traceId }
}
export function createClient(session: AuthSession, baseURL: string) {
  const client = axios.create({ baseURL, timeout: 10_000, headers: { 'Content-Type': 'application/json' } })
  client.interceptors.request.use(config => {
    if (config.data instanceof FormData) config.headers.delete('Content-Type')
    if (session.valid() && !['/auth/login','/auth/sign-in','/auth/handoff','/auth/activation/inspect','/auth/activation/complete'].some(path=>config.url?.endsWith(path))) config.headers.Authorization = 'Bearer ' + session.token
    return config
  })
  client.interceptors.response.use(response => response, error => {
    if (!axios.isAxiosError<ApiResponse<unknown>>(error)) return Promise.reject(error)
    const status = error.response?.status
    if (status === 401) session.clear()
    const backendCode = error.response?.data?.code
    const candidateTrace = error.response?.data?.traceId
    const traceId = typeof candidateTrace === 'string' && /^[A-Za-z0-9._-]{1,64}$/.test(candidateTrace) ? candidateTrace : undefined
    const mapMessage = typeof backendCode === 'string' && Object.hasOwn(mapFailureMessages, backendCode) ? mapFailureMessages[backendCode] : undefined
    const message = status === 401 && error.config?.url?.endsWith('/auth/sign-in') && error.response?.data?.code === 'UNAUTHORIZED' ? '登录服务暂未就绪，请稍后重试或联系管理员'
      : status === 401 ? (['/auth/login','/auth/sign-in'].some(path=>error.config?.url?.endsWith(path)) ? '账号或密码错误' : '登录失效，请重新登录')
      : status === 403 ? (error.response?.data?.code === 'PORTAL_MISMATCH' ? '该账号不能登录当前端，请使用对应端登录' : '没有访问权限')
      : mapMessage ? `${mapMessage}（${backendCode}${traceId ? `；排查编号：${traceId}` : ''}）`
      : status === 502 || status === 504 ? '后台服务暂不可用，请等待服务恢复后重试'
      : !error.response && error.config?.url?.includes('/maps/') && ['ECONNABORTED','ETIMEDOUT'].includes(error.code ?? '') ? '等待地图接口响应超时，结果未知，可能已计入调用量；未自动重试，请先核对剩余额度'
      : !error.response ? '网络连接失败，请检查后重试' : (/[\u3400-\u9fff]/.test(error.response.data?.message ?? '') ? error.response.data.message : status === 503 ? '后台服务暂不可用，请等待服务恢复后重试' : status === 409 ? '记录或状态已变化，请刷新后核对再提交' : status === 400 ? '输入不符合要求，请核对必填项、金额、文件和所选记录' : status === 404 ? '记录不存在或已不可访问，请刷新后重试' : '请求未完成，请稍后重试或联系管理员')
    return Promise.reject(new ApiError(message, backendCode ?? 'NETWORK_ERROR', status, traceId))
  })
  return {
    client,
    async login(username: string, password: string) {
      const response = await client.post<ApiResponse<LoginResult>>('/auth/login', { username, password, portal })
      session.set(response.data.data)
      return response.data.data.user
    },
    async me() { return (await client.get<ApiResponse<User>>('/auth/me')).data.data },
    async portalUser() { return (await client.get<ApiResponse<User>>('/admin/session')).data.data },
  }
}
