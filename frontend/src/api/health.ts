import { request } from './request'
import type { HealthData } from '@/types/api'

// ══════════════════════════════════════════════════════════════
// 自检接口
// ══════════════════════════════════════════════════════════════
// 对应后端 HealthController（Phase 0 验收用）。
//
// 走 /api 前缀，因此会经过 Vite 代理 —— 这样调用成功
// 就等于同时验证了「前端 → Vite 代理 → 后端 → 统一响应体」整条链路。
// ══════════════════════════════════════════════════════════════

export const healthApi = {
  /** 获取后端健康信息 */
  check(): Promise<HealthData> {
    return request.get('/health') as Promise<HealthData>
  },
}
