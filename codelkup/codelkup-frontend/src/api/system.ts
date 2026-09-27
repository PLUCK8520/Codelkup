import { get } from '@/utils/request'

/** 站点公开配置（无需登录） */
export interface SiteConfigVO {
  /** 注册开关（PRE-MORTEM T0-2）：false 时注册接口返回 40304，注册页显示"暂未开放" */
  registrationEnabled: boolean
}

/**
 * 系统站点配置 API。
 *
 * 为什么单独一个文件：与后端 HealthController 的分组一致（系统域），
 * 不属于 user / post 任何一个领域文件。
 */
export const systemApi = {
  /** 公开站点配置（游客可读） */
  config: () => get<SiteConfigVO>('/system/config'),
}