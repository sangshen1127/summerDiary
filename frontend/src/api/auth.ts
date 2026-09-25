import { request } from './request'
import type { CurrentUser, LoginRequest, RegisterRequest } from '@/types/user'

// ══════════════════════════════════════════════════════════════
// 认证接口封装
// ══════════════════════════════════════════════════════════════
// 契约见开发文档 §4.6「认证」段落。
//
// ⚠️ Phase 0 状态：后端这几个接口尚未实现（属于 Phase 1 模块 1-2 ~ 1-3）。
//    本文件先按契约写好，等 Phase 1 后端就位即可直接联调。
//
// 认证方案：HttpOnly Cookie 会话。
//   - 登录成功由后端下发 Set-Cookie，前端**不接触 token**
//   - 所以这里没有任何 token 存取逻辑，这是刻意的
//   - axios 实例已配 withCredentials: true，Cookie 自动携带
// ══════════════════════════════════════════════════════════════

export const authApi = {
  /** 注册。成功不解说登录态，需要再调 login */
  register(body: RegisterRequest): Promise<CurrentUser> {
    return request.post('/auth/register', body) as Promise<CurrentUser>
  },

  /** 登录。成功返回用户信息，并由响应头下发会话 Cookie */
  login(body: LoginRequest): Promise<CurrentUser> {
    return request.post('/auth/login', body) as Promise<CurrentUser>
  },

  /** 退出。后端清 Cookie */
  logout(): Promise<void> {
    return request.post('/auth/logout') as Promise<void>
  },

  /**
   * 当前用户。
   *
   * ⚠️ 路由守卫必须调用它确认真实登录态，
   *    不能只信任前端的 initialized 缓存（开发文档 §8.2）。
   */
  me(): Promise<CurrentUser> {
    return request.get('/auth/me') as Promise<CurrentUser>
  },
}
