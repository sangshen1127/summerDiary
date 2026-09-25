<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import MascotSprite from '@/components/MascotSprite.vue'
import logoUrl from '@/image/看板娘2.png'

// ══════════════════════════════════════════════════════════════
// AppShell —— 受保护页面的外壳（导航 + 内容区）
// ══════════════════════════════════════════════════════════════
// 职责：提供全局导航和内容容器。页面本身不关心导航栏。
//
// ⚠️ 右上角的用户名来自 user store，而 store 的数据是路由守卫
//    调用 /api/auth/me 得到的 —— 所以这里显示的名字一定是
//    后端确认过的，不是本地缓存。
//
// 响应式：最小支持 375px（开发文档 §8.6）。
//   窄屏时隐藏导航文字只留图标，避免横向溢出。
// ══════════════════════════════════════════════════════════════

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

/**
 * 当前高亮的导航项。
 *
 * ⚠️ 不能用 `route.path === item.path` 直接比较：
 * 那样只有精确停在 /diaries 时才高亮，一旦进入
 * /diaries/12（详情）或 /diaries/new（写日记），
 * "日记"这一项就会失去高亮 —— 用户会觉得自己"离开了"这个栏目。
 *
 * 所以用"前缀匹配 + 边界判断"：路径等于该项，或者以 `该项/` 开头。
 * 加 `'/'` 是必须的，否则 `/diaries-archive` 这种同前缀路径会被误判。
 */
function isNavActive(path: string): boolean {
  const current = route.path
  return current === path || current.startsWith(`${path}/`)
}
const loggingOut = ref(false)

/**
 * 导航项。
 * 每完成一个 Phase 打开一条（注释掉的即设计意图）。
 */
const navItems = [
  { path: '/home', label: '概览' },
  { path: '/diaries', label: '日记' },        // Phase 2
  // { path: '/ai-chat', label: 'AI 对话' },     // Phase 4
  // { path: '/memory', label: '记忆' },         // Phase 6
  // { path: '/profile', label: '画像' },        // Phase 6
  // { path: '/settings', label: '设置' },       // Phase 7
]

/**
 * 退出登录。
 *
 * 成功后跳登录页。store 的 logout() 保证无论接口是否成功
 * 都会清空本地状态（见 stores/user.ts 的说明）。
 */
async function handleLogout(): Promise<void> {
  if (loggingOut.value) return
  loggingOut.value = true
  try {
    await userStore.logout()
    ElMessage.success('已退出登录')
    await router.replace('/login')
  } finally {
    loggingOut.value = false
  }
}
</script>

<template>
  <el-container class="shell">
    <!-- ── 顶栏 ──────────────────────────────────────────── -->
    <el-header class="shell__header">
      <div class="shell__brand">
        <!--
          品牌图标：看板娘头像（与登录/注册页一致）。
          ⚠️ alt="" + aria-hidden：右侧紧接着就是「AI 日记」文字，
          图标是纯装饰，重复朗读只会干扰读屏用户。
        -->
        <img class="shell__logo" :src="logoUrl" alt="" aria-hidden="true" />
        <span class="shell__title">AI 日记</span>
      </div>

      <nav class="shell__nav" aria-label="主导航">
        <router-link
          v-for="item in navItems"
          :key="item.path"
          :to="item.path"
          class="shell__nav-link"
          :class="{ 'is-active': isNavActive(item.path) }"
        >
          {{ item.label }}
        </router-link>
      </nav>

      <div class="shell__actions">
        <template v-if="userStore.isLoggedIn">
          <span class="shell__user" :title="userStore.currentUser?.username">
            {{ userStore.displayName }}
          </span>
          <el-button
            text
            size="small"
            :loading="loggingOut"
            @click="handleLogout"
          >
            退出
          </el-button>
        </template>
        <template v-else>
          <el-button text size="small" @click="router.push('/login')">
            登录
          </el-button>
        </template>
      </div>
    </el-header>

    <!-- ── 内容区 ────────────────────────────────────────── -->
    <el-main class="shell__main">
      <div class="shell__content">
        <router-view />
      </div>
    </el-main>

    <!--
      ── 看板娘（全站陪伴）────────────────────────────────
      放在 AppShell 而不是各个页面里，理由：
        1. 它是"陪伴感"的载体，应该每个受保护页面都在，
           而不是只在首页出现一次；放在外壳里天然满足。
        2. 只挂载一次，路由切换时不会反复创建/销毁 ——
           否则每次跳页图片都要重新解码，呼吸动效也会重启（看着像闪一下）。

      ⚠️ 刻意**不放在登录页/注册页**：那两页是 public 路由，
      不经过 AppShell（它们没有导航栏），所以自动就不会出现。
      这是合理的 —— 未登录时还不知道用户是谁，不适合"陪伴"。
    -->
    <MascotSprite />
  </el-container>
</template>

<style scoped>
.shell {
  min-height: 100vh;
}

.shell__header {
  display: flex;
  align-items: center;
  gap: 16px;
  height: var(--ad-header-height);
  padding: 0 16px;
  background: var(--ad-bg-card);
  border-bottom: 1px solid var(--ad-border-color);
  position: sticky;
  top: 0;
  z-index: 10;
}

.shell__brand {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

/*
 * 品牌图标 —— 看板娘头像（与 LoginPage / RegisterPage 一致）。
 *
 * ── 尺寸为什么是 34px ──────────────────────────────────────
 * 原来是 emoji「📔」(font-size: 20px)，换成图片后需要更大才看得清 ——
 * 但导航栏总高只有 56px（--ad-header-height），还要留上下呼吸空间，
 * 所以取 34px（比认证页的 64px 小，因为这里是横排紧凑布局，
 * 不像认证页那样是居中的品牌区）。
 *
 * ⚠️ 三处用了同一张图，尺寸各不相同（认证页 64px / 这里 34px），
 *    但**裁剪参数必须一致**（object-fit + object-position），
 *    否则同一个角色在不同页面会显示不同的部分，看起来像两个人。
 *
 * ── 裁切原因 ────────────────────────────────────────────────
 * 原图 1280×1280 且**角色占满整个画布**，整图缩到 34px 会看不清五官。
 * 用 object-position: 50% 12% 把**头部**裁出来聚焦到脸
 * （与认证页同一套参数）。
 */
.shell__logo {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  object-fit: cover;
  object-position: 50% 12%;
  /* 比认证页更轻的投影 —— 它在顶栏里，不需要抢注意力 */
  box-shadow: 0 1px 2px rgba(85, 96, 79, 0.1);
  flex-shrink: 0;
}

.shell__title {
  font-size: 16px;
  font-weight: 600;
  white-space: nowrap;
}

.shell__nav {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 1;
  overflow-x: auto;
  scrollbar-width: none;
}

.shell__nav::-webkit-scrollbar {
  display: none;
}

.shell__nav-link {
  padding: 6px 12px;
  border-radius: 4px;
  color: var(--ad-text-regular);
  text-decoration: none;
  white-space: nowrap;
  transition: background-color 0.2s, color 0.2s;
}

.shell__nav-link:hover {
  color: var(--ad-nav-active);
  background: var(--ad-bg-page);
}

.shell__nav-link.is-active {
  /*
   * ⚠️ 这里刻意用 --ad-nav-active 而不是 --ad-color-primary。
   *
   * 导航文字是 14px 的正常字号，属于 WCAG 的"正常文本"，
   * 对比度必须 >= 4.5:1。--ad-color-primary 是苔藓绿 #6F8F6A，
   * 在奶油白背景上只有约 3.4:1 —— 达不到 AA。
   *
   * --ad-nav-active 是为此专门定义的深绿（约 5.3:1）。
   * 拆成两个变量的理由见 theme.css 里的说明。
   */
  color: var(--ad-nav-active);
  background: var(--ad-bg-page);
  font-weight: 500;
}

.shell__actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.shell__user {
  color: var(--ad-text-regular);
  font-size: 13px;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.shell__main {
  padding: 20px 16px;
}

.shell__content {
  max-width: var(--ad-content-max-width);
  margin: 0 auto;
}

/* 窄屏（375px 起）：隐藏品牌文字，把空间让给导航 */
@media (max-width: 480px) {
  .shell__title {
    display: none;
  }

  .shell__header {
    gap: 8px;
    padding: 0 12px;
  }

  .shell__nav-link {
    padding: 6px 10px;
    font-size: 13px;
  }

  .shell__user {
    max-width: 70px;
  }

  .shell__main {
    padding: 12px 10px;
  }
}
</style>
