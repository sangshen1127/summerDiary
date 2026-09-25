/**
 * 用户相关类型。
 *
 * ⚠️ 与后端 DTO 字段一一对应，禁止自行改名。
 *
 * 字段名是 snake_case，但**不是**因为后端配了全局 snake_case 命名策略
 * —— 后端刻意没配（见 application.yml 的说明），而是靠 `UserResponse`
 * 上显式写的 `@JsonProperty("ai_enabled")` / `@JsonProperty("created_at")`。
 *
 * 所以：**改这里之前先去读后端的 DTO**，不要凭"应该是什么规则"推测。
 * （本注释曾误写为"后端 Jackson 配了 snake_case"，模块 2-4 修正。）
 */

/** 当前登录用户（对应后端 /api/auth/me 的返回） */
export interface CurrentUser {
  id: number
  username: string
  nickname: string | null
  /** 是否开启 AI 分析。关闭后不再创建新的认知任务 */
  ai_enabled: boolean
  created_at: string
}

/** 登录请求 */
export interface LoginRequest {
  username: string
  password: string
}

/** 注册请求 */
export interface RegisterRequest {
  username: string
  password: string
  nickname?: string
}
