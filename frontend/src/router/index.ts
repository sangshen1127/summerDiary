import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'

// ══════════════════════════════════════════════════════════════
// 路由表
// ══════════════════════════════════════════════════════════════
// 完整路由见开发文档 §8.1。目前只建立骨架 + 首页，
// 其余页面在对应 Phase 逐个补齐（日记在 Phase 2，AI 对话在 Phase 4，
// Memory Center 在 Phase 6）。
//
// meta 约定：
//   public: true      免登录页面（登录、注册、404）
//   title: string     写入浏览器标题
//   非 public 页面默认需要登录
// ══════════════════════════════════════════════════════════════

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: () => import('@/layouts/AppShell.vue'),
    children: [
      {
        path: '',
        redirect: '/home',
      },
      {
        path: 'home',
        name: 'home',
        component: () => import('@/pages/HomePage.vue'),
        meta: { title: '概览' },
      },

      // ── Phase 2：日记闭环 ───────────────────────────────────
      {
        path: 'diaries',
        name: 'diary-list',
        component: () => import('@/pages/DiaryListPage.vue'),
        meta: { title: '日记' },
      },
      {
        // ⚠️ 'new' 必须排在 ':id' 之前吗？
        //    在 vue-router 4 里不需要 —— 静态段优先级高于动态段，
        //    即使写反了也会正确匹配到 'new'。这里仍然按"静态在前"排列，
        //    是为了让读代码的人一眼看清路由表结构，而不是依赖匹配优先级。
        path: 'diaries/new',
        name: 'diary-new',
        component: () => import('@/pages/DiaryEditorPage.vue'),
        meta: { title: '写日记' },
      },
      {
        // 编辑路由带 :id —— DiaryEditorPage 靠"路由里有没有 id"
        // 区分新建（/diaries/new）与编辑（/diaries/:id/edit）。
        // 用同一个组件是有意的：两者的表单、校验、标签选择完全一致，
        // 唯一差别是"挂载时要不要先拉详情"。
        path: 'diaries/:id/edit',
        name: 'diary-edit',
        component: () => import('@/pages/DiaryEditorPage.vue'),
        meta: { title: '编辑日记' },
      },
      {
        path: 'diaries/:id',
        name: 'diary-detail',
        component: () => import('@/pages/DiaryDetailPage.vue'),
        meta: { title: '日记详情' },
      },

      // ── 以下页面在后续 Phase 实现，先注释保留设计意图 ──────────
      // { path: 'memory', ... }      // Phase 6 Memory Center
      // { path: 'ai-chat', ... }     // Phase 4 AI 对话
      // { path: 'profile', ... }     // Phase 6 长期画像
      // { path: 'settings', ... }    // Phase 7 设置
    ],
  },

  {
    path: '/login',
    name: 'login',
    component: () => import('@/pages/LoginPage.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/pages/RegisterPage.vue'),
    meta: { public: true, title: '注册' },
  },

  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/pages/NotFoundPage.vue'),
    meta: { public: true, title: '页面不存在' },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(_to, _from, savedPosition) {
    // 浏览器后退时恢复原滚动位置，否则回到顶部
    return savedPosition ?? { top: 0 }
  },
})

// ══════════════════════════════════════════════════════════════
// 路由守卫
// ══════════════════════════════════════════════════════════════
// 核心规则（开发文档 §8.2）：
//   <b>首次打开受保护页面必须调 /api/auth/me 向后端确认，
//     不能只信任本地缓存。</b>
//
// 为什么必须问后端：
//   - Cookie 可能已在服务端失效（会话超时、被踢下线、用户删号）
//   - 用户可能在另一个标签页退出了登录
//   - 直接输入 URL 或刷新页面时，Pinia 状态是全新的，本地什么都问不出来
//
// 为什么会话 ID 不用前端管：
//   Cookie 是 HttpOnly，JS 读不到也不需要读。
//   前端只需知道「当前用户是谁」，而这个必须由后端回答。
// ══════════════════════════════════════════════════════════════

router.beforeEach(async (to) => {
  const userStore = useUserStore()

  // 免登录页面直接放行
  if (to.meta.public) {
    return true
  }

  // 首次进入受保护页面：向后端确认登录态
  if (!userStore.initialized) {
    await userStore.fetchMe()
  }

  if (!userStore.isLoggedIn) {
    // 带上原路径，登录成功后跳回 —— 用户体验关键点：
    // 用户直接访问 /diaries/42 被拦，登录后应该回到那一页而不是首页
    return {
      path: '/login',
      query: { redirect: to.fullPath },
    }
  }

  return true
})

router.afterEach((to) => {
  const base = 'AI 日记'
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · ${base}` : base
})

export default router
