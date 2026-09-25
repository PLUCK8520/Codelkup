import axios, {
  type AxiosInstance,
  type AxiosRequestConfig,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResult, TokenResponse } from '@/types/api'

/**
 * axios 封装。
 *
 * 三件事：统一 baseURL / 注入 token / 统一错误提示与 401 自动刷新。
 */

const TOKEN_KEY = 'codelkup_access_token'
const REFRESH_KEY = 'codelkup_refresh_token'

/* ==================== token 存取 ==================== */

export const tokenStorage = {
  getAccess: () => localStorage.getItem(TOKEN_KEY),
  getRefresh: () => localStorage.getItem(REFRESH_KEY),
  set(access: string, refresh: string) {
    localStorage.setItem(TOKEN_KEY, access)
    localStorage.setItem(REFRESH_KEY, refresh)
  },
  clear() {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(REFRESH_KEY)
  },
}

/* ==================== 请求实例 ==================== */

const http: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' },
})

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = tokenStorage.getAccess()
  if (token && config.headers) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/* ==================== 401 自动刷新 ==================== */
/**
 * 并发 401 的处理：
 * 若多个请求同时收到 401，不能各自去刷新（refreshToken 是单槽位，
 * 且刷新会轮换 token，并发刷新会互相把对方的新 token 顶掉）。
 * 因此用一个共享 Promise 把并发请求串起来，只刷新一次。
 */
let refreshPromise: Promise<string> | null = null

async function doRefresh(): Promise<string> {
  const refreshToken = tokenStorage.getRefresh()
  if (!refreshToken) {
    throw new Error('NO_REFRESH_TOKEN')
  }
  // 用裸 axios，绕开拦截器，避免刷新失败时再次触发刷新（死循环）
  const { data } = await axios.post<ApiResult<TokenResponse>>(
    '/api/auth/refresh',
    { refreshToken },
    { headers: { 'Content-Type': 'application/json' } },
  )
  if (data.code !== 0 || !data.data?.accessToken) {
    throw new Error('REFRESH_FAILED')
  }
  tokenStorage.set(data.data.accessToken, data.data.refreshToken)
  return data.data.accessToken
}

function forceLogout() {
  tokenStorage.clear()
  // 用 replace 而非 href，避免用户点返回又回到需要登录的页面
  if (!location.pathname.startsWith('/login')) {
    location.replace('/login?redirect=' + encodeURIComponent(location.pathname))
  }
}

http.interceptors.response.use(
  (response: AxiosResponse) => response,
  async (error) => {
    const { response, config } = error
    const originalRequest = config as AxiosRequestConfig & { _retried?: boolean }

    // ---- 401：尝试刷新一次 ----
    if (response?.status === 401 && !originalRequest._retried) {
      originalRequest._retried = true

      // 登录/刷新接口自身返回 401，说明凭证就是错的，直接登出
      const url: string = originalRequest.url || ''
      if (url.includes('/auth/login') || url.includes('/auth/refresh')) {
        forceLogout()
        return Promise.reject(error)
      }

      try {
        refreshPromise = refreshPromise ?? doRefresh()
        const newToken = await refreshPromise
        refreshPromise = null

        originalRequest.headers = {
          ...originalRequest.headers,
          Authorization: `Bearer ${newToken}`,
        }
        return http.request(originalRequest)
      } catch {
        refreshPromise = null
        forceLogout()
        return Promise.reject(error)
      }
    }

    // ---- 其余错误统一提示 ----
    const bizCode = response?.data?.code
    const message =
      response?.data?.message ||
      (response?.status === 403
        ? '没有权限执行此操作'
        : response?.status === 404
          ? '请求的资源不存在'
          : response?.status >= 500
            ? '服务器开小差了，请稍后再试'
            : '网络异常，请检查连接')

    // 403/404 之类的偶发错误不打扰用户；真正需要用户知晓的才弹提示
    if (![403, 404].includes(response?.status) || bizCode) {
      ElMessage.error(message)
    }

    return Promise.reject(error)
  },
)

/* ==================== 业务层解包 ==================== */

/**
 * 发起请求并直接返回 data。
 *
 * 后端约定 HTTP 200 + code!==0 也算业务失败，
 * 所以这里再判一次 code，把业务错误统一抛出去。
 */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await http.request<ApiResult<T>>(config)
  const body = response.data

  if (body.code !== 0) {
    const err = new Error(body.message || '请求失败') as Error & { code?: number }
    err.code = body.code
    throw err
  }
  return body.data
}

export const get = <T>(url: string, params?: object) =>
  request<T>({ method: 'GET', url, params })

export const post = <T>(url: string, data?: object) =>
  request<T>({ method: 'POST', url, data })

export const put = <T>(url: string, data?: object) =>
  request<T>({ method: 'PUT', url, data })

export const del = <T>(url: string, params?: object) =>
  request<T>({ method: 'DELETE', url, params })

/** 上传（multipart），单独一个方法是因为要覆盖 Content-Type */
export const upload = <T>(url: string, formData: FormData) =>
  request<T>({
    method: 'POST',
    url,
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' },
  })

export default http
