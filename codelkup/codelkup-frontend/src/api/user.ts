import { get, post, put, upload } from '@/utils/request'
import type {
  CurrentUserVO,
  LoginRequest,
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
}
