package com.sangshen.aidiary.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sangshen.aidiary.entity.AiTask;
import com.sangshen.aidiary.entity.DiaryAnalysis;

/**
 * 日记 AI 分析「一屏所需」的响应 —— {@code GET /api/diaries/{id}/analysis}。
 *
 * <h2>⚠️ 为什么把「状态」和「结果」放进同一个响应</h2>
 *
 * <p>前端日记详情页的「AI 分析」区块需要同时知道两件事：
 * <ol>
 *   <li><b>现在什么状态</b>（还没分析 / 分析中 / 失败 / 成功）—— 决定画哪个界面</li>
 *   <li><b>分析结果是什么</b>（仅成功时有）—— 决定渲染什么内容</li>
 * </ol>
 *
 * <p>最"符合直觉"的做法是拆成两个接口（状态查 {@code ai_task}、
 * 结果查 {@code diary_analysis}），但那样前端每次轮询要发两个请求，
 * 而且会出现<b>两次请求之间状态刚刚翻转</b>的不一致窗口：
 * 状态接口说 SUCCESS，结果接口还查不到结果 —— 前端只能显示一个空壳。
 *
 * <p>合成一个响应后，「状态」与「结果」来自<b>同一次数据库快照</b>，
 * 不可能互相矛盾。代价是这个 DTO 承担了两种语义，所以字段注释要写清楚
 * 哪些字段是"永远有值"、哪些是"看状态才有值"。
 *
 * <h2>字段可用性矩阵</h2>
 *
 * <table border="1">
 *   <caption>各状态下哪些字段有值</caption>
 *   <tr><th>status</th><th>can_retry</th><th>error_*</th><th>analysis</th></tr>
 *   <tr><td>{@code null}（尚无任务）</td><td>true</td><td>null</td><td>null</td></tr>
 *   <tr><td>{@code pending} / {@code running}</td><td>false</td><td>null</td><td>可能为 null</td></tr>
 *   <tr><td>{@code success}</td><td>true</td><td>null</td><td><b>非 null</b></td></tr>
 *   <tr><td>{@code failed}</td><td>true</td><td>非 null</td><td>null</td></tr>
 *   <tr><td>{@code cancelled}</td><td>true</td><td>非 null</td><td>null</td></tr>
 * </table>
 *
 * <p>⚠️ 注意 {@code running} 时 {@code analysis} <b>可能不为 null</b>：
 * 用户点「重新分析」后，旧结果仍然保留在库里（{@code upsert} 覆盖，
 * 不是先删后插），直到新结果写进去。这是刻意的 ——
 * 重新分析失败时，用户至少还能看到上一次的结果，不会"越点越空"。
 *
 * <h2>⚠️ enabled 与 user.ai_enabled 的区别</h2>
 *
 * <p>这两个字段<b>都要看</b>，表达的是不同的事：
 * <ul>
 *   <li>{@code user.ai_enabled}（在 {@code /api/auth/me} 里）——<b>用户想不想用</b>。
 *       用户自己关掉的，前端应显示「你已关闭 AI 分析」并提供开启入口</li>
 *   <li>本响应的 {@code enabled} —— <b>服务端能不能用</b>。
 *       全局开关（{@code AI_ANALYSIS_ENABLED}）关闭、或没配 API Key 时为 false。
 *       前端应显示「AI 功能暂未开放」，并且<b>不该给重试按钮</b> ——
 *       因为点了一定失败</li>
 * </ul>
 *
 * <p>只判断其中一个都会得到错误的界面：只看用户开关，
 * 服务端没开时用户会看到一个永远转不动的「分析中」；
 * 只看服务端开关，用户关掉了功能却还看到"可以分析"。
 *
 * @param diaryId     日记 ID
 * @param enabled     服务端 AI 分析能力是否可用（全局开关）
 * @param status      对外状态：{@code pending / running / success / failed / cancelled}，
 *                    <b>尚无任务时为 {@code null}</b>（表示"这篇日记还没排过队"）
 * @param canRetry    现在能否调用 {@code POST /api/ai/diaries/{id}/analyze}
 * @param retryCount  已重试次数；无任务时为 0
 * @param maxRetries  最大重试次数（取自配置）
 * @param errorCode   错误类别码；无错误时为 null
 * @param errorMessage 已脱敏的错误原因；无错误时为 null
 * @param analysis    分析结果；尚未产出时为 null
 */
public record DiaryAnalysisDetailResponse(

        @JsonProperty("diary_id")
        Long diaryId,

        boolean enabled,

        /**
         * 对外状态。null 表示"尚无任务"。
         *
         * <p>⚠️ 与 {@link AiTaskResponse#status()} 用同一套取值，
         * 由 {@link AiTaskResponse#toExternalStatus} 统一映射 ——
         * 两处各写一遍映射迟早会漂移（比如 {@code RETRYING} 在一处
         * 映射成 {@code pending}、另一处忘了改）。
         */
        String status,

        @JsonProperty("can_retry")
        boolean canRetry,

        @JsonProperty("retry_count")
        int retryCount,

        @JsonProperty("max_retries")
        int maxRetries,

        @JsonProperty("error_code")
        String errorCode,

        @JsonProperty("error_message")
        String errorMessage,

        DiaryAnalysisResponse analysis
) {

    /**
     * 「尚无任务」时的响应。
     *
     * @param diaryId    日记 ID
     * @param enabled    服务端 AI 能力是否可用
     * @param maxRetries 最大重试次数（来自配置，即便没任务也如实上报）
     * @return 全空状态
     */
    public static DiaryAnalysisDetailResponse notAnalyzed(Long diaryId,
                                                          boolean enabled,
                                                          int maxRetries) {
        // canRetry = enabled：还没有任务时，能点"开始分析"的前提只有一个 ——
        // 服务端确实有 AI 能力。反过来，关闭开关时按钮必须消失，
        // 而不是让用户点了再收到 50011。
        return new DiaryAnalysisDetailResponse(
                diaryId, enabled, null, enabled, 0, maxRetries, null, null, null);
    }

    /**
     * 有任务时的响应。
     *
     * @param diaryId     日记 ID
     * @param enabled     服务端 AI 能力是否可用
     * @param task        非空的任务实体
     * @param maxRetries  最大重试次数（来自配置）
     * @param analysis    分析结果实体，可为 null（尚未产出）
     * @return 组装好的响应
     * @throws IllegalArgumentException task 为 null 时抛出
     */
    public static DiaryAnalysisDetailResponse of(Long diaryId,
                                                 boolean enabled,
                                                 AiTask task,
                                                 int maxRetries,
                                                 DiaryAnalysis analysis) {
        if (task == null) {
            throw new IllegalArgumentException("AiTask 不能为 null（无任务请用 notAnalyzed）");
        }
        // ⚠️ canRetry 要同时满足两个条件：
        //    1) 服务端有 AI 能力（否则点了必失败）
        //    2) 任务状态允许重置（RUNNING 时不能重复触发，见 AiTaskMapper.resetForRetry）
        //    第二个条件用 AiTaskResponse.from 里已经算好的值，不在这里重算 ——
        //    状态判断的规则只有一处。
        boolean stateAllowsRetry = AiTaskResponse.from(task, maxRetries).canRetry();
        return new DiaryAnalysisDetailResponse(
                diaryId,
                enabled,
                AiTaskResponse.toExternalStatus(task.getStatus()),
                enabled && stateAllowsRetry,
                task.getRetryCount(),
                maxRetries,
                task.getErrorCode(),
                task.getErrorMessage(),
                analysis == null ? null : DiaryAnalysisResponse.from(analysis));
    }
}
