import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv, type ProxyOptions } from 'vite'
import vue from '@vitejs/plugin-vue'

// ══════════════════════════════════════════════════════════════
// Vite 配置
// ══════════════════════════════════════════════════════════════
// 最关键的配置是 server.proxy —— 它决定 Cookie 认证能不能工作。
//
// 【为什么必须用代理】
// 开发期前端在 localhost:5173，后端在 localhost:8080，属于跨域。
// 而我们的认证方案是 HttpOnly + SameSite=Lax Cookie（开发文档 §5.1），
// 浏览器在跨域请求下默认不会带上这种 Cookie。
//
// 走代理后，浏览器认为请求是发往 5173 自己的（同源），
// Vite 在服务端把请求转发给 8080，Cookie 就能正常工作。
//
// 副作用是 Set-Cookie 的 domain 会变成 localhost:5173，
// 这正好是我们想要的 —— 浏览器愿意存它。
//
// 备选方案（不推荐）：后端配 CORS allowCredentials(true) + 前端
// withCredentials + 精确 origin。配置更繁琐且容易出错。
// ══════════════════════════════════════════════════════════════

export default defineConfig(({ mode }) => {
  // 从项目根目录读 .env（Vite 默认只读 frontend/ 下的，这里指到上一级）
  const env = loadEnv(mode, fileURLToPath(new URL('..', import.meta.url)), '')
  const devPort = Number(env.VITE_DEV_PORT || 5173)
  const backendTarget = `http://localhost:${env.SERVER_PORT || 8080}`

  // ⚠️ proxy 配置抽成变量，给 dev 和 preview 共用。
  //
  // 为什么 preview 也需要它：`vite preview` 提供的是**构建产物**，
  // 它不会自动把 /api 转给后端。少了这段，用 preview 验收时
  // 所有接口请求都会打到 4173/5173 自己身上并 404，
  // 表现是"页面能打开但数据全空" —— 很容易误判成前端 bug。
  const proxy: Record<string, ProxyOptions> = {
    '/api': {
      target: backendTarget,
      // false：转发时保留浏览器发来的 Host（localhost:<前端端口>），
      // 不改成后端地址。这样后端下发的 Set-Cookie 不带不匹配的 Domain，
      // 浏览器才愿意保存。若端口浮动（5174 等），行为同样正确。
      changeOrigin: false,
      // ⚠️ 这里刻意**不给参数写显式类型标注**。第一版我手写了
      // `{ on: (event: string, cb: (proxyRes: {...}) => void) => void }`，
      // 结果 TS 报 "No overload matches this call" ——
      // http-proxy 的 `Server.on` 是带多个重载的复杂签名，
      // 手写一个近似结构必然对不上。让 TS 从 ProxyOptions 推断即可。
      configure: (proxyServer) => {
        proxyServer.on('proxyRes', (proxyRes) => {
          const setCookie = proxyRes.headers['set-cookie']
          if (setCookie) {
            // 去掉后端可能加的 Domain 属性，避免被浏览器拒绝
            proxyRes.headers['set-cookie'] = setCookie.map((cookie) =>
              cookie.replace(/;\s*Domain=[^;]+/i, ''),
            )
          }
        })
      },
    },
    '/actuator': {
      target: backendTarget,
      changeOrigin: false,
    },
  }

  return {
    plugins: [vue()],

    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },

    server: {
      port: devPort,
      // ⚠️ strictPort 必须是 false，不要改成 true。
      //
      // true 的含义：端口被占用时直接报错退出，不自动换端口。
      // 曾踩过：上一个 Vite 进程没退干净占着 5173，新启动直接失败：
      //     Error: Port 5173 is already in use
      // 只能手动去查进程、杀进程，纯属自找麻烦。
      //
      // 当初设 true 是想"让端口确定"，怕端口一变 Cookie 失效。
      // 但这个担心是多余的：浏览器始终访问 localhost:<浮动端口>，
      // 再由 Vite 在服务端转发给后端 —— 后端根本不知道前端在哪个端口，
      // 所以端口浮动不影响 Cookie，也不影响 CORS。
      //
      // 代价：启动后要看终端打印的实际地址（可能是 5174、5175...）。
      strictPort: false,
      proxy,
    },

    // ── preview：预览生产构建产物 ──────────────────────────────
    // 用途：验收时用它代替 dev server。两个好处：
    //   1. 它**不启动文件监听**，因此不会遇到 dev server 在 Windows 上
    //      偶发的 EBUSY 崩溃（曾真实发生：PowerShell 覆写 .vue 文件时，
    //      Vite 的 watcher 与临时目录撞车，进程直接退出）
    //   2. 它跑的是真实构建产物，更接近部署形态
    //
    // 代价：改代码不会热更新，需要重新 `npm run build`。
    // 所以日常开发用 dev，**验收用 preview**。
    preview: {
      port: devPort,
      strictPort: true,
      proxy,
    },

    build: {
      // 生产构建不做 sourcemap，避免泄露源码结构
      sourcemap: false,
      chunkSizeWarningLimit: 1200,
      rollupOptions: {
        output: {
          // 把体积大的库拆出来，避免单个 chunk 过大影响首屏
          manualChunks: {
            vue: ['vue', 'vue-router', 'pinia'],
            'element-plus': ['element-plus'],
          },
        },
      },
    },
  }
})
