/**
 * 日记与标签相关类型。
 *
 * ⚠️ 必须与后端 DTO 字段**严格一一对应**，字段名一个字都不能改。
 *
 * 命名约定（开发文档 §4.5）：后端**没有**配全局 snake_case，
 * 而是靠 DTO 上显式写 `@JsonProperty` 来对齐。所以这里的字段名
 * 是"照抄后端 DTO 的 JSON 名"，不是"按某种规则推导出来的"。
 *
 * 后端对应文件：
 *   dto/response/DiaryResponse.java
 *   dto/response/TagResponse.java
 *   dto/request/DiaryCreateRequest.java
 *   dto/request/DiaryUpdateRequest.java
 *   dto/request/TagCreateRequest.java
 *   dto/response/PageResponse.java
 *   dto/query/DiaryQuery.java
 */

// ══════════════════════════════════════════════════════════════
// 标签
// ══════════════════════════════════════════════════════════════

/** 标签（GET /api/tags 的元素、日记里内嵌的 tags 元素） */
export interface Tag {
  id: number
  name: string
  /** ISO-8601 UTC，末尾带 Z */
  created_at: string
}

/** 创建标签请求体（POST /api/tags） */
export interface TagCreateRequest {
  name: string
}

// ══════════════════════════════════════════════════════════════
// 日记
// ══════════════════════════════════════════════════════════════

/**
 * 日记（详情 / 创建 / 修改接口的返回，也是列表 items 的元素）。
 *
 * ⚠️⚠️ `content` 在不同接口下含义不同，这是**最容易踩的坑**：
 *
 * | 接口 | content 的值 |
 * | --- | --- |
 * | `GET /api/diaries/{id}`（详情） | **正文明文全文** |
 * | `POST /api/diaries`（创建） | 正文明文全文 |
 * | `PUT /api/diaries/{id}`（修改） | 正文明文全文 |
 * | `GET /api/diaries`（列表） | **恒为 `null`** |
 *
 * 列表为什么是 null：列表每页最多 100 条，若全部解密并传输，
 * 明文正文会同时出现在网络、浏览器内存和中间日志里，成倍扩大暴露面；
 * 而列表本来就只显示标题和摘要。详见后端 `DiaryServiceImpl` 的类注释。
 *
 * ### 由此推出的一条硬约束
 *
 * **编辑页必须先调详情接口拿到 content，再允许提交。**
 * 如果只拿列表数据（content 为 null）就提交，`content` 会以空值
 * 覆盖掉用户原来的正文 —— 那是**静默数据丢失**，比报错糟糕得多。
 * `DiaryEditorPage` 里对此有明确处理。
 */
export interface Diary {
  id: number
  title: string
  /** 正文明文。⚠️ 列表接口返回 null，见上方说明 */
  content: string | null
  mood: string | null
  weather: string | null
  location: string | null
  /** 标签列表，**永远不是 null**（无标签时是空数组） */
  tags: Tag[]
  /**
   * AI 分析任务状态。
   *
   * 取值（Phase 3 起有真实值）：
   * ```
   * null         这篇日记没有分析任务（AI 关着时创建的、或 Phase 3 之前的老数据）
   * 'pending'    排队中或等待重试
   * 'running'    正在分析
   * 'success'    分析完成 —— 结果用 GET /api/diaries/{id}/analysis 取
   * 'failed'     分析失败
   * 'cancelled'  已取消（日记被删过）
   * ```
   *
   * ⚠️ 类型写成 `string | null` 而**不是**字面量联合
   * （如 `'pending' | 'success'`）：取值集合将来只增不减，
   * 写死会让"后端新增一个状态"变成前端 type error 甚至运行时崩溃。
   * 需要判定时用字符串比较 + 兜底分支。
   *
   * ⚠️ 本字段**不承担「AI 是否启用」的语义** —— 那个看
   * `CurrentUser.ai_enabled`（用户偏好）与
   * `DiaryAnalysisDetail.enabled`（服务端能力）。
   * `null` 有三种可能的原因，光看它区分不出来。
   *
   * ⚠️ 列表接口返回的每一条本字段都是 `null`（列表不做 N+1 查询），
   * 详情接口才有真实值。
   */
  analysis_status: string | null
  /** ISO-8601 UTC，末尾带 Z。用 formatTime.ts 格式化，禁止自己 new Date() */
  created_at: string
  updated_at: string
}

/** 创建日记请求体（POST /api/diaries） */
export interface DiaryCreateRequest {
  title: string
  /** 正文明文。允许空串（只记心情不写正文），但字段必须传 */
  content: string
  mood?: string | null
  weather?: string | null
  location?: string | null
  /** 标签 ID 列表。必须全部属于当前用户，否则整个请求 40401 */
  tag_ids?: number[] | null
}

/**
 * 修改日记请求体（PUT /api/diaries/{id}）。
 *
 * ⚠️ PUT 是**整体替换**语义：没传的可选字段会被清空为 null、标签会被清空。
 * 所以**必须提交完整表单**，不能只传改动的字段。
 * 想只改一个字段应该用 PATCH（本项目未提供）。
 */
export interface DiaryUpdateRequest {
  title: string
  content: string
  mood?: string | null
  weather?: string | null
  location?: string | null
  tag_ids?: number[] | null
}

/**
 * 日记列表筛选参数（GET /api/diaries 的 query）。
 *
 * ⚠️ 注意 `tagId` 对应的是**查询参数 `tag_id`**（snake_case）。
 * 后端 Controller 用的是显式 `@RequestParam(name = "tag_id")` ——
 * 曾经因为用 `@ModelAttribute` 导致这个参数**静默失效**（开发文档 §11.3.5）。
 */
export interface DiaryQuery {
  /** 页码，从 0 开始 */
  page?: number
  /** 每页条数，默认 20，**上限 100**（超出返回 40001） */
  size?: number
  /** ⚠️ 关键词**只搜标题**。正文是随机 IV 的 AES-GCM 密文，SQL 无法 LIKE */
  keyword?: string
  /** 时间范围起点（闭区间），ISO-8601，如 2026-09-01T00:00:00 */
  from?: string
  /** 时间范围终点（闭区间） */
  to?: string
  /** 心情精确匹配 */
  mood?: string
  /** 标签筛选。⚠️ 对外参数名是 tag_id，见上方说明 */
  tagId?: number
}

// ══════════════════════════════════════════════════════════════
// AI 分析结果（Phase 3）
// ══════════════════════════════════════════════════════════════
//
// 后端对应文件：
//   dto/response/DiaryAnalysisDetailResponse.java
//   dto/response/DiaryAnalysisResponse.java
//   dto/response/AiTaskResponse.java
//
// ⚠️⚠️ 下面这些结构来自**模型的 JSON 输出**（AI Agent 文档 §5.3），
// 不是我们能完全控制的：
//   - 后端做了一次校验（不合法就不落库），但校验规则可能放宽
//   - 将来换 Prompt / 换模型，"字段名一样但内容形状变了"是很常见的
//
// 所以：**所有字段都标成可选**，渲染前必须判类型（见 DetailPage 里的
// 取值辅助函数）。直接把 emotion.label 塞进模板，一旦模型这次没返回
// label，页面就是一片 `undefined` 或者干脆抛错白屏 —— 而这属于
// "后端返回了 200 但前端炸了"，最难排查的一类问题。
// ══════════════════════════════════════════════════════════════

/** 情绪分析（对齐 AI Agent 文档 §5.3 的 `emotion`） */
export interface AnalysisEmotion {
  /** 情绪标签，如「平静」「焦虑」 */
  label?: string
  /** 强度，0.0 ~ 1.0 */
  score?: number
  /** 判断依据（模型给的一句话） */
  evidence?: string
}

/** 实体（`entities` 的元素） */
export interface AnalysisEntity {
  /** 类型，如 person / project / place */
  type?: string
  name?: string
  confidence?: number
}

/** 近期状态（`recent_state` 的元素）—— 默认只进短期记忆 */
export interface AnalysisRecentState {
  content?: string
  importance?: number
  confidence?: number
  /** 多少天后过期 */
  expires_in_days?: number
}

/** 可沉淀为长期事实的条目（`long_term_facts` 的元素） */
export interface AnalysisLongTermFact {
  /** 分类，如 interest / goal */
  category?: string
  content?: string
  importance?: number
  confidence?: number
  evidence?: string
}

/**
 * 一份分析结果（`GET /api/diaries/{id}/analysis` 的 `data.analysis`）。
 *
 * ⚠️ 后端把数据库里的 JSON 列**原样嵌进响应**（`@JsonRawValue`），
 * 所以 `emotion` 在这里已经是**对象**、`topics` 已经是**数组**，
 * 不需要再 `JSON.parse` 一次。
 */
export interface DiaryAnalysis {
  diary_id: number
  /** 一句话摘要，不超过 120 字 */
  summary: string | null
  emotion: AnalysisEmotion | null
  topics: string[] | null
  entities: AnalysisEntity[] | null
  recent_state: AnalysisRecentState[] | null
  long_term_facts: AnalysisLongTermFact[] | null
  /** 分析结果的 schema 版本 */
  schema_version: string | null
  /** 产出这份结果的 Prompt 版本。看到 `mock-` 开头说明是 Mock 产出的 */
  prompt_version: string | null
  /** ISO-8601 UTC，末尾带 Z */
  created_at: string | null
}

/**
 * 分析「状态 + 结果」的完整快照。
 *
 * ⚠️ 这是**一次请求拿到的同一份快照**，所以 `status` 与 `analysis`
 * 不会自相矛盾（分成两个接口就会）。
 *
 * 字段可用性：
 * ```
 * status = null                 → can_retry=true， analysis=null
 * status = 'pending'/'running'  → can_retry=false，analysis 可能仍保留着上一次的结果
 * status = 'success'            → analysis 非 null
 * status = 'failed'/'cancelled' → error_* 非 null，analysis=null
 * ```
 */
export interface DiaryAnalysisDetail {
  diary_id: number
  /**
   * **服务端**是否具备 AI 分析能力（全局开关 `AI_ANALYSIS_ENABLED`）。
   *
   * ⚠️ 与 `CurrentUser.ai_enabled`（**用户**是否想用）是两件事，都要看：
   *   - `enabled === false` → 显示「AI 功能暂未开放」，**不给**分析按钮
   *   - `user.ai_enabled === false` → 显示「你已关闭 AI 分析」
   */
  enabled: boolean
  /** 取值见 `Diary.analysis_status`。`null` = 还没有任务 */
  status: string | null
  /**
   * 现在能不能调 `POST /api/ai/diaries/{id}/analyze`。
   *
   * ⚠️ 由**后端**算好（= enabled 且任务状态允许重置）。
   * 前端不要自己复刻这套判断规则 —— 它依赖后端的任务状态机，
   * 复刻一份早晚会不一致，表现就是"按钮亮着但点了没反应"。
   */
  can_retry: boolean
  retry_count: number
  max_retries: number
  /** 错误类别码，如 `AI_TIMEOUT` / `AI_HTTP_429` / `JSON_INVALID` */
  error_code: string | null
  /** 已脱敏的错误原因，可直接展示给用户 */
  error_message: string | null
  analysis: DiaryAnalysis | null
}

/** AI 任务状态（`GET /api/ai/tasks/{id}`、`POST /api/ai/diaries/{id}/analyze`） */
export interface AiTask {
  id: number
  /** 取值见 `Diary.analysis_status` */
  status: string
  retry_count: number
  max_retries: number
  can_retry: boolean
  error_code: string | null
  error_message: string | null
  started_at: string | null
  finished_at: string | null
  created_at: string
}
