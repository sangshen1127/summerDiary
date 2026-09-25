import { fileURLToPath } from 'node:url'
import { mergeConfig, defineConfig } from 'vitest/config'
import viteConfig from './vite.config'

// ══════════════════════════════════════════════════════════════
// Vitest 配置
// ══════════════════════════════════════════════════════════════
// 单独一个文件而不是塞进 vite.config.ts，原因：
//   1. Vite 的 UserConfig 类型里没有 test 字段，塞进去会 TS 报错
//   2. 测试配置和构建配置职责不同，分开更好维护
//
// 这里用 mergeConfig 复用 vite.config.ts 的 resolve.alias，
// 避免测试里 import '@/xxx' 解析不到。
// ══════════════════════════════════════════════════════════════

export default mergeConfig(
  viteConfig({ mode: 'test', command: 'serve' }),
  defineConfig({
    test: {
      // 组件测试需要 DOM 环境
      environment: 'jsdom',
      globals: true,
      include: ['src/**/*.spec.ts', 'src/**/*.test.ts'],
      // 覆盖率报告（Phase 8 会用）
      coverage: {
        provider: 'v8',
        reporter: ['text', 'html'],
        include: ['src/**/*.{ts,vue}'],
        exclude: ['src/**/*.spec.ts', 'src/types/**', 'src/main.ts'],
      },
    },
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
  }),
)
