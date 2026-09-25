-- ══════════════════════════════════════════════════════════════
-- V2 —— AI 分析结果与异步任务表（diary_analysis / ai_task）
-- ══════════════════════════════════════════════════════════════
-- Phase 3 交付。表结构见开发文档 §7.1，字段级说明见 §6.5 / §6.6。
--
-- ⚠️ 迁移规则（§7.2）：V1 已执行，永不修改。本文件是新增。
--    本脚本必须能在**空库**上从零执行成功（与 V1 一起）。
--
-- ⚠️ 与 AI 侧的分工（《我的职责与任务清单》第 238 行）：
--    这 4 张 AI 相关表由**前后端负责人**写迁移脚本，
--    AI 侧只提字段级约束与索引需求。所以本文件由我维护。
-- ══════════════════════════════════════════════════════════════


-- ──────────────────────────────────────────────────────────────
-- diary_analysis —— 日记的 AI 分析结果
-- ──────────────────────────────────────────────────────────────
-- 一篇日记最多一条分析结果（diary_id 唯一）。
--
-- ⚠️ 为什么 diary_id 要唯一，而不是每次分析插一条：
--    详情页要的是"这篇日记的分析结果"，不是"分析历史"。
--    保留历史会让查询变成"取最新一条"，多一次排序、多一处出错可能，
--    却没有任何产品需求支撑。重新分析走 UPDATE（见 ai_task 的说明）。
--
-- ⚠️ 为什么存 JSON 而不是拆成多张关联表：
--    分析结果是**整体**被消费的（前端一次性渲染摘要+情绪+主题），
--    没有"按某个实体反查日记"的需求。拆表会让写入变成 5 次 INSERT，
--    而收益为零。等真出现"按主题检索"的需求时再加索引列（§7.1 的
--    normalized_key 就是这么设计的）。
--
-- ⚠️ 隐私：summary 等字段是**模型生成的派生数据**，可能包含日记原文片段。
--    它同样属于用户隐私，接口返回前必须校验 user_id（见索引设计）。
-- ══════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS `diary_analysis`
(
    `id`                   BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',

    -- ⚠️ user_id 是冗余的（diary 表已经有），但**刻意保留**：
    --    1. 越权查询不必每次都 JOIN diary —— 权限条件直接写在
    --       本表的 WHERE 里，漏写 user_id 会在 review 时一眼看出来
    --    2. 与 §4.7「所有涉及用户数据的 SQL 必须显式带 user_id」一致
    `user_id`              BIGINT      NOT NULL COMMENT '所属用户（冗余但刻意保留，用于权限过滤）',

    `diary_id`             BIGINT      NOT NULL COMMENT '日记 ID',

    -- ── 分析结果（AI Agent 文档 §5.3 的 schema）──────────────
    `summary`              TEXT        NULL COMMENT '一句话摘要',

    -- ⚠️ 用 JSON 类型而不是 TEXT：
    --    MySQL 8 的 JSON 会做格式校验，写进非法 JSON 直接报错。
    --    用 TEXT 的话，非法 JSON 要等到读取时才发现，而且可能已经在库里躺了很久。
    `emotion_json`         JSON        NULL COMMENT '情绪分析，如 {"primary":"平静","intensity":0.6}',
    `topics_json`          JSON        NULL COMMENT '主题列表，如 ["阅读","散步"]',
    `entities_json`        JSON        NULL COMMENT '实体列表，如 [{"type":"PERSON","name":"阿哲"}]',
    `recent_state_json`    JSON        NULL COMMENT '近期状态（供长期画像聚合用）',
    `long_term_facts_json` JSON        NULL COMMENT '可沉淀为长期事实的条目',

    -- ── 版本追溯（§6.3「便于追溯这次分析用的是哪版 Prompt」）────
    `schema_version`       VARCHAR(20) NULL COMMENT '分析结果 JSON 的 schema 版本',
    `prompt_version`       VARCHAR(20) NULL COMMENT '生成这份结果所用的 Prompt 版本，如 v1',

    `created_at`           DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'UTC',
    `updated_at`           DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'UTC',

    PRIMARY KEY (`id`),

    -- 一篇日记一条结果：既是业务约束，也让"重新分析"必须走 UPDATE
    UNIQUE KEY `uk_diary_analysis_diary` (`diary_id`),

    -- ⚠️ 这个索引是为**权限查询**服务的：
    --    最典型的查询是「查我的某篇日记的分析」→
    --    WHERE user_id = ? AND diary_id = ?
    --    (user_id, diary_id) 让它在索引里就完成过滤，不必回表判断归属。
    KEY `idx_diary_analysis_user` (`user_id`, `diary_id`)

) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='日记 AI 分析结果';


-- ──────────────────────────────────────────────────────────────
-- ai_task —— 异步任务（全新通用的任务表，不只服务 AI）
-- ──────────────────────────────────────────────────────────────
-- ⚠️ 设计目标：**日记保存与模型调用彻底解耦**。
--
--    保存日记时只写 diary + diary_tag，然后发一个事件就返回；
--    模型调用放到事务提交之后的异步 Worker 里。
--    这样「模型超时/报错时正文照样保存成功」这条验收标准才成立
--    （《我的职责与任务清单》模块 3）。
--
--    ⚠️ 绝对不要在保存日记的同一个事务里同步调用模型：
--       模型读取超时是 30 秒，那个事务会一直持有行锁，
--       并发写入会被拖死，而且失败会连带日记一起回滚。
--
-- ⚠️ source_type / source_id 是**弱关联**（没有外键）：
--    任务可能指向日记、记忆、画像等不同来源，无法用一种外键表达。
--    代价是可能出现"来源已删除但任务还在"，这是可接受的 ——
--    Worker 发现来源不存在时把任务标成 CANCELLED 即可（也算补偿机制的一部分）。
-- ══════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS `ai_task`
(
    `id`              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',

    `user_id`         BIGINT      NOT NULL COMMENT '所属用户',

    -- ── 任务指向什么 ──────────────────────────────────────────
    `source_type`     VARCHAR(30) NOT NULL COMMENT '来源类型：DIARY / MEMORY / PROFILE',
    `source_id`       BIGINT      NOT NULL COMMENT '来源 ID（弱关联，无外键）',

    `task_type`       VARCHAR(40) NOT NULL COMMENT '任务类型：DIARY_ANALYZE / MEMORY_EXTRACT / EMBED',

    -- ── 状态机 ────────────────────────────────────────────────
    -- PENDING -> RUNNING -> SUCCESS
    --                    \-> FAILED -> RETRYING -> RUNNING（最多 max-retries 次）
    -- PENDING / RUNNING -> CANCELLED（来源被删除等）
    --
    -- ⚠️ 用 VARCHAR 而不是 MySQL 的 ENUM：
    --    ENUM 加值要 ALTER TABLE（锁表 + 迁移），而状态枚举在开发期
    --    一定会变。VARCHAR + 应用层枚举校验，改起来只动 Java 代码。
    --    代价是数据库不校验取值 —— 所以 Entity 里必须用枚举类型，
    --    由 MyBatis 的 TypeHandler 保证写入的一定是合法值。
    `status`          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING / RUNNING / SUCCESS / FAILED / RETRYING / CANCELLED',

    `retry_count`     INT         NOT NULL DEFAULT 0 COMMENT '已重试次数（首次执行前为 0）',

    -- ── 失败可观察（前端"重试"按钮的依据）────────────────────
    `error_code`      VARCHAR(40) NULL COMMENT '错误码/类别，如 AI_TIMEOUT / AI_HTTP_429 / JSON_INVALID',
    -- ⚠️ 这里只存**可观测的简短原因**，绝不能把模型原始响应或 Prompt 全文写进来
    --    （开发文档 §5.4 红线：禁止 Prompt 全文入日志/库）。
    --    写之前由 AiTaskErrorSanitizer 截断并剔除敏感片段。
    `error_message`   VARCHAR(500) NULL COMMENT '给排查用的简短原因（已脱敏、已截断）',

    -- ── 幂等 ──────────────────────────────────────────────────
    -- 公式（§6.6）：userId + sourceType + sourceId + taskType + version
    -- 唯一索引保证同一个"来源 + 任务类型 + 版本"只会有一条任务记录。
    --
    -- ⚠️ 重试与"重新分析"都**复用同一条记录**（重置状态 + 清空错误），
    --    而不是插新行 —— 否则唯一约束会直接冲突。这样也天然满足幂等：
    --    重复点击"重新分析"不会产生多条任务。
    `idempotency_key` VARCHAR(160) NOT NULL COMMENT '幂等键，唯一',

    -- ⚠️ version 的语义（V2 定义，文档 §7.1 未明确）：
    --    这是**幂等键的版本号**，不是 Prompt 版本。
    --    用途：当幂等键公式本身要变更时（比如加入新维度），
    --    递增 version 即可让新旧键共存，不必手工清理历史数据。
    --    Prompt 版本另有 prompt_version 列（见下方），两者刻意分开 ——
    --    换 Prompt 模板不应该产生"新任务"，而应该是同一条任务的重跑。
    `version`         INT         NOT NULL DEFAULT 1 COMMENT '幂等键版本号（与 prompt_version 无关）',

    `prompt_version`  VARCHAR(20) NULL COMMENT '本次执行所用的 Prompt 版本，便于追溯',

    -- ── 时间 ──────────────────────────────────────────────────
    `started_at`      DATETIME    NULL COMMENT 'UTC，开始执行的时间（RUNNING 时写入）',
    `finished_at`     DATETIME    NULL COMMENT 'UTC，结束时间（SUCCESS/FAILED/CANCELLED 时写入）',
    `created_at`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'UTC',

    -- ⚠️ updated_at 是 V2 相对 §7.1 字段清单的**增补**：
    --    任务状态会多次变化（PENDING -> RUNNING -> FAILED -> RETRYING -> ...），
    --    排查"这个任务卡了多久"时需要最后变更时间。
    --    created_at 只反映入队时刻，不够用。
    `updated_at`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'UTC',

    PRIMARY KEY (`id`),

    -- 幂等：同一来源同一类型同一版本只有一条
    UNIQUE KEY `uk_ai_task_idem` (`idempotency_key`),

    -- ⚠️ (status, created_at) 是**给 Worker 扫待办用的**：
    --    Worker 的查询是「取最早的 N 条 PENDING/RETRYING 任务」→
    --    WHERE status IN (...) ORDER BY created_at LIMIT N
    --    没有这个索引，每轮扫描都是全表扫描 + filesort，
    --    任务表一旦上万行就会明显变慢。
    KEY `idx_ai_task_status_created` (`status`, `created_at`),

    -- ⚠️ 这个索引是**给权限查询和前端轮询用的**：
    --    前端查"我这篇日记的分析任务" → WHERE user_id = ? AND source_type = ? AND source_id = ?
    --    路由是 /api/ai/tasks/{id}，但列表/轮询场景都按来源查。
    KEY `idx_ai_task_user_source` (`user_id`, `source_type`, `source_id`)

) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='异步任务（AI 分析与后续的向量化、记忆提炼共用）';
