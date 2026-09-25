package com.sangshen.aidiary.dto.response;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sangshen.aidiary.entity.DiaryAnalysis;

import java.time.Instant;

/**
 * 日记 AI 分析结果响应。
 *
 * <h2>⚠️ JSON 字段用 {@code @JsonRawValue} 直接嵌出，不做二次转义</h2>
 *
 * <p>数据库里 {@code emotion_json} 存的是一串合法 JSON 文本
 * （MySQL 的 JSON 列已保证这一点）。如果直接当 String 序列化，
 * 前端拿到的会是<b>被转义的字符串</b>：
 * <pre>
 * "emotion_json": "{\"label\":\"平静\"}"     // 字符串，前端还要再 JSON.parse 一次
 * </pre>
 * 而用了 {@code @JsonRawValue} 之后是：
 * <pre>
 * "emotion": {"label":"平静"}                // 直接是对象，前端拿来即用
 * </pre>
 *
 * <p><b>安全性说明</b>：{@code @JsonRawValue} 会把内容原样注入 JSON。
 * 这里安全的前提是"**数据库里的 JSON 列保证内容是合法 JSON**"——
 * 不是合法 JSON 根本插不进去（本次迁移验证时实测过）。
 * 所以不存在"注入出非法结构"的风险。
 *
 * <p>⚠️ 但**不要**把这个注解用在任何未经校验的输入上 ——
 * 那会造成 JSON 注入。本类能安全使用，完全依赖 JSON 列的约束。
 *
 * <h2>字段名对齐</h2>
 *
 * <p>{@code emotion_json} → {@code emotion}（去掉 _json 后缀）。
 * 前端拿到的是"已经是对象/数组的值"，再叫 _json 会让人以为要自己解析。
 * 这是刻意的重命名，已同步到 {@code frontend/src/types/diary.ts}。
 *
 * @param diaryId        日记 ID
 * @param summary        一句话摘要
 * @param emotion        情绪分析（JSON 对象）
 * @param topics         主题列表（JSON 数组）
 * @param entities       实体列表（JSON 数组）
 * @param recentState    近期状态（JSON 数组）
 * @param longTermFacts  可沉淀为长期事实的条目（JSON 数组）
 * @param schemaVersion  分析结果的 schema 版本
 * @param promptVersion  产出这份结果所用的 Prompt 版本（可看出是否 Mock）
 * @param createdAt      分析完成时间（ISO-8601 UTC）
 */
public record DiaryAnalysisResponse(

        @JsonProperty("diary_id")
        Long diaryId,

        String summary,

        @JsonRawValue
        String emotion,

        @JsonRawValue
        String topics,

        @JsonRawValue
        String entities,

        @JsonProperty("recent_state")
        @JsonRawValue
        String recentState,

        @JsonProperty("long_term_facts")
        @JsonRawValue
        String longTermFacts,

        @JsonProperty("schema_version")
        String schemaVersion,

        @JsonProperty("prompt_version")
        String promptVersion,

        @JsonProperty("created_at")
        Instant createdAt
) {

    /**
     * 从实体转换。
     *
     * <p>⚠️ JSON 字段为 null 时输出 {@code null} 而不是 {@code "null"}。
     * {@code @JsonRawValue} 遇到 null 会输出裸的 {@code null} 字面量，
     * 这在 JSON 里是合法的（表示空值），前端判断 {@code == null} 即可。
     *
     * @param entity 非空的分析结果实体
     * @return 响应对象
     * @throws IllegalArgumentException entity 为 null 时抛出
     */
    public static DiaryAnalysisResponse from(DiaryAnalysis entity) {
        if (entity == null) {
            throw new IllegalArgumentException("DiaryAnalysis 不能为 null");
        }
        return new DiaryAnalysisResponse(
                entity.getDiaryId(),
                entity.getSummary(),
                entity.getEmotionJson(),
                entity.getTopicsJson(),
                entity.getEntitiesJson(),
                entity.getRecentStateJson(),
                entity.getLongTermFactsJson(),
                entity.getSchemaVersion(),
                entity.getPromptVersion(),
                UtcTime.toInstant(entity.getCreatedAt()));
    }
}
