import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi, userApi } from '@/api/user'
import { tokenStorage } from '@/utils/request'
import type { CurrentUserVO, LoginRequest, RegisterRequest, UserVO } from '@/types/api'

/**
 * 认证状态。
 *
 * ⚠️ 关键设计：**不在前端解码 JWT 判断登录态**。
 *    accessToken 存 sessionStorage（refresh 在 HttpOnly Cookie 里，第五轮审计 #3），
 *    但"是否已登录"以"能否成功调用 /users/me"为准 ——
 *    token 可能已过期、被登出拉黑、或被封禁，只有服务端说了算。
 *    启动时调用 fetchCurrentUser() 做一次真实校验。
 */
export const useAuthStore = defineStore('auth', () => {
  const user = ref<CurrentUserVO | null>(null)
  const initialized = ref(false)

  const isLoggedIn = computed(() => user.value !== null)
  const isAdmin = computed(() => user.value?.role === 2)
  const canModerate = computed(() => (user.value?.role ?? 0) >= 1)

  /** 登录 */
  async function login(payload: LoginRequest) {
    const res = await authApi.login(payload)
    tokenStorage.setAccess(res.accessToken)
    user.value = await userApi.getMe()
    return res
  }

  /** 注册（后端注册即返回 token，等于自动登录） */
  async function register(payload: RegisterRequest) {
    const res = await authApi.register(payload)
    tokenStorage.setAccess(res.accessToken)
    user.value = await userApi.getMe()
    return res
  }

  /** 登出：先通知后端拉黑 token，再清本地。失败也要清本地，避免卡在登录态 */
  async function logout() {
    try {
      await authApi.logout()
    } catch {
      // 忽略：token 可能已失效，本地清理才是关键
    } finally {
      tokenStorage.clear()
      user.value = null
    }
  }

  /** 拉取当前用户；无 token 或失败都视为未登录 */
  async function fetchCurrentUser(): Promise<UserVO | null> {
    if (!tokenStorage.getAccess()) {
      initialized.value = true
      return null
    }
    try {
      user.value = await userApi.getMe()
      return user.value
    } catch {
      // 拦截器在刷新失败时已清理 token，这里只需清状态
      tokenStorage.clear()
      user.value = null
      return null
    } finally {
      initialized.value = true
    }
  }

  /** 局部更新用户信息（资料编辑/换头像后调用，省一次请求） */
  function patchUser(patch: Partial<CurrentUserVO>) {
    if (user.value) {
      user.value = { ...user.value, ...patch }
    }
  }

  return {
    user,
    initialized,
    isLoggedIn,
    isAdmin,
    canModerate,
    login,
    register,
    logout,
    fetchCurrentUser,
    patchUser,
  }
})
