import axios from 'axios'

/**
 * 全局 API 客户端。
 *
 * - 携带 Cookie（后端使用 HttpOnly 会话 Cookie 鉴权）
 * - 统一错误结构：优先取后端返回的 { code, message }
 * - 401 时派发全局事件，由路由层跳转登录页
 */
const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 150000,
  withCredentials: true,
})

export class ApiError extends Error {
  constructor(message, { code = 'unknown_error', status = 0, payload = null } = {}) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.payload = payload
  }
}

export const AUTH_EXPIRED_EVENT = 'renti:auth-expired'
export const ADMIN_AUTH_EXPIRED_EVENT = 'renti:admin-auth-expired'

apiClient.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const status = error.response?.status ?? 0
    const payload = error.response?.data ?? null
    const code = payload?.code ?? payload?.detail?.code ?? 'network_error'
    const message =
      payload?.message ??
      payload?.detail?.summary ??
      payload?.detail?.message ??
      (status ? `请求失败（${status}）` : '网络连接异常，请稍后重试')

    if (status === 401) {
      const isAdminScope = String(error.config?.url ?? '').startsWith('/api/admin')
      window.dispatchEvent(
        new CustomEvent(isAdminScope ? ADMIN_AUTH_EXPIRED_EVENT : AUTH_EXPIRED_EVENT, {
          detail: { code, message },
        }),
      )
    }

    return Promise.reject(new ApiError(message, { code, status, payload }))
  },
)

export default apiClient
