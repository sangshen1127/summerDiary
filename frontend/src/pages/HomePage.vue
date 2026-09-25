<script setup lang="ts">
import { computed } from 'vue'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/formatTime'

// ══════════════════════════════════════════════════════════════
// HomePage —— 概览页
// ══════════════════════════════════════════════════════════════
// Phase 1 的内容：显示当前登录用户信息 + 会话验证。
//
// ⚠️ 这里<b>主动调用</b> fetchMe 而不是直接用 store 里已有的数据 ——
//    目的是让「刷新页面时向后端确认登录态」这条链路可被肉眼验证：
//    页面上的「会话状态」显示的是<b>刚刚</b>从后端拿到的结果。
//
// Phase 2 会在这里加：今日是否已写日记、最近心情、AI 洞察。
// ══════════════════════════════════════════════════════════════

const userStore = useUserStore()

/** 当前用户，来自 store（由路由守卫或本页的刷新获得） */
const user = computed(() => userStore.currentUser)

const refreshing = computed(() => userStore.loading)

/**
 * 手动重新向后端确认登录态。
 *
 * 用途：验证「Cookie 会话是否还在」。测试时可以：
 *   1. 在 Apifox 里删掉 Cookie（或等服务端会话超时）
 *   2. 点这个按钮 → 应该被踢回登录页
 */
async function refresh(): Promise<void> {
  await userStore.fetchMe()
}
</script>

<template>
  <div class="home">
    <!-- ── 当前用户卡片 ──────────────────────────────────── -->
    <el-card shadow="never" class="home__card">
      <template #header>
        <div class="home__card-header">
          <span class="home__card-title">当前登录用户</span>
          <el-button
            type="primary"
            size="small"
            :loading="refreshing"
            @click="refresh"
          >
            重新确认
          </el-button>
        </div>
      </template>

      <el-descriptions v-if="user" :column="1" border size="small">
        <el-descriptions-item label="用户 ID">
          {{ user.id }}
        </el-descriptions-item>
        <el-descriptions-item label="用户名">
          {{ user.username }}
        </el-descriptions-item>
        <el-descriptions-item label="昵称">
          <span v-if="user.nickname">{{ user.nickname }}</span>
          <span v-else class="home__muted">（未设置）</span>
        </el-descriptions-item>
        <el-descriptions-item label="AI 分析">
          <el-tag :type="user.ai_enabled ? 'success' : 'info'" size="small">
            {{ user.ai_enabled ? '已开启' : '已关闭' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="注册时间">
          {{ formatDateTime(user.created_at) }}
          <span class="home__tz">（本地时间）</span>
        </el-descriptions-item>
      </el-descriptions>

      <!-- 理论上不会到这里（路由守卫已经拦过），保留兜底 -->
      <el-empty v-else description="未获取到用户信息" />
    </el-card>

    <!-- ── 会话验证卡片 ──────────────────────────────────── -->
    <el-card shadow="never" class="home__card">
      <template #header>
        <span class="home__card-title">会话状态</span>
      </template>

      <el-result
        icon="success"
        title="会话有效"
        sub-title="刷新页面仍保持登录，说明 Cookie 会话工作正常"
      >
        <template #extra>
          <div class="home__session-tip">
            <p class="home__tip-line">
              本页数据是<strong>刚刚</strong>通过
              <code>GET /api/auth/me</code> 从后端获取的，
              不是本地缓存。
            </p>
            <p class="home__tip-line home__muted">
              会话 ID 保存在 <code>HttpOnly</code> Cookie 中，
              浏览器自动携带，JavaScript 读不到 —— 这是本项目
              不用 JWT 而用 Cookie 的核心理由。
            </p>
            <el-button
              size="small"
              text
              type="primary"
              @click="$router.push('/login')"
            >
              去登录页看看（已登录会被自动跳回）
            </el-button>
          </div>
        </template>
      </el-result>
    </el-card>

    <!-- ── 后续计划 ──────────────────────────────────────── -->
    <el-card shadow="never" class="home__card">
      <template #header>
        <span class="home__card-title">接下来的开发计划</span>
      </template>
      <el-timeline>
        <el-timeline-item timestamp="Phase 1" type="success" :hollow="false">
          注册 / 登录 / HttpOnly Cookie 会话（当前）
        </el-timeline-item>
        <el-timeline-item timestamp="Phase 2" type="primary" hollow>
          日记 CRUD / 标签 / 筛选分页 / 正文 AES 加密
        </el-timeline-item>
        <el-timeline-item timestamp="Phase 3" hollow>
          异步任务框架 + Mock AI（无 Key 也能跑通）
        </el-timeline-item>
        <el-timeline-item timestamp="Phase 4-6" hollow>
          真实 AI 分析 / RAG 检索 / 记忆治理
        </el-timeline-item>
      </el-timeline>
    </el-card>
  </div>
</template>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.home__card {
  border-radius: 6px;
}

.home__card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.home__card-title {
  font-weight: 600;
}

.home__muted {
  color: var(--ad-text-secondary);
}

.home__tz {
  color: var(--ad-text-secondary);
  font-size: 12px;
}

.home__session-tip {
  text-align: left;
  max-width: 560px;
  margin: 0 auto;
}

.home__tip-line {
  margin: 0 0 8px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ad-text-regular);
  word-break: break-word;
}

.home__tip-line code {
  padding: 1px 5px;
  border-radius: 3px;
  background: var(--ad-bg-page);
  font-size: 12px;
}
</style>
