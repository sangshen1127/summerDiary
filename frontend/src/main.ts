import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

import App from './App.vue'
import router from './router'
import { useAppStore } from './stores/app'

// Element Plus 全量引入。
// 开发文档 §0 决策 1 已冻结 UI 方案为 Element Plus，不引入 Tailwind。
// 若后续首屏体积成为问题，可改用 unplugin-vue-components 按需引入，
// 但那会多一层构建配置，Phase 0 不做提前优化。
import 'element-plus/dist/index.css'
import './styles/global.css'
// ⚠️ theme.css 必须在 global.css 之后引入。
//
// 它承载「森语时光」设计系统：设计 Token + 组件 class（.btn / .field /
// .tag / .mood / .alert / .diary）+ 把 --ad-* 与 --el-* 重新映射到森语色板。
//
// 关于优先级：theme.css 的重映射写在 `html:root` 里（权重 0,1,1），
// 而 global.css 和 element-plus 用的是 `:root`（权重 0,1,0），
// 所以 theme.css **一定**生效，不依赖这里的引入顺序 ——
// 换句话说下面的顺序只是"读起来合理"，真正保证覆盖的是选择器权重。
import './styles/theme.css'

const app = createApp(App)

const pinia = createPinia()
app.use(pinia)

// ⚠️ 必须在 use(pinia) 之后才能用 store
useAppStore().init()

app.use(router)
app.use(ElementPlus, { locale: zhCn })

// 全局兜底：组件内未捕获的异常不至于白屏
app.config.errorHandler = (err, _instance, info) => {
  console.error('[vue] 未捕获异常：', err, info)
}

app.mount('#app')
