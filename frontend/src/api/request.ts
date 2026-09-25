import axios, { type AxiosInstance, type AxiosError, type InternalAxiosRequestConfig } from 'axios'
import {
  ErrorCode,
  NETWORK_ERROR_CODE,
  type ApiResult,
  type AppError,
} from '@/types/api'

// ══════════════════════════════════════════════════════════════
// 唯一的 Axios 实例
// ══════════════════════════════════════════════════════════════
// 规范见开发文档 §8.3。全项目只允许存在这一个实例，
// 所有接口封装都在 src/api/ 下基于它写。
//
// 三条硬规则：
//   1. 必须检查业务 code，不能只看 HTTP 状态码
//      （后端返回 HTTP 200 + code=40101 是可能的）
//   2. 40101 要清空 user store 并跳 /login，带上 redirect
//   3. 技术细节写 console，给用户的文案必须是可读的
// ══════════════════════════════════════════════════════════════

const BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api'

/** 普通请求超时 15 秒 */
const DEFAULT_TIMEOUT = 15000

/**
 * AI 相关请求超时 60 秒。
 *
 * 为什么单独设：模型调用后端配置是「连接 5s / 读取 30s」，
 * 加上重试和向量检索，可能接近 60 秒。用 15 秒会把正常请求判超时。
 */
export const AI_TIMEOUT = 60000

const instance: AxiosInstance = axios.create({
  baseURL: BASE_URL,
  timeout: DEFAULT_TIMEOUT,
  // 携带 HttpOnly Cookie（会话认证的核心）
  withCredentials: true,
  headers: {
    'Content-Type': 'application/json',
  },
})

// ══════════════════════════════════════════════════════════════
// 请求拦截器
// ══════════════════════════════════════════════════════════════

instance.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    // 注意：不要在这里手动加 Authorization 头 ——
    // 我们用 HttpOnly Cookie，JS 读不到也不需要读。
    // 手动加反而会把 token 暴露在 JS 内存里，失去 HttpOnly 的意义。
    return config
  },
  (error: unknown) => Promise.reject(toAppError(error)),
)

// ══════════════════════════════════════════════════════════════
// 响应拦截器
// ══════════════════════════════════════════════════════════════

instance.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResult<unknown> | undefined

    // 非统一响应体（例如静态资源、actuator），原样返回
    if (!body || typeof body.code !== 'number') {
      return response.data
    }

    // ✅ 业务成功
    if (body.code === ErrorCode.SUCCESS) {
      // 直接返回 data，调用方拿到的就是业务数据本身
      return body.data
    }

    // ❌ 业务失败：HTTP 可能仍是 200，所以必须在这里拦
    //    交给下面的统一错误处理
    return Promise.reject(
      toAppError(null, body.code, body.message),
    )
  },
  (error: AxiosError) => {
    // HTTP 层的错误（4xx / 5xx / 网络故障）
    const status = error.response?.status
    const body = error.response?.data as ApiResult<unknown> | undefined

    // 后端返回了统一响应体 → 用里面的业务码和文案
    if (body && typeof body.code === 'number') {
      const appError = toAppError(error, body.code, body.message)
      if (appError.isAuthError) {
        handleUnauthorized()
      }
      return Promise.reject(appError)
    }

    // 后端没返回统一响应体 → 按 HTTP 状态码翻译
    if (status === 401) {
      handleUnauthorized()
      return Promise.reject(
        toAppError(error, ErrorCode.UNAUTHORIZED, '登录已过期，请重新登录'),
      )
    }
    if (status === 403) {
      return Promise.reject(toAppError(error, ErrorCode.FORBIDDEN, '没有权限执行此操作'))
    }
    if (status === 404) {
      return Promise.reject(toAppError(error, ErrorCode.NOT_FOUND, '请求的内容不存在'))
    }
    if (status && status >= 500) {
      return Promise.reject(
        toAppError(error, ErrorCode.INTERNAL_ERROR, '服务器暂时不可用，请稍后重试'),
      )
    }

    // 没有 response → 网络层故障
    return Promise.reject(toAppError(error, NETWORK_ERROR_CODE, describeNetworkError(error)))
  },
)

// ══════════════════════════════════════════════════════════════
// 辅助函数
// ══════════════════════════════════════════════════════════════

function toAppError(
  raw: unknown,
  code?: number,
  message?: string,
): AppError {
  const finalCode = code ?? NETWORK_ERROR_CODE
  const finalMessage = message || '请求失败，请稍后重试'
  return {
    code: finalCode,
    message: finalMessage,
    isAuthError: finalCode === ErrorCode.UNAUTHORIZED,
    isAiError: finalCode === ErrorCode.AI_SERVICE_ERROR,
    detail: raw,
  }
}

/**
 * 把网络层异常翻译成用户能看懂的话。
 *
 * 技术细节（ECONNREFUSED、DNS 失败等）只写 console，
 * 用户看到的是「无法连接服务器」这种可操作的信息。
 */
function describeNetworkError(error: AxiosError): string {
  if (error.code === 'ECONNABORTED' || error.message.includes('timeout')) {
    return '请求超时，请检查网络后重试'
  }
  // 浏览器把跨域失败、连接被拒、断网都归为 Network Error，无法区分
  return '无法连接服务器，请确认后端已启动'
}

/**
 * 40101 的统一处理。
 *
 * ⚠️ 这里用动态 import 而不是顶部 import，是**刻意的**：
 *
 *   request.ts ← user.ts ← ... 会形成循环依赖
 *   （user.ts 里的 authApi 依赖 request.ts）
 *
 * 顶部 import 时，模块初始化顺序不确定，store 可能拿到 undefined。
 * 动态 import 把解析推迟到运行时（真正发生 401 时），彻底破环。
 *
 * Vite 构建时会提示
 *   "user.ts is dynamically imported by request.ts but also statically imported by ..."
 * 这条警告是**预期内**的，不需要处理 —— 因为 401 处理本来就不该进
 * 主 chunk，它只在异常路径上用到。
 */
function handleUnauthorized(): void {
  void (async () => {
    try {
      const [{ useUserStore }, { default: router }] = await Promise.all([
        import('@/stores/user'),
        import('@/router'),
      ])
      useUserStore().reset()

      const current = router.currentRoute.value
      // 已经在登录页就不再跳，避免重复导航警告
      if (current.path !== '/login') {
        void router.push({
          path: '/login',
          query: { redirect: current.fullPath },
        })
      }
    } catch (e) {
      console.error('[request] 处理 401 失败', e)
    }
  })()
}

/**
 * 带额外超时的请求（AI 接口用 60s）。
 *
 * 用法：
 *   import { request } from '@/api/request'
 *   const answer = await request.post<ChatAnswer>('/ai/...', body, { timeout: AI_TIMEOUT })
 */
export const request = instance

export default instance
