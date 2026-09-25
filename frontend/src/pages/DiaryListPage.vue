<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { diaryApi, tagApi } from '@/api/diary'
import type { Diary, DiaryQuery, Tag } from '@/types/diary'
import type { AppError } from '@/types/api'
import { useAsyncState } from '@/composables/useAsyncState'
import { formatDate } from '@/utils/formatTime'
import EmptyState from '@/components/EmptyState.vue'

// ══════════════════════════════════════════════════════════════
// DiaryListPage —— 日记列表
// ══════════════════════════════════════════════════════════════
// 开发文档 §8.5：数据页面必须显式处理 loading / empty / error 三种状态。
//
// ⚠️ 列表接口返回的每一条 `content` 都是 null（后端刻意不解密正文）。
//    所以本页**不显示正文摘要** —— 想显示就必须让列表返回明文，
//    那会成倍扩大明文暴露面（网络 + 浏览器内存 + 中间日志），
//    与"列表只给标题和元信息、正文只在详情页出现"的取舍冲突。
//    这是刻意的产品决定，不是没做完。
//
// ⚠️ keyword **只搜标题**。搜索框的 placeholder 里写明了这一点 ——
//    如果不写，用户搜正文搜不到会以为是坏了。
// ══════════════════════════════════════════════════════════════

const router = useRouter()

// ── 筛选条件 ───────────────────────────────────────────────────
// 用 reactive 而不是 ref，省掉每处 .value
const query = reactive<DiaryQuery>({
  page: 0,
  size: 10,
})

/** 搜索框的值。与 query.keyword 分开，避免每敲一个字就重新请求 */
const keywordInput = ref('')

/** 标签列表（用于筛选下拉）。标签很少，一次全取，不分页 */
const tags = ref<Tag[]>([])

// ── 列表数据 ───────────────────────────────────────────────────
/*
 * ⚠️⚠️ 必须**解构**，不能写 `const listState = useAsyncState(...)`
 *      然后在模板里用 `loading` —— 这是一个真实踩过的坑。
 *
 * 原因：模板的 ref 自动解包只对**顶层绑定**生效（setup 返回的对象
 * 会经 proxyRefs 处理，顶层 ref 自动解包）。而 `listState` 是个普通
 * 对象，`loading` 取到的是里面的 **ref 对象本身**。
 *
 * 后果非常隐蔽：
 *   v-if="loading"   ← 拿到 ref 对象，对象永远 truthy
 *   → loading 分支永远显示，骨架屏永不消失
 *   → 数据其实已经回来了（"共 3 篇"是对的），列表却一条都不渲染
 *   → 而且不报任何错、TypeScript 也不报错（模板类型检查查不出这个）
 *
 * 这个 bug 是模块 2-4 用 headless 浏览器实测时抓到的：
 *   document.querySelectorAll('.diary').length === 0
 *   document.querySelector('.diary-list__loading') 存在
 * 静态检查（vue-tsc）和 HTTP 200 都发现不了它 ——
 * **只有真实渲染才能暴露**，这就是为什么前端必须做浏览器验证。
 *
 * 解构之后 loading / error / isEmpty / data 都成了顶层 ref，模板自动解包。
 */
const { loading, error, data: pageData, isEmpty, execute } = useAsyncState(() =>
  diaryApi.list(query),
)
const diaries = ref<Diary[]>([])

/**
 * el-pagination 要的"当前页"是 **1-based**，而后端和我们的 query 是
 * **0-based**。这个 ±1 的换算只在这里做一次，不散落到模板里。
 *
 * ⚠️ 用 `?? 0` 是因为 `DiaryQuery.page` 在类型上是可选的
 * （它同时充当"查询参数对象"，字段当然可以缺省）。
 */
const currentPageNumber = computed(() => (query.page ?? 0) + 1)

/**
 * 总条数。
 *
 * ⚠️ 与上面的解构同理：`pageData` 是 ref，在**脚本里**要写 `.value`；
 * 模板里若直接用 `pageData?.total` 会拿到 undefined。
 * 抽成 computed 后模板写 `total` 即可，且只在这一处解包。
 */
const total = computed(() => pageData.value?.total ?? 0)

/**
 * 加载列表。
 *
 * ⚠️ 这里手动把 data 复制到 diaries，而不是直接用 pageData。
 * 原因：删除一篇日记后需要"就地更新列表"，而 pageData 是
 * useAsyncState 内部管理的，直接改它绕过了它的语义。
 * 用一个本地 ref 更直白，也让"删除后要不要重新请求"这件事由页面决定。
 */
async function load(): Promise<void> {
  const result = await execute()
  diaries.value = result?.items ?? []
}

/** 条件变化时回到第 1 页再查 —— 否则会出现"在第 3 页筛选，结果为空"的困惑 */
async function reloadFromFirstPage(): Promise<void> {
  query.page = 0
  await load()
}

/** 执行搜索（回车或点搜索按钮触发） */
async function handleSearch(): Promise<void> {
  query.keyword = keywordInput.value.trim() || undefined
  await reloadFromFirstPage()
}

/** 清空全部筛选条件 */
async function resetFilters(): Promise<void> {
  keywordInput.value = ''
  delete query.keyword
  delete query.mood
  delete query.tagId
  await reloadFromFirstPage()
}

/** 是否有任何筛选条件生效（决定要不要显示"清空"按钮） */
function hasActiveFilter(): boolean {
  return Boolean(query.keyword || query.mood || query.tagId !== undefined)
}

function handlePageChange(newPage: number): void {
  query.page = newPage
  void load()
}

function handleSizeChange(newSize: number): void {
  query.size = newSize
  query.page = 0
  void load()
}

/** 进入详情 */
function openDetail(id: number): void {
  void router.push(`/diaries/${id}`)
}

/** 进入新建 */
function openCreate(): void {
  void router.push('/diaries/new')
}

/**
 * 删除日记。
 *
 * ⚠️ 删除类操作必须二次确认（开发文档 §8.6）。
 * 用 ElMessageBox.confirm 而不是自己写弹窗 —— 后者容易漏掉
 * 键盘可达性、焦点陷阱这些细节。
 */
async function handleDelete(diary: Diary): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定要删除《${diary.title}》吗？删除后无法恢复。`,
      '删除日记',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    // 用户点了取消 —— 这不是错误，静默返回
    return
  }

  try {
    await diaryApi.remove(diary.id)
    ElMessage.success('已删除')
    // 删除后重新拉一次：如果删的是本页最后一条，total 和分页都要跟着变。
    // 只从本地数组删掉那一条会让"当前页 0 条但 total 还有"的显示不一致。
    await load()
  } catch (e) {
    const err = e as AppError
    // 40401 说明它已经不在了（可能另一个标签页删过）——
    // 这时重新加载列表比报错更有用
    if (err?.code === 40401) {
      ElMessage.info('这篇日记已经不存在了，列表已刷新')
      await load()
      return
    }
    ElMessage.error(err?.message || '删除失败，请稍后重试')
  }
}

/** 取标签名用于筛选栏展示 */
function activeTagName(): string {
  return tags.value.find((t) => t.id === query.tagId)?.name ?? ''
}

onMounted(async () => {
  // 标签和列表并行加载，标签失败不阻塞列表（它只是筛选辅助）
  const [tagList] = await Promise.all([
    tagApi.list().catch(() => [] as Tag[]),
    load(),
  ])
  tags.value = tagList
})
</script>

<template>
  <div class="diary-list">
    <!-- ── 页头 ──────────────────────────────────────────── -->
    <header class="diary-list__head">
      <div>
        <h1 class="diary-list__title">我的日记</h1>
        <p class="diary-list__sub">
          共 {{ total }} 篇
        </p>
      </div>
      <button class="btn btn--primary" type="button" @click="openCreate">
        <span aria-hidden="true">✎</span> 写一篇
      </button>
    </header>

    <!-- ── 筛选栏 ────────────────────────────────────────── -->
    <section class="diary-list__filters" aria-label="筛选条件">
      <div class="field diary-list__search">
        <input
          v-model="keywordInput"
          class="field__input"
          type="search"
          placeholder="搜索标题（正文已加密，不支持搜索）"
          aria-label="按标题搜索"
          @keyup.enter="handleSearch"
        />
      </div>

      <el-select
        v-model="query.mood"
        placeholder="心情"
        clearable
        class="diary-list__select"
        @change="reloadFromFirstPage"
      >
        <el-option label="开心" value="开心" />
        <el-option label="平静" value="平静" />
        <el-option label="焦虑" value="焦虑" />
        <el-option label="疲惫" value="疲惫" />
        <el-option label="兴奋" value="兴奋" />
      </el-select>

      <el-select
        v-model="query.tagId"
        placeholder="标签"
        clearable
        class="diary-list__select"
        @change="reloadFromFirstPage"
      >
        <el-option
          v-for="tag in tags"
          :key="tag.id"
          :label="tag.name"
          :value="tag.id"
        />
      </el-select>

      <button class="btn btn--ghost btn--sm" type="button" @click="handleSearch">
        搜索
      </button>
      <button
        v-if="hasActiveFilter()"
        class="btn btn--text btn--sm"
        type="button"
        @click="resetFilters"
      >
        清空
      </button>
    </section>

    <!-- ── 生效的筛选提示 ────────────────────────────────── -->
    <p v-if="hasActiveFilter()" class="diary-list__active-filter">
      当前筛选：
      <span v-if="query.keyword">标题含「{{ query.keyword }}」</span>
      <span v-if="query.mood">心情「{{ query.mood }}」</span>
      <span v-if="query.tagId">标签「{{ activeTagName() }}」</span>
    </p>

    <!-- ── 内容区：四种状态显式处理 ──────────────────────── -->

    <!-- 1) loading -->
    <div v-if="loading" class="diary-list__loading">
      <div v-for="i in 3" :key="i" class="diary-list__skeleton" aria-hidden="true">
        <div class="diary-list__skeleton-line diary-list__skeleton-line--short" />
        <div class="diary-list__skeleton-line" />
      </div>
      <p class="diary-list__loading-text">正在翻开日记本…</p>
    </div>

    <!-- 2) error -->
    <div v-else-if="error" class="diary-list__error">
      <div class="alert alert--danger">
        <span aria-hidden="true">⚠</span>
        <div>
          <p class="diary-list__error-title">加载失败</p>
          <p class="diary-list__error-msg">{{ error }}</p>
        </div>
      </div>
      <button class="btn btn--ghost btn--sm" type="button" @click="load">
        重新加载
      </button>
    </div>

    <!-- 3) empty —— 区分"一篇都没有"和"筛选后为空"，文案不同 -->
    <EmptyState
      v-else-if="isEmpty"
      :icon="hasActiveFilter() ? '🔍' : '🌱'"
      :title="hasActiveFilter() ? '没有符合条件的日记' : '还没有写过日记'"
      :description="
        hasActiveFilter()
          ? '换个条件试试，或者清空筛选看看全部。'
          : '记录下今天的一点小事，从这里开始。'
      "
      :action-text="hasActiveFilter() ? '清空筛选' : '写第一篇'"
      @action="hasActiveFilter() ? resetFilters() : openCreate()"
    />

    <!-- 4) 有数据 -->
    <template v-else>
      <!--
        ⚠️ 容器用主题的 `.card`，不要自己写圆角/边框。
        原因：`.diary` 的 hover 绿叶条纹（::before）和最后一条的分隔线处理，
        都依赖"它是 .card 的直接子元素 + .card 的 overflow:hidden 裁切圆角"。
        自己包一层带 padding 的容器会让那条约 3px 的绿线位置偏移，
        也会让首尾两条的圆角裁切失效。
        theme.css 里作者特意注明了这个约束。
      -->
      <ul class="card diary-list__items">
        <li
          v-for="diary in diaries"
          :key="diary.id"
          class="diary"
          tabindex="0"
          role="button"
          @click="openDetail(diary.id)"
          @keyup.enter="openDetail(diary.id)"
        >
          <p class="diary__date">
            <span aria-hidden="true">🗓</span>
            {{ formatDate(diary.created_at) }}
          </p>
          <h2 class="diary__title">{{ diary.title }}</h2>

          <!-- 元信息：心情 / 天气 / 地点 / 标签 -->
          <div class="diary-list__meta">
            <span v-if="diary.mood" class="mood mood--calm">{{ diary.mood }}</span>
            <span v-if="diary.weather" class="diary-list__plain">{{ diary.weather }}</span>
            <span v-if="diary.location" class="diary-list__plain">
              <span aria-hidden="true">📍</span>{{ diary.location }}
            </span>
            <span v-for="tag in diary.tags" :key="tag.id" class="tag">
              {{ tag.name }}
            </span>
          </div>

          <div class="diary-list__actions">
            <button
              class="btn btn--text btn--sm"
              type="button"
              @click.stop="openDetail(diary.id)"
            >
              查看
            </button>
            <button
              class="btn btn--text btn--sm diary-list__danger"
              type="button"
              @click.stop="handleDelete(diary)"
            >
              删除
            </button>
          </div>
        </li>
      </ul>

      <!-- 分页 -->
      <div v-if="total > 0" class="diary-list__pager">
        <el-pagination
          :current-page="currentPageNumber"
          :page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="(p: number) => handlePageChange(p - 1)"
          @size-change="handleSizeChange"
        />
      </div>
    </template>
  </div>
</template>

<style scoped>
.diary-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ── 页头 ─────────────────────────────────────────────────── */
.diary-list__head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.diary-list__title {
  margin: 0;
  font-family: var(--ad-font-round, inherit);
  font-size: 24px;
  font-weight: 600;
  color: var(--ad-text-primary);
}

.diary-list__sub {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--ad-text-secondary);
}

/* ── 筛选栏 ───────────────────────────────────────────────── */
.diary-list__filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.diary-list__search {
  flex: 1 1 240px;
  min-width: 180px;
}

.diary-list__select {
  width: 130px;
  flex-shrink: 0;
}

.diary-list__active-filter {
  margin: 0;
  font-size: 12.5px;
  color: var(--ad-text-secondary);
}

.diary-list__active-filter span + span::before {
  content: '·';
  margin: 0 6px;
}

/* ── 列表 ─────────────────────────────────────────────────── */
/*
 * ⚠️ 列表项自己的外观（padding / 分隔线 / hover 绿叶 / 圆角裁切）
 *    全部由 theme.css 的 `.diary` 提供，这里**刻意不复制一份**。
 *
 *    踩过的思路陷阱：一开始我在这里写了 `.diary { padding: 18px 22px;
 *    border-bottom: ... }`，等于把主题样式抄了一遍 —— 结果是
 *    主题后来更新时页面不会跟着变，而且抄漏了 hover 的绿叶动效。
 *    只在需要"补充"时才写样式，不要"重写"主题已有的规则。
 */
.diary-list__items {
  list-style: none;
  margin: 0;
  padding: 0;
}

.diary-list__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 10px;
}

.diary-list__plain {
  font-size: 12.5px;
  color: var(--ad-text-secondary);
}

.diary-list__actions {
  display: flex;
  gap: 4px;
  margin-top: 10px;
}

.diary-list__danger {
  color: var(--ad-color-danger);
}

/* ── 骨架屏 ───────────────────────────────────────────────── */
.diary-list__loading {
  background: var(--ad-bg-card);
  border: 1px solid var(--ad-border-color);
  border-radius: 12px;
  padding: 20px 22px;
}

.diary-list__skeleton + .diary-list__skeleton {
  margin-top: 20px;
}

.diary-list__skeleton-line {
  height: 12px;
  border-radius: 999px;
  background: linear-gradient(
    90deg,
    var(--ad-bg-page) 25%,
    var(--ad-border-color) 37%,
    var(--ad-bg-page) 63%
  );
  background-size: 400% 100%;
  animation: skeleton-flow 1.6s ease-in-out infinite;
}

.diary-list__skeleton-line--short {
  width: 28%;
  margin-bottom: 10px;
}

@keyframes skeleton-flow {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}

.diary-list__loading-text {
  margin: 16px 0 0;
  text-align: center;
  font-size: 13px;
  color: var(--ad-text-secondary);
}

/* 尊重"减少动效"偏好（开发文档 §8.6 无障碍要求） */
@media (prefers-reduced-motion: reduce) {
  .diary-list__skeleton-line {
    animation: none;
  }
}

/* ── 错误态 ───────────────────────────────────────────────── */
.diary-list__error {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 12px;
}

.diary-list__error-title {
  margin: 0;
  font-weight: 600;
}

.diary-list__error-msg {
  margin: 2px 0 0;
  font-size: 13px;
}

/* ── 分页 ─────────────────────────────────────────────────── */
.diary-list__pager {
  display: flex;
  justify-content: center;
}

/* ── 窄屏 375px ───────────────────────────────────────────── */
@media (max-width: 480px) {
  .diary-list__title {
    font-size: 20px;
  }

  .diary-list__select {
    width: calc(50% - 4px);
  }

  .diary-list__pager :deep(.el-pagination) {
    flex-wrap: wrap;
    justify-content: center;
    row-gap: 8px;
  }
}
</style>
