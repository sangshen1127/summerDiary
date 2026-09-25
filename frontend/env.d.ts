/// <reference types="vite/client" />

/**
 * Vite 环境变量类型声明。
 *
 * 只有 VITE_ 前缀的变量会被注入到客户端代码里，
 * 因此绝不能把 API Key 之类的东西放进 VITE_ 变量 —— 那等于公开。
 */
interface ImportMetaEnv {
  /** 后端 API 基地址。开发期用相对路径 /api，走 Vite 代理 */
  readonly VITE_API_BASE_URL?: string
  /** 开发服务器端口 */
  readonly VITE_DEV_PORT?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

/** 允许在 .ts 里 import .vue 单文件组件 */
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}
