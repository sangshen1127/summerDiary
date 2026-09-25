import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi } from '@/api/auth'
import type { CurrentUser, LoginRequest, RegisterRequest } from '@/types/user'

// ══════════════════════════════════════════════════════════════
// user store —— 只负责「登录用户是谁、登录了没」
// ══════════════════════════════════════════════════════════════
// 边界见开发文档 §8.4：
//   ✅ 该管：当前用户、登录态、fetchMe、login、logout
//   ❌ 不该管：别人的信息、业务数据（那些属于 diary / ai store）
//
// ⚠️ 核心安全规则（开发文档 §8.2 / 避坑指南 坑 19）：
//    绝不能只信任内存状态判断登录态。路由守卫首次进入受保护页面时
//    必须调 /api/auth/me 向后端确认，因为：
//      - Cookie 可能在服务端已失效（会话超时、被踢下线）
//      - 用户可能在另一个标签页退出了登录
//      - 换浏览器标签打开时，Pinia 状态是全新的
//    所以有 initialized 标记：只确认一次，避免每次路由跳转都打接口。
//
// ⚠️ 关于会话 ID：本 store <b>不保存任何 token</b>。
//    Cookie 方案下会话 ID 由浏览器管理（且是 HttpOnly，JS 读不到），
//    前端只需要知道「当前用户是谁」。
// ══════════════════════════════════════════════════════════════

export const useUserStore = defineStore('user', () => {
  // ── state ──────────────────────────────────────────────────
  const currentUser = ref<CurrentUser | null>(null)

  /**
   * 是否已经向后端确认过登录态。
   *
   * false → 未知，路由守卫需要调 fetchMe
   * true  → 已确认（无论结果是否登录），守卫直接用 isLoggedIn 判断
   */
  const initialized = ref(false)

  /** fetchMe 进行中，避免并发重复请求 */
  const loading = ref(false)

  // ── getters ────────────────────────────────────────────────
  const isLoggedIn = computed(() => currentUser.value !== null)

  const displayName = computed(
    () => currentUser.value?.nickname || currentUser.value?.username || '未登录',
  )

  // ── actions ────────────────────────────────────────────────

  /**
   * 向后端确认当前登录用户。
   *
   * 40101 是正常情况（没登录），不算错误 —— 所以这里 catch 掉，
   * 只把 currentUser 置空。真正的网络故障才写 console 提示排查。
   *
   * @returns 是否处于登录态
   */
  async function fetchMe(): Promise<boolean> {
    if (loading.value) return isLoggedIn.value
    loading.value = true
    try {
      currentUser.value = await authApi.me()
      return true
    } catch (e) {
      currentUser.value = null
      const err = e as { isAuthError?: boolean; message?: string }
      // 40101 是预期内的「未登录」，不打扰用户也不刷日志
      if (!err.isAuthError) {
        console.warn('[user] 获取当前用户失败：', err.message)
      }
      return false
    } finally {
      loading.value = false
      // ⚠️ 无论成功失败都算「已确认」，否则守卫会反复请求
      initialized.value = true
    }
  }

  /**
   * 登录。
   *
   * 成功后后端通过 Set-Cookie 下发会话，前端不接触会话 ID。
   * 这里只负责把用户信息写进 store 并标记为已确认。
   *
   * 失败时把错误抛给调用方 —— 登录页需要区分「凭据错误」和其他失败，
   * 以便聚焦到密码框或显示通用提示。
   */
  async function login(payload: LoginRequest): Promise<CurrentUser> {
    const user = await authApi.login(payload)
    setUser(user)
    return user
  }

  /**
   * 注册。
   *
   * <b>不自动登录</b> —— 与后端设计一致（注册接口不下发 Cookie）。
   * 注册成功后由页面跳转到登录页。
   *
   * 不写 store 的原因：注册成功不代表登录成功，
   * 擅自写入会让「从哪里来」的状态不一致。
   */
  async function register(payload: RegisterRequest): Promise<CurrentUser> {
    return authApi.register(payload)
  }

  /**
   * 退出登录。
   *
   * ⚠️ 无论后端调用是否成功，都必须清空本地状态。
   * 理由：用户点了退出，本地就应该立刻表现为已退出。
   * 如果因为网络失败而保持登录态，用户会困惑（"我明明点了退出"）。
   * 服务端会话即使没销毁，也会因超时自然失效。
   */
  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } catch (e) {
      console.warn('[user] 退出接口调用失败，仍清空本地状态：', e)
    } finally {
      reset()
    }
  }

  /**
   * 登录成功后写入用户信息。
   * Cookie 由后端 Set-Cookie 下发，前端不接触。
   */
  function setUser(user: CurrentUser): void {
    currentUser.value = user
    initialized.value = true
  }

  /** 局部更新（例如改了昵称、切换了 AI 开关） */
  function patchUser(partial: Partial<CurrentUser>): void {
    if (currentUser.value) {
      currentUser.value = { ...currentUser.value, ...partial }
    }
  }

  /**
   * 清空登录态。
   *
   * 三个时机调用：
   *   1. 用户主动退出登录
   *   2. Axios 拦截器收到 40101（Cookie 已失效）
   *   3. 退出接口调用失败后的兜底
   *
   * ⚠️ initialized 保持 true：表示「已确认是未登录状态」，
   *    不要重置成 false，否则会引发新一轮 fetchMe 循环。
   */
  function reset(): void {
    currentUser.value = null
    initialized.value = true
  }

  return {
    // state
    currentUser,
    initialized,
    loading,
    // getters
    isLoggedIn,
    displayName,
    // actions
    fetchMe,
    login,
    register,
    logout,
    setUser,
    patchUser,
    reset,
  }
})
