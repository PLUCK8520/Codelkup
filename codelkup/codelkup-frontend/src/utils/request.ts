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

/**
 * 请求配置（在 axios 原生配置上追加本项目自己的标志位）。
 *
 * `silent`：失败不弹全局提示，由调用方自行处理 —— 用于"后台自动行为"
 * （草稿自动保存等）。这类请求失败时都有降级路径（本地草稿兜底），
 * 弹提示既不解决任何问题，又会在自动保存的节拍上反复打扰用户。
 */
export interface RequestConfig extends AxiosRequestConfig {
  silent?: boolean
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

/* ==================== 凭证刷新 ==================== */
/**
 * 并发失效的处理：
 * 若多个请求同时失效，不能各自去刷新（refreshToken 是单槽位，
 * 且刷新会轮换 token，并发刷新会互相把对方的新 token 顶掉）。
 * 因此用一个共享 Promise 把并发请求串起来，只刷新一次。
 */
let refreshPromise: Promise<string> | null = null

/**
 * ⚠️ 触发刷新的判据必须是**业务码**，不能只看 HTTP 状态码。
 *
 * 本项目后端把业务失败也返回 HTTP 200 + code（见后端 SecurityHandlers），
 * 凭证失效同样是 `200 + 40101/40102/40103`。此前这里只判 `status === 401`，
 * 于是这条刷新链路**从不触发** —— token 一过期就直接表现为"未登录"，
 * 而"401 并发刷新串行化"这段设计等于没生效。
 *
 * 40104（邮箱或密码错误）/40105（封禁）不在列表里：密码错不该去刷 token。
 */
const AUTH_EXPIRED_CODES = [40101, 40102, 40103]

/** 登录 / 刷新接口自身的失败不触发刷新，否则会互相递归 */
function isAuthEndpoint(url?: string): boolean {
  return !!url && (url.includes('/auth/login') || url.includes('/auth/refresh'))
}

function isAuthExpired(body: unknown): boolean {
  const code = (body as ApiResult<unknown> | undefined)?.code
  return typeof code === 'number' && AUTH_EXPIRED_CODES.includes(code)
}

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
  // 用 replace 而非 href，避免用户点返回又回到需要登录的页面
  if (!location.pathname.startsWith('/login')) {
    location.replace('/login?redirect=' + encodeURIComponent(location.pathname))
  }
}

/**
 * 凭证失效的收尾。
 *
 * ⚠️ 只有"本来登录着"（本地还存着 refreshToken）才跳登录页：
 * 游客访问公开接口时也可能拿不到凭证，若一律重定向，
 * 未登录用户打开首页就会被弹到 /login。
 */
function onAuthExpired() {
  const wasLoggedIn = !!tokenStorage.getRefresh()
  tokenStorage.clear()
  if (wasLoggedIn) {
    forceLogout()
  }
}

/**
 * 用 refreshToken 换新 accessToken 并**重放**原请求。
 *
 * @returns 重放成功的响应；无法刷新（无 refreshToken / 刷新失败 / 已重试过）返回 null
 */
async function refreshAndReplay(
  config: InternalAxiosRequestConfig & { _retried?: boolean },
): Promise<AxiosResponse | null> {
  if (config._retried || !tokenStorage.getRefresh()) {
    return null
  }
  config._retried = true
  try {
    refreshPromise = refreshPromise ?? doRefresh()
    const newToken = await refreshPromise
    refreshPromise = null
    config.headers.Authorization = `Bearer ${newToken}`
    return await http.request(config)
  } catch {
    refreshPromise = null
    return null
  }
}

http.interceptors.response.use(
  // ---- 成功分支：HTTP 200 也可能是"凭证失效"（业务码 401xx）----
  async (response: AxiosResponse) => {
    const config = response.config as InternalAxiosRequestConfig & { _retried?: boolean }

    if (isAuthExpired(response.data) && !isAuthEndpoint(config.url)) {
      const replayed = await refreshAndReplay(config)
      if (replayed) {
        return replayed
      }
      onAuthExpired()
    }

    return response
  },
  // ---- 失败分支：HTTP 层错误（网关/直连等返回真实 401 时走这里，逻辑同上）----
  async (error) => {
    const { response, config } = error
    const originalRequest = config as InternalAxiosRequestConfig & { _retried?: boolean }

    if (response?.status === 401 && !isAuthEndpoint(originalRequest.url)) {
      const replayed = await refreshAndReplay(originalRequest)
      if (replayed) {
        return replayed
      }
      onAuthExpired()
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
    // （silent 请求一律不弹：它们失败后的收尾由调用方负责）
    if ((![403, 404].includes(response?.status) || bizCode) && !isSilent(originalRequest)) {
      ElMessage.error(message)
    }

    return Promise.reject(error)
  },
)

/** 静默请求判定：标记位随 axios 合并后的 config 一路带到错误分支 */
function isSilent(config?: AxiosRequestConfig): boolean {
  return (config as RequestConfig | undefined)?.silent === true
}

/* ==================== 业务层解包 ==================== */

/**
 * 发起请求并直接返回 data。
 *
 * 后端约定 HTTP 200 + code!==0 也算业务失败，
 * 所以这里再判一次 code，把业务错误统一抛出去。
 */
export async function request<T>(config: RequestConfig): Promise<T> {
  const response = await http.request<ApiResult<T>>(config)
  const body = response.data

  if (body.code !== 0) {
    const err = new Error(body.message || '请求失败') as Error & { code?: number }
    err.code = body.code

    // ⚠️ 业务失败必须在这里提示，不能指望 axios 的失败分支：
    //    后端约定"业务失败也返回 HTTP 200 + code"（见 SecurityHandlers），
    //    那条失败分支永远不会走到 —— 不在这里弹，用户看到的就是"点了没反应"。
    //    实测踩过：密码输错登录、改密时原密码填错，界面都毫无反馈
    //    （更糟的是各处 catch 里都写着"拦截器已统一提示"，于是谁也没提示）。
    //    只有凭证失效的 40101/40102/40103 除外 —— 它们由上面的刷新/跳登录流程统一处理，
    //    这里再弹一次就是重复打扰；silent 请求（自动保存等）也除外。
    if (!isAuthExpired(body) && !config.silent) {
      ElMessage.error(err.message)
    }
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
