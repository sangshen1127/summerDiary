import { request } from './request'
import type { PageResult } from '@/types/api'
import type {
  AiTask,
  Diary,
  DiaryAnalysisDetail,
  DiaryCreateRequest,
  DiaryQuery,
  DiaryUpdateRequest,
  Tag,
  TagCreateRequest,
} from '@/types/diary'

// ══════════════════════════════════════════════════════════════
// 日记与标签接口封装
// ══════════════════════════════════════════════════════════════
// 契约见开发文档 §4.6「日记与标签」段落，共 10 个接口：
//   日记 5 个 + 标签 3 个 + AI 分析 2 个（Phase 3 新增）。
//
// ⚠️ 用户名/身份**不在这里传** —— 后端从 HttpOnly Cookie 的会话里取
// （开发文档 §4.7 权限铁律）。本文件里没有任何 userId 参数，
// 前端也无法伪造身份。
//
// ⚠️ request 拦截器已经把统一响应体拆开了：
//   - 成功 → 直接 resolve `data` 字段，所以这里的返回类型是业务数据本身
//   - 失败 → reject 一个 AppError { code, message, isAuthError, isAiError }
//   所以调用方 catch 到的东西一定有成对的 code + message，可以直接展示。
// ══════════════════════════════════════════════════════════════

// ──────────────────────────────────────────────────────────────
// 日记
// ──────────────────────────────────────────────────────────────

export const diaryApi = {
  /**
   * 创建日记。
   *
   * 成功返回完整的日记（含**明文正文**与标签）。
   */
  create(body: DiaryCreateRequest): Promise<Diary> {
    return request.post('/diaries', body) as Promise<Diary>
  },

  /**
   * 分页查询日记列表。
   *
   * ⚠️⚠️ 返回的每一条 `content` 都是 **null** ——
   * 列表刻意不解密正文（安全与性能考虑，见 types/diary.ts 的说明）。
   * 需要正文请调 {@link detail}。
   *
   * ⚠️ `keyword` **只搜标题**，搜不到正文内容。
   */
  list(query: DiaryQuery = {}): Promise<PageResult<Diary>> {
    return request.get('/diaries', { params: toQueryParams(query) }) as Promise<PageResult<Diary>>
  },

  /** 查询详情。返回的 `content` 是**已解密的明文全文**。 */
  detail(id: number): Promise<Diary> {
    return request.get(`/diaries/${id}`) as Promise<Diary>
  },

  /**
   * 修改日记。
   *
   * ⚠️ PUT 是**整体替换**语义：没传的可选字段会被清空、标签会被清空。
   * 调用方必须提交完整表单（见 types/diary.ts 的 `DiaryUpdateRequest`）。
   */
  update(id: number, body: DiaryUpdateRequest): Promise<Diary> {
    return request.put(`/diaries/${id}`, body) as Promise<Diary>
  },

  /** 删除日记（后端是**软删除**：`deleted = 1`）。 */
  remove(id: number): Promise<void> {
    return request.delete(`/diaries/${id}`) as Promise<void>
  },
}

// ──────────────────────────────────────────────────────────────
// AI 分析（Phase 3）
// ──────────────────────────────────────────────────────────────

export const aiApi = {
  /**
   * 查询某篇日记的 AI 分析「状态 + 结果」。
   *
   * ⚠️ 注意路径：这个接口在 `/diaries/{id}/analysis` 下（属于日记资源），
   * 而不是 `/ai/...`。后端对应的是 `DiaryAnalysisController`。
   *
   * ## 三种返回情况都不算失败（永远 200）
   * ```
   * data.status === null                 → 还没有任务（尚未分析）
   * data.status === 'pending' | 'running'→ 在排队/执行中，应当继续轮询
   * data.status === 'success'            → data.analysis 有内容
   * data.status === 'failed' | 'cancelled' → data.error_message 有原因
   * ```
   *
   * ⚠️ **不要**因为"还没分析好"就当成错误处理。后端刻意把状态放在
   * `data` 里而不是用 4xx/5xx，就是为了让前端能区分
   * 「正常的等待」与「真的失败了」。
   *
   * @param id 日记 ID
   */
  analysis(id: number): Promise<DiaryAnalysisDetail> {
    return request.get(`/diaries/${id}/analysis`) as Promise<DiaryAnalysisDetail>
  },

  /**
   * 请求（重新）分析某篇日记。
   *
   * ⚠️⚠️ **这是"排队"，不是"执行"**：返回时任务通常还是 `pending`，
   * 真正的分析由后端 Worker 异步完成。所以调用方**不能**期待它返回
   * `success`，正确用法是拿到返回后开始轮询 {@link analysis}。
   *
   * 三种结果都不算失败（后端设计成幂等）：
   *   - 本来在跑 → 什么都不做，返回当前状态（`can_retry=false`）
   *   - 已成功过 → 重置重新分析（会覆盖旧结果）
   *   - 上次失败 → 重置重试，`retry_count` 归零
   *
   * 失败时抛的 AppError：
   *   - `40401` → 日记不存在或不属于当前用户
   *   - `50011` → 服务端 AI 能力未开启（**不是**用户输入的问题）
   */
  analyzeDiary(id: number): Promise<AiTask> {
    return request.post(`/ai/diaries/${id}/analyze`) as Promise<AiTask>
  },
}

// ──────────────────────────────────────────────────────────────
// 标签
// ──────────────────────────────────────────────────────────────

export const tagApi = {
  /** 查询当前用户的全部标签（不分页，按创建时间正序）。 */
  list(): Promise<Tag[]> {
    return request.get('/tags') as Promise<Tag[]>
  },

  /**
   * 创建标签，**若同名已存在则返回已有的那个**（幂等，不报 409）。
   *
   * 所以前端不需要"先查一遍再决定创建还是选择" ——
   * 直接调它拿返回的 id 用即可。少一次往返，也少一处竞态。
   */
  create(body: TagCreateRequest): Promise<Tag> {
    return request.post('/tags', body) as Promise<Tag>
  },

  /**
   * 删除标签。仅允许删除**未被任何未删除日记使用**的标签。
   *
   * 失败时抛的 AppError 里：
   *   - code `40401` → 标签不存在或不属于当前用户
   *   - code `40901` → 标签正在被 N 篇日记使用（message 里带篇数）
   */
  remove(id: number): Promise<void> {
    return request.delete(`/tags/${id}`) as Promise<void>
  },
}

// ══════════════════════════════════════════════════════════════
// 查询参数映射
// ══════════════════════════════════════════════════════════════

/**
 * 把 {@link DiaryQuery} 转成后端实际接受的 query 参数名。
 *
 * ## ⚠️ 为什么必须有这个函数，不能直接把对象丢给 axios
 *
 * 后端接受的查询参数是 **snake_case** 的 `tag_id`（开发文档 §4.6），
 * 而 TS 接口里写的是 camelCase 的 `tagId`。如果直接
 * `request.get('/diaries', { params: query })`，axios 会原样发出
 * `?tagId=3` —— 后端的 `@RequestParam(name = "tag_id")` **收不到**，
 * 于是**筛选静默失效**：不报错，返回的是没筛选的完整列表。
 *
 * 这正是后端在模块 2-3 真实踩过的 bug（开发文档 §11.3.5，
 * Spring 的 `@ModelAttribute` 绑不上 snake_case 参数）。
 * 前端这边有**同一个坑的另一半**：参数名从这一侧发错，同样静默失效。
 *
 * ## 为什么不干脆把 TS 字段也写成 tag_id
 *
 * 因为 TS 字段名在组件里到处用（`query.tagId`），
 * 用 snake_case 会和其他字段（page/size/keyword/mood）的命名风格割裂。
 * 集中在一个函数里做映射，好处是**只有这一处需要和后端对参数名**，
 * 测试和排查都只看这一个地方。
 *
 * 其余参数（page/size/keyword/from/to/mood）前后端同名，直接透传。
 *
 * @param query 组件里用的查询对象
 * @returns 可直接交给 axios params 的对象
 */
function toQueryParams(query: DiaryQuery): Record<string, string | number> {
  const params: Record<string, string | number> = {}

  // 同名参数直接透传（值为 undefined 时 axios 会自动忽略该参数）
  if (query.page !== undefined) params.page = query.page
  if (query.size !== undefined) params.size = query.size
  if (query.keyword) params.keyword = query.keyword
  if (query.from) params.from = query.from
  if (query.to) params.to = query.to
  if (query.mood) params.mood = query.mood

  // ⚠️ 唯一一个需要改名的参数：tagId → tag_id
  if (query.tagId !== undefined) params.tag_id = query.tagId

  return params
}
