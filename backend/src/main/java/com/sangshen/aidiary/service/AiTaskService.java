package com.sangshen.aidiary.service;

import com.sangshen.aidiary.dto.response.AiTaskResponse;

/**
 * 异步任务服务 —— 前端能看到的任务操作都收在这里。
 *
 * <p>契约见开发文档 §4.6「AI 任务与对话」段落：
 * <pre>
 * GET  /api/ai/tasks/{id}              查询任务状态
 * POST /api/ai/diaries/{id}/analyze    主动重试分析（幂等）
 * </pre>
 *
 * <h2>⚠️ 本服务<b>不</b>执行任务</h2>
 *
 * <p>真正执行分析的是 {@code AiTaskWorker}（定时轮询）。本服务只做两件事：
 * <b>读状态</b>、<b>把任务重新排队</b>。
 *
 * <p>这个划分很重要：如果 Controller 路径上能直接执行分析，
 * 那"用户在浏览器里点一下"就会同步等待模型响应 ——
 * 也就是把已经用异步解决掉的超时问题又请回来了。
 * 所以 {@link #requestReanalysis} 只把状态改回 {@code PENDING}，
 * 剩下的交给 Worker。
 *
 * <h2>⚠️ 所有查询都必须带 userId</h2>
 *
 * <p>任务 ID 是自增的，用别人的 ID 就能猜到大概（"现在系统里有多少任务"）。
 * Mapper 的 SQL 一律带 {@code user_id}，「不存在」与「不属于我」都返回
 * {@code 40401}，与日记/标签的口径一致。
 */
public interface AiTaskService {

    /**
     * 按 ID 查询任务状态。
     *
     * <pre>GET /api/ai/tasks/42</pre>
     *
     * <p>前端一般不需要直接用它 —— 日记详情页要的信息
     * {@code GET /api/diaries/{id}/analysis} 一次就给全了。
     * 本接口主要给"我只知道 taskId"的调试与排查场景用。
     *
     * @param taskId 任务 ID
     * @param userId 当前用户 ID
     * @return 任务状态（错误信息已脱敏）
     * @throws com.sangshen.aidiary.exception.BusinessException
     *         任务不存在或不属于当前用户 → {@code 40401}
     */
    AiTaskResponse getTask(Long taskId, Long userId);

    /**
     * 请求（重新）分析某篇日记。
     *
     * <pre>POST /api/ai/diaries/42/analyze</pre>
     *
     * <h2>⚠️ 幂等 —— 本方法保证"同一篇日记只有一条分析任务"</h2>
     *
     * <p>三种进入情况，行为不同但都不报错：
     * <table border="1">
     *   <caption>requestReanalysis 的分支</caption>
     *   <tr><th>进去时</th><th>做什么</th></tr>
     *   <tr><td>还没有任务（AI 关着时创建的日记、或老数据）</td>
     *       <td>补入队一条 PENDING</td></tr>
     *   <tr><td>任务已终态（SUCCESS / FAILED / CANCELLED）</td>
     *       <td><b>重置同一条</b>为 PENDING，不新建</td></tr>
     *   <tr><td>任务在跑（PENDING / RUNNING / RETRYING）</td>
     *       <td>什么都不做，原样返回当前状态</td></tr>
     * </table>
     *
     * <p>为什么不新建任务：幂等键有唯一约束，同一个
     * {@code 来源+类型+版本} 只能有一条。"新建"这条路根本走不通。
     *
     * <p>为什么"正在跑"时静默返回而不是报 40901：用户连点两次按钮
     * 是很常见的（网络慢、没看到页面变化）。这不是错误，
     * 返回当前真实状态比抛异常更符合用户预期 ——
     * 前端拿到的 {@code can_retry=false} 会自己把按钮禁掉。
     *
     * <h2>为什么在这里校验日记归属</h2>
     *
     * <p>否则可以传别人的 diaryId 来给别人的日记排队分析 ——
     * 既浪费模型额度，也是一种越权（让别人的数据被处理）。
     * {@code diaryService.requireOwned} 会抛 {@code 40401}。
     *
     * @param diaryId 日记 ID
     * @param userId  当前用户 ID
     * @return 任务的<b>最新</b>状态（不是"期望"状态 —— 重置后 Worker 可能已经捞走了）
     * @throws com.sangshen.aidiary.exception.BusinessException
     *         日记不存在/不属于当前用户 → {@code 40401}；
     *         服务端 AI 能力未开启 → {@code 50011}
     */
    AiTaskResponse requestReanalysis(Long diaryId, Long userId);
}
