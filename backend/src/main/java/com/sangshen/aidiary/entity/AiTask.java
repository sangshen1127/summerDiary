package com.sangshen.aidiary.entity;

import com.sangshen.aidiary.entity.enums.AiTaskStatus;
import com.sangshen.aidiary.entity.enums.AiTaskTypes.SourceType;
import com.sangshen.aidiary.entity.enums.AiTaskTypes.TaskType;

import java.time.LocalDateTime;

/**
 * 异步任务实体，对应 {@code ai_task} 表。
 *
 * <h2>⚠️ 这张表存在的唯一理由：把"保存日记"和"调用模型"解耦</h2>
 *
 * <p>验收标准是「模型超时/报错时<b>正文照样保存成功</b>」。
 * 要做到这一点，保存日记的事务里<b>只能</b>写 diary / diary_tag，
 * 然后发个事件就返回；模型调用放到事务提交之后的异步 Worker。
 *
 * <p>所以 {@code ai_task} 是"待办清单"：日记存成功后往里放一条，
 * Worker 按 {@link AiTaskStatus#PENDING} 捞出来执行。
 * 模型挂了只会让任务变成 FAILED，日记已经在库里了。
 *
 * <h2>⚠️ 这个类绝不能直接返回给前端</h2>
 *
 * <p>含 {@link #errorMessage}（虽然已脱敏，仍是内部排查信息）。
 * Controller 必须返回 DTO。
 *
 * <h2>隐私红线（开发文档 §5.4）</h2>
 *
 * <p>{@link #errorMessage} 里<b>绝不能</b>出现：日记正文、Prompt 全文、
 * 模型完整响应、API Key。写入前必须经过截断与过滤
 * （见 {@code AiTaskErrorSanitizer}）。
 */
public class AiTask {

    /**
     * 幂等键公式的版本号。
     *
     * <p>⚠️ 与 {@link #promptVersion} 不是一个东西：公式变更时才递增，
     * 让新旧键共存，不必清理历史数据（详见 {@link #version} 的字段注释）。
     *
     * <p>放在实体上而不是各个调用方里：入队路径有两个
     * （日记创建后的自动入队、用户点「重新分析」），
     * 两边必须算出**完全相同**的键，否则幂等就失效了 ——
     * 症状是同一篇日记出现两条任务，然后被 Worker 重复分析。
     */
    public static final int IDEMPOTENCY_KEY_VERSION = 1;

    private Long id;

    /** 所属用户。所有查询都必须带它做隔离 */
    private Long userId;

    /** 来源类型（Phase 3 只用到 DIARY） */
    private SourceType sourceType;

    /** 来源 ID。弱关联，没有外键 —— 来源可能是日记/记忆/画像 */
    private Long sourceId;

    /** 任务类型（Phase 3 只实现 DIARY_ANALYZE） */
    private TaskType taskType;

    /** 当前状态。流转合法性由 {@link AiTaskStatus#canTransitionTo} 守护 */
    private AiTaskStatus status;

    /** 已重试次数。首次执行前为 0 */
    private int retryCount;

    /** 错误类别码，如 AI_TIMEOUT / AI_HTTP_429 / JSON_INVALID */
    private String errorCode;

    /** 给排查用的简短原因（已脱敏、已截断，最长 500 字符） */
    private String errorMessage;

    /** 幂等键：userId + sourceType + sourceId + taskType + version */
    private String idempotencyKey;

    /**
     * 幂等键版本号。
     *
     * <p>⚠️ 与 {@link #promptVersion} <b>不是一个东西</b>：
     * <ul>
     *   <li>{@code version} —— 幂等键公式的版本。公式变了才递增，
     *       让新旧键共存，不必清理历史数据</li>
     *   <li>{@code promptVersion} —— 这次执行用的是哪版 Prompt，
     *       仅用于追溯</li>
     * </ul>
     * 换 Prompt 模板不应该产生"新任务"，而应该是同一条任务的重跑 ——
     * 这就是两者刻意分开的原因。
     */
    private int version;

    /** 本次执行所用的 Prompt 版本 */
    private String promptVersion;

    /** 开始执行时间（UTC） */
    private LocalDateTime startedAt;

    /** 结束时间（UTC） */
    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // ── 工厂方法 ───────────────────────────────────────────────

    /**
     * 造一条全新的待执行任务（{@link AiTaskStatus#PENDING}）。
     *
     * <h2>为什么把"新建任务"收进实体，而不是各调用方自己 set</h2>
     *
     * <p>入队有两个入口：日记创建后的自动入队、用户点「重新分析」时的补入队。
     * 两边要填的字段（状态、重试次数、幂等键、版本号）<b>必须完全一致</b>。
     * 如果各写一遍，早晚会漏掉一个 —— 比如某处忘了设 {@code idempotencyKey}，
     * 结果是插入时撞 {@code NOT NULL} 报错（好一点），
     * 或者某处把 version 写成默认值 0，导致同一个来源算出两个不同的键
     * （坏一点：<b>同一篇日记出现两条任务，被 Worker 重复分析、重复计费</b>）。
     *
     * <p>顺带说明为什么这里<b>不</b>设置 {@code id} / {@code createdAt} /
     * {@code updatedAt}：它们由数据库生成（自增、
     * {@code DEFAULT CURRENT_TIMESTAMP}）。Java 侧写时间会引入
     * "应用服务器与数据库时钟不一致"的隐患 —— 项目里时间只有一个来源。
     *
     * @param userId        所属用户
     * @param sourceType    来源类型
     * @param sourceId      来源 ID
     * @param taskType      任务类型
     * @param promptVersion 本次执行用的 Prompt 版本
     * @return 可直接交给 {@code insertIgnoreDuplicate} 的任务对象
     * @throws IllegalArgumentException 任一 ID 为 null（属于编码错误，早失败早发现）
     */
    public static AiTask newPending(Long userId, SourceType sourceType,
                                    Long sourceId, TaskType taskType,
                                    String promptVersion) {
        if (userId == null || sourceId == null || sourceType == null || taskType == null) {
            throw new IllegalArgumentException(
                    "构造待执行任务时 userId / sourceType / sourceId / taskType 都不能为 null");
        }
        AiTask task = new AiTask();
        task.setUserId(userId);
        task.setSourceType(sourceType);
        task.setSourceId(sourceId);
        task.setTaskType(taskType);
        task.setStatus(AiTaskStatus.PENDING);
        task.setRetryCount(0);
        task.setVersion(IDEMPOTENCY_KEY_VERSION);
        task.setPromptVersion(promptVersion);
        task.setIdempotencyKey(buildIdempotencyKey(
                userId, sourceType, sourceId, taskType, IDEMPOTENCY_KEY_VERSION));
        return task;
    }

    /**
     * 构造幂等键。
     *
     * <p>格式：{@code sourceType:sourceId:taskType:userId:version}
     *
     * <p>⚠️ 列的 VARCHAR(160) 足够：最长的组合约
     * {@code DIARY:9223372036854775807:DIARY_ANALYZE:9223372036854775807:1} ≈ 60 字符。
     *
     * <p>为什么用冒号分隔而不是拼接成一串数字：<b>可读性</b>。
     * 排查时直接在数据库里看这一列就能明白它是哪个用户哪篇日记的任务，
     * 不需要反查其他表。幂等键的价值一半在于"能被人看懂"。
     *
     * <p>为什么把 userId 放进去：不同用户的 diaryId 是各自自增的，
     * 单靠 diaryId 可能撞车。加上 userId 才是全局唯一。
     *
     * <p>⚠️ 为什么把 {@code taskType} 也放进去：同一篇日记将来可能同时有
     * 多个任务（分析、抽取记忆、建向量）。只用
     * {@code userId + sourceId} 做键的话，它们会互相顶掉 ——
     * 而这三个任务本来就该各跑各的。
     */
    public static String buildIdempotencyKey(Long userId, SourceType sourceType,
                                             Long sourceId, TaskType taskType,
                                             int version) {
        return sourceType.name() + ':' + sourceId + ':' + taskType.name()
                + ':' + userId + ':' + version;
    }

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

    public SourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(SourceType sourceType) {
        this.sourceType = sourceType;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public void setSourceId(Long sourceId) {
        this.sourceId = sourceId;
    }

    public TaskType getTaskType() {
        return taskType;
    }

    public void setTaskType(TaskType taskType) {
        this.taskType = taskType;
    }

    public AiTaskStatus getStatus() {
        return status;
    }

    public void setStatus(AiTaskStatus status) {
        this.status = status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
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
     * <p>因为字段里有 {@link #errorMessage} —— 它虽然经过脱敏，
     * 但仍是"模型调用失败时的内部信息"。一旦有人
     * {@code log.info("task={}", task)}，它就会进日志。
     *
     * <p>开发文档 §5.4 把「完整 Prompt 全文」列为红线，
     * 而错误信息里很可能带模型返回的片段。所以这里保持默认实现
     * （只打印类名 + 哈希码），调试时用 {@link #toSafeString()}。
     */
    public String toSafeString() {
        return "AiTask{id=" + id
                + ", userId=" + userId
                + ", sourceType=" + sourceType
                + ", sourceId=" + sourceId
                + ", taskType=" + taskType
                + ", status=" + status
                + ", retryCount=" + retryCount
                + ", errorCode=" + errorCode
                // errorMessage 只报长度，不报内容
                + ", errorMessage=" + (errorMessage == null
                        ? "null"
                        : "(长度 " + errorMessage.length() + "，内容不打印)")
                + ", createdAt=" + createdAt
                + ", finishedAt=" + finishedAt
                + '}';
    }
}
