/**
 * 时间格式化工具。
 *
 * ══════════════════════════════════════════════════════════════
 * 为什么必须有这个文件（开发文档 §4.4 + 避坑指南 坑 20）
 * ══════════════════════════════════════════════════════════════
 * 契约：数据库存 UTC，接口返回 ISO-8601（带 Z），前端按用户时区显示。
 *
 * 如果每个页面各写各的 new Date().toLocaleString()，会出现：
 *   - 有的页面显示 UTC，有的显示本地时间，相差 8 小时
 *   - 有的补零有的不补，格式五花八门
 *   - 有的把无时区的字符串当本地时间解析，跨时区直接错
 *
 * 所以规定：**所有时间显示必须走本文件的函数**，
 * 禁止在页面里散落 new Date() 拼接。
 * ══════════════════════════════════════════════════════════════
 */

/** 用户时区。Phase 7 的设置页会允许用户改，先固定为浏览器时区 */
function getUserTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai'
  } catch {
    return 'Asia/Shanghai'
  }
}

/**
 * 解析后端返回的时间字符串。
 *
 * ⚠️ 关键点：后端返回的是带 Z 的 ISO-8601（如 2026-09-20T09:48:47.898Z），
 *    new Date() 能正确识别 Z 并转成本地时间。
 *    但如果哪天后端漏了 Z，JS 会把它当**本地时间**解析 ——
 *    于是显示会差 8 小时且不报错。
 *    所以这里做一次防御：无时区标识时补上 Z。
 */
function parseISO(input: string): Date | null {
  if (!input) return null

  // 已带时区标识（Z 或 ±HH:MM）→ 直接用
  const hasTimeZone = /(?:Z|[+-]\d{2}:?\d{2})$/.test(input)
  const normalized = hasTimeZone ? input : `${input}Z`

  const date = new Date(normalized)
  return Number.isNaN(date.getTime()) ? null : date
}

/**
 * 格式化为「2026-09-20 17:48」
 *
 * @param input 后端返回的 ISO-8601 UTC 字符串
 * @returns 本地时间字符串；解析失败返回 '—'
 */
export function formatDateTime(input: string | null | undefined): string {
  if (!input) return '—'
  const date = parseISO(input)
  if (!date) return '—'

  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: getUserTimeZone(),
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
    .format(date)
    .replace(/\//g, '-')
}

/**
 * 格式化为「2026-09-20」
 */
export function formatDate(input: string | null | undefined): string {
  if (!input) return '—'
  const date = parseISO(input)
  if (!date) return '—'

  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: getUserTimeZone(),
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
    .format(date)
    .replace(/\//g, '-')
}

/**
 * 相对时间：「3 分钟前」「2 小时前」「昨天 17:48」
 *
 * 日记列表和 AI 消息用它更自然。超过 7 天直接显示日期。
 */
export function formatRelative(input: string | null | undefined): string {
  if (!input) return '—'
  const date = parseISO(input)
  if (!date) return '—'

  const diffMs = Date.now() - date.getTime()
  const diffSec = Math.floor(diffMs / 1000)

  if (diffSec < 60) return '刚刚'
  if (diffSec < 3600) return `${Math.floor(diffSec / 60)} 分钟前`
  if (diffSec < 86400) return `${Math.floor(diffSec / 3600)} 小时前`
  if (diffSec < 86400 * 7) return `${Math.floor(diffSec / 86400)} 天前`

  return formatDate(input)
}

/**
 * 转成 <input type="datetime-local"> 需要的格式「2026-09-20T17:48」。
 *
 * 编辑器修改日记时间时用。
 */
export function toDateTimeLocal(input: string | null | undefined): string {
  if (!input) return ''
  const date = parseISO(input)
  if (!date) return ''

  const pad = (n: number): string => String(n).padStart(2, '0')
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  )
}
