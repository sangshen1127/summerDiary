package com.sangshen.aidiary.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sangshen.aidiary.entity.AiTask;
import com.sangshen.aidiary.entity.enums.AiTaskStatus;

import java.time.Instant;

/**
 * 异步任务状态响应（{@code GET /api/ai/tasks/{id}}）。
 *
 * <h2>⚠️ status 用字符串，不暴露后端枚举名</h2>
 *
 * <p>{@link AiTaskStatus} 是后端内部状态机，枚举名（{@code PENDING} /
 * {@code RETRYING}）带实现细节。直接序列化出去会把前端绑死在
 * "我们的状态机长什么样"上，将来重命名状态就是破坏性变更。
 *
 * <p>所以这里映射成一组**稳定的、小写的**对外值：
 * <pre>
 *   PENDING   -> "pending"
 *   RUNNING   -> "running"
 *   SUCCESS   -> "success"
 *   FAILED    -> "failed"      （可能还会重试，看 can_retry）
 *   RETRYING  -> "pending"     ← 刻意归到 pending
 *   CANCELLED -> "cancelled"
 * </pre>
 *
 * <p><b>为什么 RETRYING 映射成 "pending" 而不是 "retrying"</b>：
 * 从用户视角，"等待重试"和"排队中"是同一件事——都是"还没轮到我"。
 * 多暴露一个前端用不到的状态，只会让前端多一个必须处理的分支。
 *
 * <h2>前端该怎么用</h2>
 * <pre>{@code
 * if (status === 'pending' || status === 'running') → 显示「分析中…」
 * else if (status === 'success')                    → 渲染分析结果
 * else if (status === 'failed')                     → 显示错误 + 重试按钮（若 can_retry）
 * }</pre>
 *
 * <h2>⚠️ error_message 会返回给前端吗</h2>
 *
 * <p>会，但它是**已脱敏**的（见 {@code AiTaskErrorSanitizer}）：
 * 密钥被替换成 {@code ***}、超长被截断、不含日记正文。
 *
 * <p>要不要展示给用户是产品决策。当前设计是**返回**，
 * 让前端可以放在"详情"折叠区里，便于用户反馈问题时报给我们。
 *
 * @param id          任务 ID
 * @param status      对外状态（pending / running / success / failed / cancelled）
 * @param retryCount  已重试次数
 * @param maxRetries  最大重试次数（前端据此显示"还能重试几次"）
 * @param canRetry    现在能不能点重试（failed 且未在运行中）
 * @param errorCode   错误类别码，成功时为 null
 * @param errorMessage 已脱敏的错误原因，成功时为 null
 * @param startedAt   开始执行时间
 * @param finishedAt  结束时间
 * @param createdAt   入队时间
 */
public record AiTaskResponse(

        Long id,

        String status,

        @JsonProperty("retry_count")
        int retryCount,

        @JsonProperty("max_retries")
        int maxRetries,

        /**
         * 现在能否触发重新分析。
         *
         * <p>由后端算好而不是让前端判断：判断规则涉及"状态是否允许重置"
         * （见 {@code AiTaskMapper.resetForRetry} 的 WHERE 条件），
         * 前端复刻一遍迟早会不一致。
         */
        @JsonProperty("can_retry")
        boolean canRetry,

        @JsonProperty("error_code")
        String errorCode,

        @JsonProperty("error_message")
        String errorMessage,

        @JsonProperty("started_at")
        Instant startedAt,

        @JsonProperty("finished_at")
        Instant finishedAt,

        @JsonProperty("created_at")
        Instant createdAt
) {

    /**
     * 从实体转换。
     *
     * @param task       非空的任务实体
     * @param maxRetries 最大重试次数（来自配置，不在这条记录里）
     * @return 响应对象
     * @throws IllegalArgumentException task 为 null 时抛出
     */
    public static AiTaskResponse from(AiTask task, int maxRetries) {
        if (task == null) {
            throw new IllegalArgumentException("AiTask 不能为 null");
        }
        AiTaskStatus status = task.getStatus();
        return new AiTaskResponse(
                task.getId(),
                toExternalStatus(status),
                task.getRetryCount(),
                maxRetries,
                status == AiTaskStatus.FAILED || status == AiTaskStatus.SUCCESS,
                task.getErrorCode(),
                task.getErrorMessage(),
                UtcTime.toInstant(task.getStartedAt()),
                UtcTime.toInstant(task.getFinishedAt()),
                UtcTime.toInstant(task.getCreatedAt()));
    }

    /**
     * 后端状态 → 对外状态。
     *
     * <p>用 switch 而不查表：遗漏新增状态时编译器会报错
     * （Java 17 的穷尽 switch 对枚举有覆盖检查）。
     * 如果改成 Map，新增枚举值只会静默返回 null。
     *
     * <p>⚠️ 声明为 {@code public} 是<b>刻意的</b>，不是随手放开：
     * 除本类之外，{@code DiaryServiceImpl}（填日记详情里的
     * {@code analysis_status}）与 {@code DiaryAnalysisDetailResponse}
     * 也要用这套映射。三处各自写一遍的话，将来新增状态时
     * 漏改一处的症状是"详情页显示 pending、AI 区块显示 success" ——
     * 同一份数据在同一屏上自相矛盾，而且不报错。
     *
     * @param status 后端状态，可为 null
     * @return 对外状态字符串；入参为 null 时返回 {@code "unknown"}
     */
    public static String toExternalStatus(AiTaskStatus status) {
        if (status == null) {
            return "unknown";
        }
        return switch (status) {
            case PENDING, RETRYING -> "pending";
            case RUNNING -> "running";
            case SUCCESS -> "success";
            case FAILED -> "failed";
            case CANCELLED -> "cancelled";
        };
    }
}
