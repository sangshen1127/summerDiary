package com.sangshen.aidiary.entity.enums;

/**
 * 任务类型与来源类型。
 *
 * <h2>为什么这两个枚举放在一个文件里</h2>
 *
 * <p>它们是一对：{@link SourceType} 说明"任务作用于什么"，
 * {@link TaskType} 说明"要对它做什么"，两者组合才有意义
 * （{@code DIARY + DIARY_ANALYZE} 是合法的，
 * {@code MEMORY + DIARY_ANALYZE} 是荒谬的）。
 *
 * <p>放在一起便于对照阅读，也提醒后来者：**新增任务类型时要同时想清楚
 * 它作用于哪种来源**。它们各自是独立类型，不影响 MyBatis 映射。
 */
public final class AiTaskTypes {

    private AiTaskTypes() {
        // 纯容器类，禁止实例化
    }

    /**
     * 任务来源类型 —— 任务作用于什么。
     */
    public enum SourceType {

        /** 日记。Phase 3 唯一会用到的来源类型。 */
        DIARY,

        /** 记忆条目。Phase 6 用（记忆的向量化、聚合）。 */
        MEMORY,

        /** 用户长期画像。Phase 6 用。 */
        PROFILE
    }

    /**
     * 任务类型 —— 要对来源做什么。
     *
     * <p>⚠️ Phase 3 <b>只实现</b> {@link #DIARY_ANALYZE}。
     * 其他两个枚举值先定义出来，是为了让表结构和幂等键公式一次定好 ——
     * 这样 Phase 5/6 加任务类型时不需要改表、不需要改索引。
     * 但**不要提前写它们的 Worker 分支**（那是 Phase 5/6 的事）。
     */
    public enum TaskType {

        /**
         * 日记认知分析：日记 → 摘要 / 情绪 / 主题 / 实体。
         *
         * <p>由 {@code DiaryCreatedEvent} 触发，产出写进 {@code diary_analysis}。
         */
        DIARY_ANALYZE,

        /**
         * 记忆提炼：日记分析结果 → 三层记忆的增删改。
         *
         * <p>Phase 6 实现。依赖 DIARY_ANALYZE 的结果，
         * 所以将来它应该由"分析成功"事件触发，而不是和它并行跑
         * —— 否则记忆提炼会读到还没生成的分析结果。
         */
        MEMORY_EXTRACT,

        /**
         * 向量化：把内容切片后写入向量库。
         *
         * <p>Phase 5 实现。
         */
        EMBED
    }
}
