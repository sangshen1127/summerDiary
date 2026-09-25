/**
 * 后端接口契约的 TypeScript 镜像。
 *
 * ⚠️ 这里的类型必须与后端保持一致，字段名一个字都不能改。
 *    真源是 md文档/AI日记系统开发文档.md §4 与 §4.6 的接口总表。
 *
 * 命名约定：**JSON 字段是 snake_case**，但后端**并没有**配全局
 * Jackson 命名策略（`application.yml` 里明确注释了"刻意不配"）。
 * 实际做法是：只在名字真的不一致时，在 DTO 字段上显式写
 * `@JsonProperty("created_at")`。
 *
 * 所以本文件的字段名是**照抄后端 DTO 声明的 JSON 名**，
 * 而不是"按某种规则推导出来的"。看后端 DTO 就能直接知道这里该写什么。
 *
 * （本节曾误写为"后端 Jackson 配了 snake_case"，模块 2-4 修正 ——
 *   结论相同但理由不同，而理由错了会让人在排查字段名问题时找错方向。）
 */

// ══════════════════════════════════════════════════════════════
// 统一响应体（开发文档 §4.1）
// ══════════════════════════════════════════════════════════════

export interface ApiResult<T> {
  code: number
  message: string
  data: T
  timestamp: number
}

// ══════════════════════════════════════════════════════════════
// 错误码字典（开发文档 §4.2）—— 与后端 ErrorCode.java 一一对应
// ══════════════════════════════════════════════════════════════

export const ErrorCode = {
  SUCCESS: 0,
  /** 参数缺失、格式或长度错误 */
  BAD_REQUEST: 40001,
  /** 未登录、Cookie 无效或过期 */
  UNAUTHORIZED: 40101,
  /** 已登录但无权访问 */
  FORBIDDEN: 40301,
  /** 资源不存在「或不属于当前用户」（刻意不区分） */
  NOT_FOUND: 40401,
  /** 请求方法不被支持（如对只读接口发 POST） */
  METHOD_NOT_ALLOWED: 40501,
  /** 用户名已存在、幂等键冲突、状态非法 */
  CONFLICT: 40901,
  /** AI 返回的 JSON 无法解析 */
  UNPROCESSABLE: 42201,
  /** AI 或向量服务失败，要保留用户输入并提供重试 */
  AI_SERVICE_ERROR: 50011,
  /** 未预期的系统错误 */
  INTERNAL_ERROR: 50001,
} as const

export type ErrorCodeValue = (typeof ErrorCode)[keyof typeof ErrorCode]

// ══════════════════════════════════════════════════════════════
// 分页（开发文档 §4.3）
// ══════════════════════════════════════════════════════════════

/** 分页请求参数。page 从 0 开始；size 后端强制 <= 100 */
export interface PageQuery {
  page?: number
  size?: number
}

export interface PageResult<T> {
  items: T[]
  page: number
  size: number
  total: number
  hasNext: boolean
}

// ══════════════════════════════════════════════════════════════
// 前端统一错误对象
// ══════════════════════════════════════════════════════════════

/**
 * Axios 拦截器把「网络错误」和「业务错误」统一转换成这个对象，
 * 页面只需要处理一种错误类型。
 *
 * - `code`：业务错误码；纯网络故障时为 NETWORK_ERROR
 * - `message`：可直接展示给用户的文案
 * - `isAuthError`：40101，拦截器已经处理了跳转，页面通常只需静默
 * - `isAiError`：50011，页面应保留用户输入并提供重试按钮
 */
export interface AppError {
  code: number
  message: string
  isAuthError: boolean
  isAiError: boolean
  /** 技术细节，只写 console，不展示给用户 */
  detail?: unknown
}

/** 纯网络故障（后端没起来、断网、超时）用的伪错误码 */
export const NETWORK_ERROR_CODE = -1

// ══════════════════════════════════════════════════════════════
// 健康检查（Phase 0 验收用，后端 HealthController）
// ══════════════════════════════════════════════════════════════

export interface HealthData {
  status: string
  application: string
  profile: string
  /** ISO-8601 UTC，末尾带 Z，用于验证时区约定 */
  server_time_utc: string
  timezone: string
}
