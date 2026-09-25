<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { aiApi, diaryApi } from '@/api/diary'
import { useUserStore } from '@/stores/user'
import { useAsyncState } from '@/composables/useAsyncState'
import { formatDateTime, formatRelative } from '@/utils/formatTime'
import type { AppError } from '@/types/api'
import type { AnalysisEmotion, DiaryAnalysisDetail } from '@/types/diary'

// ══════════════════════════════════════════════════════════════
// DiaryDetailPage —— 日记详情
// ══════════════════════════════════════════════════════════════
// 显示已解密的正文全文（详情接口返回的 content 是明文）。
//
// ⚠️ 路由参数必须规范化：`route.params.id` 类型上是 string | string[]，
//    直接 Number() 在数组情况下会得到 NaN，然后请求 /diaries/NaN。
//
// ══════════════════════════════════════════════════════════════
// 「AI 分析」区块（Phase 2 是两种灰态，Phase 3 起是完整状态机）
// ══════════════════════════════════════════════════════════════
// Phase 2 时后端 `analysis_status` 恒为 null，所以只画了两种灰态。
// Phase 3 接上真实状态后，本区块要处理的是这台状态机：
//
//   enabled === false          → 服务端没有 AI 能力 → 灰态，无按钮
//   user.ai_enabled === false  → 用户自己关了      → 灰态，无按钮
//   status === null            → 尚未分析          → 「开始分析」按钮
//   status === 'pending'       → 排队 / 等待重试   → 转圈 + 【轮询】
//   status === 'running'       → 分析中            → 转圈 + 【轮询】
//   status === 'success'       → 有结果            → 渲染摘要/情绪/主题
//   status === 'failed'        → 失败              → 原因 + 重试按钮
//   status === 'cancelled'     → 已取消            → 说明 + 重试按钮
//   其它未知值                 → 兜底文案（**必须有**）
//
// ⚠️ 判断顺序是**从"能力"到"状态"**，不能颠倒：
//   `analysis_status === null` 有歧义（没任务 / AI 被关了 / 老数据），
//   必须先排除"能力不可用"，剩下的 null 才能解释成"尚未分析"。
//
// ⚠️ 轮询必须**能停下来**，且**只在 pending/running 时**进行。
//   漏了停止条件 = 用户把页面开着就一直在发请求（每 3 秒一次）。
//   这里在三个地方收口：状态转终态、组件卸载、请求出错。
// ══════════════════════════════════════════════════════════════

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

/** 轮询间隔。3 秒是在"响应及时"与"别把后端问烦"之间的折中。 */
const POLL_INTERVAL_MS = 3000

const diaryId = computed<number | null>(() => {
  const raw = route.params.id
  const value = Array.isArray(raw) ? raw[0] : raw
  if (!value) return null
  const n = Number(value)
  return Number.isFinite(n) && n > 0 ? n : null
})

/*
 * ⚠️⚠️ 解构而不是 `const detailState = useAsyncState(...)`。
 *
 * 理由与 DiaryListPage 里那段注释相同：模板的 ref 自动解包只对**顶层绑定**
 * 生效，`detailState.loading` 取到的是 ref 对象本身，而对象永远 truthy，
 * 于是 v-if 恒为真、页面永远停在加载态。
 *
 * 列表页就是这个 bug 的受害者，已由 headless 浏览器实测抓到
 * （`document.querySelectorAll('.diary').length === 0` 而 loading 区块存在）。
 */
const {
  loading,
  error,
  data: pageData,
  executed,
  execute,
} = useAsyncState(() => {
  const id = diaryId.value
  if (id === null) {
    return Promise.reject(new Error('缺少日记 ID'))
  }
  return diaryApi.detail(id)
})

const diary = computed(() => pageData.value)

/**
 * 是否被后端明确告知"不存在或不属于我"（40401）。
 *
 * ⚠️ 这里有个真实的坑：`useAsyncState` 的 `error` 是一个**字符串**
 * （它只存 message），所以拿不到业务码，没法用 `code === 40401` 判断。
 *
 * 而且它出错时会把 `data` 置为 null，于是"40401 不存在"和
 * "网络故障"在数据层面长得一样（都是 data=null + error 非空）。
 *
 * 两种处理办法，本页选了后者：
 *   1. 改 useAsyncState 让它保存完整错误对象（会影响所有已用它的人）
 *   2. 按文案判断（够用，因为 40401 的文案固定是"请求的内容不存在"）
 *
 * 选 2 的理由：改公共 composable 的影响面大于收益，而这里的区分
 * 只影响**提示文案**，判断错了也不会造成功能问题。
 * 如果将来需要按错误码做分支（比如 40401 要跳转、50001 要重试），
 * 那时再给 useAsyncState 加 `errorCode` 才是值得的。
 */
const notFound = computed(
  () =>
    executed.value &&
    !loading.value &&
    !diary.value &&
    error.value.includes('不存在'),
)

const deleting = ref(false)

async function handleDelete(): Promise<void> {
  const d = diary.value
  if (!d) return

  try {
    await ElMessageBox.confirm(
      `确定要删除《${d.title}》吗？删除后无法恢复。`,
      '删除日记',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }

  deleting.value = true
  try {
    await diaryApi.remove(d.id)
    ElMessage.success('已删除')
    await router.replace('/diaries')
  } catch (e) {
    const err = e as AppError
    if (err?.code === 40401) {
      ElMessage.info('这篇日记已经不存在了')
      await router.replace('/diaries')
      return
    }
    ElMessage.error(err?.message || '删除失败，请稍后重试')
  } finally {
    deleting.value = false
  }
}

function goEdit(): void {
  if (diaryId.value !== null) {
    void router.push(`/diaries/${diaryId.value}/edit`)
  }
}

/** 用户是否自己关闭了 AI（个人偏好，与后端全局开关是两件事） */
const aiDisabled = computed(() => userStore.currentUser?.ai_enabled === false)

// ══════════════════════════════════════════════════════════════
// AI 分析状态与轮询
// ══════════════════════════════════════════════════════════════

/** 分析状态 + 结果快照。null = 还没取到（首次加载中或取失败） */
const aiDetail = ref<DiaryAnalysisDetail | null>(null)
/**
 * 拉取状态失败的原因（网络/服务器问题，**不是**"分析失败"）。
 *
 * ⚠️ 与 `status === 'failed'`（模型分析失败）是两件事：
 *   - 这个 → 我们没问到后端，界面上是"刷新失败，点我重试"
 *   - 那个 → 后端明确说分析失败了，界面显示模型错误 + 重试分析按钮
 * 混在一起就会出现"后端明明说 AI_TIMEOUT，界面却提示检查网络"。
 */
const aiError = ref('')
/** 用户点了「开始分析」，正在等接口返回 */
const analyzing = ref(false)

/** 轮询定时器句柄。null 表示当前没有在轮询 */
let pollTimer: ReturnType<typeof setInterval> | null = null

/**
 * 把分析结果里的 JSON 字段安全地取成「数组」。
 *
 * ⚠️ 为什么需要这个函数而不是直接 `analysis.topics`：
 * 这些字段的内容来自**模型的 JSON 输出**。后端会校验，但校验规则
 * 与"前端渲染假设"不一定完全一致（比如某个数组里混进了一个对象、
 * 或者将来 schema 调整把字符串换成了对象）。直接 `v-for` 一个非数组
 * 值，Vue 不会报错但会渲染出意外的东西；而 `x.name` 取到 undefined
 * 则可能让 key 重复、列表错乱。
 *
 * 所以这里做一次"形状归一化"：非数组 → 空数组；对象元素 → 取 name/content。
 * 代价是十几行代码，收益是**模型输出再怎么变，页面也不会崩**。
 *
 * @param value 后端返回的原始值（类型上不可信）
 * @param pick 从对象元素里取哪个字段当展示文本
 */
function toTextList(value: unknown, pick: 'name' | 'content' = 'name'): string[] {
  if (!Array.isArray(value)) return []
  const out: string[] = []
  for (const item of value) {
    if (typeof item === 'string') {
      if (item.trim()) out.push(item)
    } else if (item && typeof item === 'object') {
      const text = (item as Record<string, unknown>)[pick]
      if (typeof text === 'string' && text.trim()) out.push(text)
    }
  }
  return out
}

/** 分析结果快照（没有则为 null）。模板里用它做判空，少写几层 `?.` */
const analysis = computed(() => aiDetail.value?.analysis ?? null)

/**
 * 是否已经有分析结果可渲染。
 *
 * ⚠️ 与 `status === 'success'` **不等价**：重新分析期间
 * status 是 pending/running，但旧结果仍在库里（后端是 upsert 覆盖，
 * 不是先删后插）。这个 computed 让"有内容就显示"成为模板的判断依据，
 * 用户点「重新分析」后不会看到结果凭空消失。
 */
const hasAnalysis = computed(() => analysis.value !== null)

/**
 * 取情绪对象里的 label / score，形状不对时返回 null（而不是渲染乱码）。
 */
const emotion = computed<AnalysisEmotion | null>(() => {
  const raw = analysis.value?.emotion
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const e = raw as AnalysisEmotion
  const hasLabel = typeof e.label === 'string' && e.label.trim().length > 0
  const hasScore = typeof e.score === 'number' && Number.isFinite(e.score)
  if (!hasLabel && !hasScore) return null
  return e
})

/** 主题（字符串数组，已过滤空值） */
const topics = computed(() => toTextList(analysis.value?.topics))

/** 实体名列表 */
const entityNames = computed(() => toTextList(analysis.value?.entities, 'name'))

/** 近期状态条目 */
const recentStates = computed(() => toTextList(analysis.value?.recent_state, 'content'))

/** 长期事实条目 */
const longTermFacts = computed(() => toTextList(analysis.value?.long_term_facts, 'content'))

/**
 * AI 区块当前该显示哪种界面。
 *
 * ⚠️ 顺序即优先级，**不能调换**：
 *   1. 服务端没能力 / 用户关了  → 后面一切都不用看了
 *   2. 还没取到状态             → 加载中
 *   3. 取状态失败               → 错误 + 重试拉取
 *   4. 任务状态                 → 具体渲染
 */
const aiView = computed<
  'unavailable' | 'user-disabled' | 'loading' | 'error' | 'idle' | 'working' | 'done' | 'failed' | 'unknown'
>(() => {
  const detail = aiDetail.value

  // ── 1. 能力层：这两个条件任一成立，都轮不到"任务状态"说话 ──
  // enabled=false 时后端连任务都不会创建，显示"尚未分析"会误导用户
  // （他会以为点一下就能分析，但点了只会收到 50011）。
  if (detail && !detail.enabled) return 'unavailable'
  if (aiDisabled.value) return 'user-disabled'

  // ── 2/3. 数据层 ────────────────────────────────────────────
  if (detail === null) {
    if (aiError.value) return 'error'
    return 'loading'
  }

  // ── 4. 任务状态层 ──────────────────────────────────────────
  switch (detail.status) {
    case null:
      return 'idle'
    case 'pending':
    case 'running':
      return 'working'
    case 'success':
      return 'done'
    case 'failed':
    case 'cancelled':
      return 'failed'
    default:
      // ⚠️ 必须有兜底：后端将来新增状态（比如 'queued'）时，
      //    没有 default 的话这个 computed 会返回 undefined，
      //    模板里所有 v-if/v-else-if 全部落空 → 整块空白且不报错。
      return 'unknown'
  }
})

/** 失败态的展示文案（含可重试次数），避免模板里堆逻辑 */
const aiFailureText = computed(() => {
  const detail = aiDetail.value
  if (!detail) return '分析没有成功。'
  if (detail.status === 'cancelled') {
    return '这次分析已取消（日记当时不可用）。'
  }
  return detail.error_message || '分析失败，暂时没有更详细的信息。'
})

/** 轮询中？用于在界面上给一点"正在自动刷新"的暗示 */
const awaitingResult = computed(() => aiView.value === 'working')

/**
 * 拉取一次分析状态，并按结果决定是否继续轮询。
 *
 * ⚠️ 这个函数是**唯一**改动 `aiDetail` 的地方。轮询、手动刷新、
 * 点完「开始分析」后的刷新都走它 —— 保证界面永远来自同一份快照，
 * 不会出现"轮询把状态更新了但结果没跟上"的错位。
 */
async function loadAnalysis(): Promise<void> {
  const id = diaryId.value
  if (id === null) return

  try {
    aiDetail.value = await aiApi.analysis(id)
    aiError.value = ''
  } catch (e) {
    // 拉取失败（网络断了、401 跳走了、日记刚被删）。
    // ⚠️ 这里**不能**直接把 aiDetail 清空：清空会让界面从"分析中"
    //    跳回"尚未分析"，用户会以为分析没跑；保留上一次快照 + 显示
    //    一条错误提示更诚实。所以只记错误，不动数据。
    aiError.value = (e as AppError)?.message || '获取分析状态失败'
  }

  syncPolling()
}

/** 启动/停止轮询，使其与当前状态一致。 */
function syncPolling(): void {
  const status = aiDetail.value?.status
  const shouldPoll = status === 'pending' || status === 'running'

  if (shouldPoll) {
    if (pollTimer === null) {
      pollTimer = setInterval(() => void loadAnalysis(), POLL_INTERVAL_MS)
    }
    return
  }
  stopPolling()
}

/** 停止轮询（幂等）。 */
function stopPolling(): void {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

/** 手动重试「拉取状态」（不是重试分析）。 */
async function retryLoadAnalysis(): Promise<void> {
  aiError.value = ''
  await loadAnalysis()
}

/** 点「开始分析」/「重新分析」/「重试」。 */
async function handleAnalyze(): Promise<void> {
  const id = diaryId.value
  if (id === null || analyzing.value) return

  analyzing.value = true
  try {
    // ⚠️ 这个接口是"排队"，返回的 status 多半还是 pending。
    //    所以拿到结果后必须重新拉一次完整详情（含 analysis 字段），
    //    而不是把返回值直接当详情用 —— 那样会丢掉上一次的分析内容。
    await aiApi.analyzeDiary(id)
    ElMessage.success('已加入分析队列')
    await loadAnalysis()
  } catch (e) {
    const err = e as AppError
    if (err?.code === 50011) {
      // 服务端 AI 能力没开。这不是用户操作错误，用 info 而不是 error，
      // 并把"能做什么"说清楚。
      ElMessage.info(err.message || 'AI 分析功能当前未启用')
    } else if (err?.code === 40401) {
      ElMessage.error('这篇日记不存在或不属于当前账号')
    } else {
      ElMessage.error(err?.message || '发起分析失败，请稍后重试')
    }
    // 失败也要刷新一次：可能是并发（别的标签页已经在分析了）
    await loadAnalysis()
  } finally {
    analyzing.value = false
  }
}

onMounted(() => {
  void execute()
  // AI 区块**独立**于正文加载：正文失败（404 等）时不必去取分析状态；
  // 正文成功后再取，两条请求互不阻塞。
  void loadAnalysis()
})

// ⚠️ 必须清掉定时器。漏了的症状是"离开页面后控制台仍在请求 /analysis"，
//    而且因为组件已卸载，回调里的赋值不会报错 —— 完全静默。
onBeforeUnmount(stopPolling)
</script>

<template>
  <div class="detail">
    <!-- ── 顶部操作条 ────────────────────────────────────── -->
    <div class="detail__bar">
      <button class="btn btn--text btn--sm" type="button" @click="router.push('/diaries')">
        <span aria-hidden="true">←</span> 返回列表
      </button>
      <div v-if="diary" class="detail__bar-actions">
        <button class="btn btn--ghost btn--sm" type="button" @click="goEdit">
          编辑
        </button>
        <button
          class="btn btn--ghost btn--sm detail__danger"
          type="button"
          :disabled="deleting"
          @click="handleDelete"
        >
          {{ deleting ? '删除中…' : '删除' }}
        </button>
      </div>
    </div>

    <!-- ── 加载中 ────────────────────────────────────────── -->
    <div v-if="loading" class="detail__card">
      <div class="detail__skeleton-line detail__skeleton-line--short" />
      <div class="detail__skeleton-line" />
      <div class="detail__skeleton-line detail__skeleton-line--mid" />
      <p class="detail__loading-text">正在解密正文…</p>
    </div>

    <!-- ── 加载失败 / 不存在 ─────────────────────────────── -->
    <div v-else-if="!diary" class="detail__card">
      <div class="alert alert--danger">
        <span aria-hidden="true">⚠</span>
        <div>
          <p class="detail__error-title">
            {{ notFound ? '这篇日记不存在' : '加载失败' }}
          </p>
          <p class="detail__error-msg">
            {{
              notFound
                ? '它可能已被删除，或者不属于当前账号。'
                : error
            }}
          </p>
        </div>
      </div>
      <div class="detail__error-actions">
        <button
          v-if="!notFound"
          class="btn btn--primary btn--sm"
          type="button"
          @click="execute"
        >
          重试
        </button>
        <button class="btn btn--ghost btn--sm" type="button" @click="router.push('/diaries')">
          返回列表
        </button>
      </div>
    </div>

    <!-- ── 正文 ──────────────────────────────────────────── -->
    <template v-else>
      <article class="detail__card">
        <p class="detail__date" :title="formatDateTime(diary.created_at)">
          <span aria-hidden="true">🗓</span>
          {{ formatDateTime(diary.created_at) }}
          <span class="detail__relative">{{ formatRelative(diary.created_at) }}</span>
        </p>

        <h1 class="detail__title">{{ diary.title }}</h1>

        <!-- 元信息 -->
        <div class="detail__meta">
          <span v-if="diary.mood" class="mood mood--calm">{{ diary.mood }}</span>
          <span v-if="diary.weather" class="detail__plain">{{ diary.weather }}</span>
          <span v-if="diary.location" class="detail__plain">
            <span aria-hidden="true">📍</span>{{ diary.location }}
          </span>
          <span v-for="tag in diary.tags" :key="tag.id" class="tag">
            {{ tag.name }}
          </span>
        </div>

        <!--
          正文用 white-space: pre-wrap 保留换行与缩进 ——
          用户写日记时的分段是有意义的，不能被折叠成一行。
        -->
        <div v-if="diary.content" class="detail__content">{{ diary.content }}</div>
        <p v-else class="detail__no-content">（这篇日记没有正文）</p>

        <p v-if="diary.updated_at !== diary.created_at" class="detail__updated">
          最后修改：{{ formatDateTime(diary.updated_at) }}
        </p>
      </article>

      <!-- ── AI 分析区块 ───────────────────────────────────── -->
      <!--
        Phase 3 起这里是一个完整的状态机（判断顺序见 script 里的 aiView）。
        ⚠️ 每个分支都必须有**唯一的**判断条件，且用 v-else-if 串联 ——
           分散的 v-if 会让"两个条件同时为真"变成两块内容同时显示。
      -->
      <section class="detail__card detail__ai" aria-label="AI 分析">
        <h2 class="detail__ai-title">
          <span aria-hidden="true">🌿</span> AI 分析
          <!-- 轮询中给一个不打扰的提示，让用户知道页面在自己刷新 -->
          <span v-if="awaitingResult" class="detail__ai-polling">自动刷新中</span>
        </h2>

        <!-- 情况 1：服务端没有 AI 能力（全局开关关闭 / 没配 Key） -->
        <p v-if="aiView === 'unavailable'" class="detail__ai-hint">
          本服务暂未开放 AI 分析。
          <span class="detail__ai-note">（这是服务端配置，稍后可以再回来看看）</span>
        </p>

        <!-- 情况 2：用户自己关闭了 AI（个人偏好，与上面那个是两件事） -->
        <p v-else-if="aiView === 'user-disabled'" class="detail__ai-hint">
          你已关闭 AI 分析，这篇日记不会被送入模型。
          <span class="detail__ai-note">（可在设置页重新开启）</span>
        </p>

        <!-- 情况 3：正在取分析状态 -->
        <p v-else-if="aiView === 'loading'" class="detail__ai-hint">
          正在获取分析状态…
        </p>

        <!-- 情况 4：取状态失败（注意：这不是"分析失败"） -->
        <template v-else-if="aiView === 'error'">
          <p class="detail__ai-hint">
            {{ aiError }}
          </p>
          <div class="detail__ai-actions">
            <button
              class="btn btn--ghost btn--sm"
              type="button"
              @click="retryLoadAnalysis"
            >
              重新获取
            </button>
          </div>
        </template>

        <!-- 情况 5：尚未分析（还没有任务） -->
        <template v-else-if="aiView === 'idle'">
          <p class="detail__ai-hint">
            尚未分析。
            <span class="detail__ai-note">
              分析会生成一句话摘要、情绪与主题 —— 需要消耗一次模型调用。
            </span>
          </p>
          <div class="detail__ai-actions">
            <button
              class="btn btn--primary btn--sm"
              type="button"
              :disabled="analyzing || !aiDetail?.can_retry"
              @click="handleAnalyze"
            >
              {{ analyzing ? '提交中…' : '开始分析' }}
            </button>
          </div>
        </template>

        <!-- 情况 6：分析中，且**还没有**任何结果可显示 -->
        <template v-else-if="aiView === 'working' && !hasAnalysis">
          <p class="detail__ai-hint">
            <span class="detail__ai-spinner" aria-hidden="true" />
            正在分析这篇日记…
            <span class="detail__ai-note">（排队与重试都算在内，完成后会自动显示）</span>
          </p>
        </template>

        <!-- 情况 7：分析失败 / 已取消 -->
        <template v-else-if="aiView === 'failed'">
          <div class="alert alert--danger">
            <span aria-hidden="true">⚠</span>
            <div>
              <p class="detail__error-title">分析没有完成</p>
              <p class="detail__error-msg">{{ aiFailureText }}</p>
              <p v-if="aiDetail?.error_code" class="detail__ai-code">
                错误类别：{{ aiDetail.error_code }}
                <template v-if="aiDetail.retry_count > 0">
                  · 已尝试 {{ aiDetail.retry_count }} / {{ aiDetail.max_retries }} 次
                </template>
              </p>
            </div>
          </div>
          <div class="detail__ai-actions">
            <button
              class="btn btn--primary btn--sm"
              type="button"
              :disabled="analyzing || !aiDetail?.can_retry"
              @click="handleAnalyze"
            >
              {{ analyzing ? '提交中…' : '重试分析' }}
            </button>
          </div>
        </template>

        <!-- 情况 8：兜底 —— 后端返回了前端还不认识的状态 -->
        <!--
          ⚠️ 这个分支不是"多余的防御"：后端的取值集合只增不减，
             真新增一个状态时，没有它整块会变成空白且**不报错**。
        -->
        <template v-else-if="aiView === 'unknown'">
          <p class="detail__ai-hint">
            分析状态暂时无法识别（{{ aiDetail?.status }}）。
            <span class="detail__ai-note">（前端版本可能落后于后端，可尝试刷新页面）</span>
          </p>
        </template>

        <!--
          情况 9：有结果可渲染。
          走到这里的两种来源：
            a) status === 'success'                         —— 正常完成
            b) status === 'pending'/'running' 但库里有旧结果 —— 正在【重新分析】
          b 这种情况刻意复用同一段渲染，避免"两份几乎一样的模板"将来改一处漏一处。
        -->
        <template v-else>
          <!-- 9a：完成但结果为空 —— 理论上不该发生，如实说明而不是假装有内容 -->
          <p v-if="!hasAnalysis" class="detail__ai-hint">
            分析已完成，但没有返回可展示的内容。
          </p>

          <template v-else>
            <!-- 正在重新分析时，先说明下面这块是旧的，避免用户误以为是新结果 -->
            <p v-if="aiView === 'working'" class="detail__ai-hint">
              <span class="detail__ai-spinner" aria-hidden="true" />
              正在重新分析…以下是上一次的结果。
            </p>

            <p v-if="analysis?.summary" class="detail__ai-summary">
              {{ analysis.summary }}
            </p>

            <!-- 情绪 -->
            <div v-if="emotion" class="detail__ai-row">
              <span class="detail__ai-label">情绪</span>
              <span class="mood mood--calm">{{ emotion.label || '未知' }}</span>
              <span v-if="typeof emotion.score === 'number'" class="detail__ai-score">
                强度 {{ Math.round(emotion.score * 100) }}%
              </span>
            </div>

            <!-- 主题 -->
            <div v-if="topics.length" class="detail__ai-row">
              <span class="detail__ai-label">主题</span>
              <span v-for="t in topics" :key="t" class="tag">{{ t }}</span>
            </div>

            <!-- 实体 -->
            <div v-if="entityNames.length" class="detail__ai-row">
              <span class="detail__ai-label">提到</span>
              <!--
                实体用 tag--plain：视觉上比「主题」弱一档。
                主题是"这篇日记在讲什么"（主要信息），实体是"提到了谁/什么"
                （次要信息），两者用同一个样式会让读者分不清主次。
              -->
              <span v-for="name in entityNames" :key="name" class="tag tag--plain">
                {{ name }}
              </span>
            </div>

            <!-- 近期状态 -->
            <div v-if="recentStates.length" class="detail__ai-row detail__ai-row--stack">
              <span class="detail__ai-label">近期状态</span>
              <ul class="detail__ai-list">
                <li v-for="(item, i) in recentStates" :key="i">{{ item }}</li>
              </ul>
            </div>

            <!-- 长期事实 -->
            <div v-if="longTermFacts.length" class="detail__ai-row detail__ai-row--stack">
              <span class="detail__ai-label">可能沉淀为长期记忆</span>
              <ul class="detail__ai-list">
                <li v-for="(item, i) in longTermFacts" :key="i">{{ item }}</li>
              </ul>
            </div>

            <p class="detail__ai-foot">
              <span v-if="analysis?.created_at">
                {{ formatDateTime(analysis.created_at) }} 由 AI 生成
              </span>
              <span v-if="analysis?.prompt_version" class="detail__ai-note">
                · Prompt {{ analysis.prompt_version }}
              </span>
            </p>

            <!--
              ⚠️ 重新分析按钮只在**没有任务在跑**时出现。
                 正在跑的时候，can_retry 是 false，按钮会禁用 ——
                 但更好的是干脆不画：一个永远点不动的按钮只会让人困惑。
            -->
            <div v-if="aiView !== 'working'" class="detail__ai-actions">
              <button
                class="btn btn--ghost btn--sm"
                type="button"
                :disabled="analyzing || !aiDetail?.can_retry"
                @click="handleAnalyze"
              >
                {{ analyzing ? '提交中…' : '重新分析' }}
              </button>
            </div>
          </template>
        </template>

        <!--
          非阻塞的刷新失败提示：当已经有快照、只是这次刷新失败时，
          不推翻整块界面（那会让人以为分析没了），只在底部说一声。
        -->
        <p v-if="aiDetail && aiError" class="detail__ai-refresh-error">
          状态刷新失败：{{ aiError }}
          <button class="btn btn--text btn--sm" type="button" @click="retryLoadAnalysis">
            重试
          </button>
        </p>
      </section>
    </template>
  </div>
</template>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 760px;
  margin: 0 auto;
}

/* ── 操作条 ───────────────────────────────────────────────── */
.detail__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.detail__bar-actions {
  display: flex;
  gap: 8px;
}

.detail__danger {
  color: var(--ad-color-danger);
}

/* ── 卡片 ─────────────────────────────────────────────────── */
.detail__card {
  background: var(--ad-bg-card);
  border: 1px solid var(--ad-border-color);
  border-radius: 12px;
  padding: 22px 24px;
}

.detail__date {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 10px;
  font-size: 12.5px;
  color: var(--ad-text-secondary);
  letter-spacing: 0.04em;
}

.detail__relative {
  color: var(--ad-text-placeholder);
}

.detail__title {
  margin: 0 0 12px;
  font-size: 22px;
  font-weight: 600;
  line-height: 1.5;
  color: var(--ad-text-primary);
  word-break: break-word;
}

.detail__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 18px;
}

.detail__plain {
  font-size: 12.5px;
  color: var(--ad-text-secondary);
}

/* ── 正文 ─────────────────────────────────────────────────── */
.detail__content {
  font-size: 15px;
  line-height: 1.95;
  color: var(--ad-text-regular);
  /* 保留用户写的换行与缩进 */
  white-space: pre-wrap;
  word-break: break-word;
  overflow-wrap: anywhere;
}

.detail__no-content {
  margin: 0;
  font-size: 14px;
  color: var(--ad-text-placeholder);
  font-style: italic;
}

.detail__updated {
  margin: 18px 0 0;
  font-size: 12px;
  color: var(--ad-text-placeholder);
}

/* ── AI 区块 ──────────────────────────────────────────────── */
.detail__ai {
  background: var(--ad-bg-page);
}

.detail__ai-title {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 0 8px;
  font-size: 14.5px;
  font-weight: 600;
  color: var(--ad-text-primary);
}

.detail__ai-hint {
  margin: 0;
  font-size: 13.5px;
  line-height: 1.8;
  color: var(--ad-text-secondary);
}

.detail__ai-note {
  color: var(--ad-text-placeholder);
}

/* 标题右侧的"自动刷新中"标记 */
.detail__ai-polling {
  margin-left: auto;
  font-size: 11.5px;
  font-weight: 400;
  color: var(--ad-text-placeholder);
}

/* 摘要 —— 区块里视觉权重最高的一段 */
.detail__ai-summary {
  margin: 0 0 14px;
  font-size: 14.5px;
  line-height: 1.85;
  color: var(--ad-text-regular);
}

/* 一行「标签 + 内容」 */
.detail__ai-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}

/* 内容较长时改成上下排布（列表） */
.detail__ai-row--stack {
  align-items: flex-start;
  flex-direction: column;
  gap: 4px;
}

.detail__ai-label {
  flex: none;
  font-size: 12px;
  color: var(--ad-text-placeholder);
  letter-spacing: 0.04em;
}

.detail__ai-score {
  font-size: 12px;
  color: var(--ad-text-secondary);
}

.detail__ai-list {
  margin: 0;
  padding-left: 18px;
  font-size: 13.5px;
  line-height: 1.8;
  color: var(--ad-text-regular);
}

.detail__ai-foot {
  display: flex;
  align-items: center;
  gap: 5px;
  flex-wrap: wrap;
  margin: 14px 0 0;
  font-size: 12px;
  color: var(--ad-text-placeholder);
}

.detail__ai-actions {
  display: flex;
  gap: 8px;
  margin-top: 14px;
}

.detail__ai-code {
  margin: 5px 0 0;
  font-size: 12px;
  color: var(--ad-text-secondary);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

/* 「状态刷新失败」的非阻塞提示 —— 视觉上要弱，不能盖过正文 */
.detail__ai-refresh-error {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  margin: 14px 0 0;
  font-size: 12px;
  color: var(--ad-color-warning, var(--ad-text-secondary));
}

/* 转圈：纯 CSS，不引第三方组件 */
.detail__ai-spinner {
  display: inline-block;
  width: 11px;
  height: 11px;
  margin-right: 6px;
  vertical-align: -1px;
  border: 1.6px solid var(--ad-border-color);
  border-top-color: var(--ad-color-primary);
  border-radius: 50%;
  animation: detail-spin 0.8s linear infinite;
}

@keyframes detail-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .detail__ai-spinner {
    animation: none;
  }
}

/* ── 骨架屏 ───────────────────────────────────────────────── */
.detail__skeleton-line {
  height: 13px;
  border-radius: 999px;
  margin-bottom: 12px;
  background: linear-gradient(
    90deg,
    var(--ad-bg-page) 25%,
    var(--ad-border-color) 37%,
    var(--ad-bg-page) 63%
  );
  background-size: 400% 100%;
  animation: detail-skeleton 1.6s ease-in-out infinite;
}

.detail__skeleton-line--short {
  width: 26%;
}

.detail__skeleton-line--mid {
  width: 62%;
}

@keyframes detail-skeleton {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .detail__skeleton-line {
    animation: none;
  }
}

.detail__loading-text {
  margin: 16px 0 0;
  text-align: center;
  font-size: 13px;
  color: var(--ad-text-secondary);
}

/* ── 错误态 ───────────────────────────────────────────────── */
.detail__error-title {
  margin: 0;
  font-weight: 600;
}

.detail__error-msg {
  margin: 3px 0 0;
  font-size: 13px;
}

.detail__error-actions {
  display: flex;
  gap: 8px;
  margin-top: 14px;
}

/* ── 窄屏 375px ───────────────────────────────────────────── */
@media (max-width: 480px) {
  .detail__card {
    padding: 16px 14px;
  }

  .detail__title {
    font-size: 19px;
  }

  .detail__content {
    font-size: 14.5px;
  }
}
</style>
