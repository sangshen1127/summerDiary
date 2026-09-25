import { defineStore } from 'pinia'
import { ref } from 'vue'

// ══════════════════════════════════════════════════════════════
// app store —— 全局 UI 状态
// ══════════════════════════════════════════════════════════════
// 边界见开发文档 §8.4：
//   ✅ 该管：主题、全局 loading、全局错误
//   ❌ 不该管：任何业务数据（日记、记忆、会话都在各自的 store 或页面里）
// ══════════════════════════════════════════════════════════════

export type ThemeMode = 'light' | 'dark' | 'system'

const THEME_STORAGE_KEY = 'ai-diary:theme'

export const useAppStore = defineStore('app', () => {
  // ── 主题 ───────────────────────────────────────────────────
  const theme = ref<ThemeMode>(readStoredTheme())

  function setTheme(mode: ThemeMode): void {
    theme.value = mode
    try {
      localStorage.setItem(THEME_STORAGE_KEY, mode)
    } catch {
      // 隐私模式下 localStorage 可能不可用，忽略即可，不影响功能
    }
    applyTheme(mode)
  }

  // ── 全局 loading ───────────────────────────────────────────
  /**
   * 全局遮罩 loading。
   *
   * ⚠️ 只用于「整页阻塞」的操作（如账户清除、导出大数据）。
   *    普通列表/表单的 loading 应该用局部 loading，
   *    否则多个请求并发时遮罩会闪烁。
   */
  const globalLoading = ref(false)

  function showGlobalLoading(): void {
    globalLoading.value = true
  }

  function hideGlobalLoading(): void {
    globalLoading.value = false
  }

  // ── 初始化 ─────────────────────────────────────────────────
  /** 应用启动时调用一次，把持久化的主题应用到 document */
  function init(): void {
    applyTheme(theme.value)
  }

  return {
    theme,
    globalLoading,
    setTheme,
    showGlobalLoading,
    hideGlobalLoading,
    init,
  }
})

function readStoredTheme(): ThemeMode {
  try {
    const raw = localStorage.getItem(THEME_STORAGE_KEY)
    if (raw === 'light' || raw === 'dark' || raw === 'system') return raw
  } catch {
    // 忽略
  }
  return 'system'
}

/**
 * 把主题写到 <html> 的 data-theme 属性上。
 *
 * 样式层通过 :root[data-theme='dark'] 选择器响应，
 * 不在这里直接操作 DOM 颜色，保持样式与逻辑分离。
 */
function applyTheme(mode: ThemeMode): void {
  const root = document.documentElement
  if (mode === 'system') {
    root.removeAttribute('data-theme')
  } else {
    root.setAttribute('data-theme', mode)
  }
}
