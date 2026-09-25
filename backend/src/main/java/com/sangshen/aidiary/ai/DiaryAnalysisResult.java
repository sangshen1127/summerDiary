package com.sangshen.aidiary.ai;

/**
 * 日记认知分析结果 —— {@link CognitionService#analyze} 的返回值。
 *
 * <h2>⚠️ 这个类的位置为什么在 {@code ai} 包而不是 {@code dto} 或 {@code entity}</h2>
 *
 * <p>它是**领域契约**的载体：AI 侧产出它，我的框架消费它。
 * <ul>
 *   <li>放 {@code entity} —— 它不对应任何表（{@code DiaryAnalysis} 实体才是），
 *       而且 entity 层不该被 AI 侧直接构造</li>
 *   <li>放 {@code dto} —— 它<b>不是</b> Controller 的出入参。Controller 返回的是
 *       另一个 DTO（带 diaryId 等上下文字段），由我转换</li>
 * </ul>
 *
 * <p>单独放一个 {@code ai} 包，语义最清楚：**这里是和 AI 模块的接缝**。
 * 将来 {@code AiClient} / {@code VectorStore} 等接口也放这里。
 *
 * <h2>⚠️ JSON 字段用 String，不拆成结构化对象</h2>
 *
 * <p>{@link #emotionJson} / {@link #topicsJson} 等的 schema
 * <b>归 AI 侧定义</b>（AI Agent 文档 §5.3）。我这边拆成 Java 对象，
 * 等于把"字段长什么样"的决策权挪过来了 —— 将来 AI 侧改 schema
 * 就要改我的类，而两边的修改节奏不同。
 *
 * <p>代价：我无法在编译期校验内容。这是刻意的取舍，
 * 校验归 {@link #schemaVersion} 负责。
 *
 * @param schemaVersion  分析结果 JSON 的 schema 版本，如 "1.0"
 * @param summary        一句话摘要。可为 null（模型没产出摘要）
 * @param emotionJson    情绪分析 JSON，如 {"label":"平静","score":0.5}
 * @param topicsJson     主题列表 JSON，如 ["阅读","散步"]
 * @param entitiesJson   实体列表 JSON
 * @param recentStateJson 近期状态 JSON（供长期画像聚合）
 * @param longTermFactsJson 可沉淀为长期事实的条目 JSON
 * @param promptVersion  产出这份结果所用的 Prompt 版本（Worker 会写进任务表）
 */
public record DiaryAnalysisResult(
        String schemaVersion,
        String summary,
        String emotionJson,
        String topicsJson,
        String entitiesJson,
        String recentStateJson,
        String longTermFactsJson,
        String promptVersion
) {

    /**
     * 安全描述 —— 摘要与各 JSON 都是**模型基于日记正文生成的派生数据**，
     * 可能包含原文片段，不得进日志。
     */
    public String toSafeString() {
        return "DiaryAnalysisResult{schemaVersion=" + schemaVersion
                + ", summary=" + (summary == null ? "null" : "(长度 " + summary.length() + ")")
                + ", promptVersion=" + promptVersion
                + '}';
    }
}
