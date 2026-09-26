import { get, post, put, upload } from '@/utils/request'
import type {
  CurrentUserVO,
  LoginRequest,
  PageResult,
  PasswordChangeRequest,
  PostListVO,
  ProfileUpdateRequest,
  RegisterRequest,
  TokenResponse,
  UserVO,
} from '@/types/api'

/** 认证相关接口 —— 对应后端 AuthController */
export const authApi = {
  register: (data: RegisterRequest) => post<TokenResponse>('/auth/register', data),

  login: (data: LoginRequest) => post<TokenResponse>('/auth/login', data),

  logout: () => post<void>('/auth/logout'),

  /** 邮箱是否已被占用（注册表单失焦时调用做异步提示） */
  checkEmail: (email: string) =>
    get<{ email: string; taken: boolean; message: string }>('/auth/check-email', { email }),
}

/** 用户相关接口 —— 对应后端 UserController */
export const userApi = {
  /** 公开主页（不含邮箱） */
  getProfile: (id: number) => get<UserVO>(`/users/${id}`),

  /** 本人资料（含邮箱） */
  getMe: () => get<CurrentUserVO>('/users/me'),

  updateMe: (data: ProfileUpdateRequest) => put<CurrentUserVO>('/users/me', data),

  uploadAvatar: (formData: FormData) => upload<CurrentUserVO>('/users/me/avatar', formData),

  /**
   * 修改密码。
   * ⚠️ 后端在成功响应之前就已拉黑当前 accessToken 并清空 refresh 白名单，
   *    所以调用方拿到成功结果后应当立即本地登出并跳登录页（见 SettingsView）。
   */
  changePassword: (data: PasswordChangeRequest) => put<void>('/users/me/password', data),

  /**
   * 我的收藏（分页，按收藏时间倒序）。
   *
   * 后端处理器在内容控制器（返回的是一页帖子，与列表共用装配），
   * 路径仍在 /users/me 命名空间下 —— 前端只关心路径。
   */
  getCollections: (page: number, size = 20) =>
    get<PageResult<PostListVO>>('/users/me/collections', { page, size }),
}
