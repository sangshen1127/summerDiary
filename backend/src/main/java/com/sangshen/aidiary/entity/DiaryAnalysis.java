package com.sangshen.aidiary.entity;

import java.time.LocalDateTime;

/**
 * 日记的 AI 分析结果实体，对应 {@code diary_analysis} 表。
 *
 * <p>一篇日记最多一条（{@code diary_id} 唯一）。重新分析走 UPDATE。
 *
 * <h2>⚠️ JSON 字段为什么用 String 而不是结构化对象</h2>
 *
 * <p>{@link #emotionJson} / {@link #topicsJson} 等对应 MySQL 的
 * {@code JSON} 列。这里刻意用 {@link String} 承接，理由：
 *
 * <ol>
 *   <li><b>分析结果的 schema 归 AI 侧定义</b>（《我的职责与任务清单》§7）。
 *       后端把它们拆成 Java 对象，等于把"字段长什么样"的决策权
 *       挪到了我这边，将来 AI 侧改 schema 就要改我的实体。</li>
 *   <li><b>数据库已经保证它是合法 JSON</b>（{@code JSON} 列会校验），
 *       所以"存进去的是不是合法 JSON"不需要 Java 层再管一次。</li>
 *   <li>需要给前端时，直接把这串 JSON 原样嵌进响应即可 ——
 *       用 Jackson 的 {@code @JsonRawValue} 避免二次转义。</li>
 * </ol>
 *
 * <p>代价：Java 层无法对内容做类型校验。这是<b>刻意的取舍</b> ——
 * 校验归 AI 侧的 schema 版本（{@link #schemaVersion}）负责。
 *
 * <h2>⚠️ JDBC 读 JSON 列的类型</h2>
 *
 * <p>MySQL Connector/J 读 {@code JSON} 列时返回 {@code String}，
 * 所以这里用 {@link String} 是安全的。但**这一点是驱动行为、不是 SQL 标准**，
 * 所以 XML 里显式声明了 {@code resultType} / jdbcType，
 * 避免换驱动版本后出现"字段为 null"这种静默失败。
 */
public class DiaryAnalysis {

    private Long id;

    /** 所属用户。冗余字段，但**刻意保留** —— 权限条件直接写在本表 WHERE 里 */
    private Long userId;

    /** 日记 ID。唯一，一篇日记一条分析 */
    private Long diaryId;

    /** 一句话摘要 */
    private String summary;

    /** 情绪分析 JSON，如 {"primary":"平静","intensity":0.6} */
    private String emotionJson;

    /** 主题列表 JSON，如 ["阅读","散步"] */
    private String topicsJson;

    /** 实体列表 JSON，如 [{"type":"PERSON","name":"阿哲"}] */
    private String entitiesJson;

    /** 近期状态 JSON（供长期画像聚合） */
    private String recentStateJson;

    /** 可沉淀为长期事实的条目 JSON */
    private String longTermFactsJson;

    /** 分析结果 JSON 的 schema 版本 */
    private String schemaVersion;

    /** 生成这份结果所用的 Prompt 版本，如 v1 */
    private String promptVersion;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // ── getter / setter ────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getDiaryId() {
        return diaryId;
    }

    public void setDiaryId(Long diaryId) {
        this.diaryId = diaryId;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getEmotionJson() {
        return emotionJson;
    }

    public void setEmotionJson(String emotionJson) {
        this.emotionJson = emotionJson;
    }

    public String getTopicsJson() {
        return topicsJson;
    }

    public void setTopicsJson(String topicsJson) {
        this.topicsJson = topicsJson;
    }

    public String getEntitiesJson() {
        return entitiesJson;
    }

    public void setEntitiesJson(String entitiesJson) {
        this.entitiesJson = entitiesJson;
    }

    public String getRecentStateJson() {
        return recentStateJson;
    }

    public void setRecentStateJson(String recentStateJson) {
        this.recentStateJson = recentStateJson;
    }

    public String getLongTermFactsJson() {
        return longTermFactsJson;
    }

    public void setLongTermFactsJson(String longTermFactsJson) {
        this.longTermFactsJson = longTermFactsJson;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * ⚠️ 刻意不重写 {@code toString()}。
     *
     * <p>{@link #summary} 与各 JSON 字段都是**模型基于日记正文生成的派生数据**，
     * 很可能包含原文片段。开发文档 §5.4 把日记正文列为红线，
     * 派生的摘要同样属于用户隐私，不该出现在日志里。
     */
    public String toSafeString() {
        return "DiaryAnalysis{id=" + id
                + ", userId=" + userId
                + ", diaryId=" + diaryId
                + ", summary=" + (summary == null ? "null" : "(长度 " + summary.length() + ")")
                + ", schemaVersion=" + schemaVersion
                + ", promptVersion=" + promptVersion
                + '}';
    }
}
