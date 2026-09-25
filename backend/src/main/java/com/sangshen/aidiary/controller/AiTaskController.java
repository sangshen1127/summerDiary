package com.sangshen.aidiary.controller;

import com.sangshen.aidiary.common.Result;
import com.sangshen.aidiary.dto.response.AiTaskResponse;
import com.sangshen.aidiary.security.SecurityUtils;
import com.sangshen.aidiary.service.AiTaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 任务接口。
 *
 * <p>契约见开发文档 §4.6「AI 任务与对话」段落：
 * <pre>
 * GET  /api/ai/tasks/{id}              查询任务状态
 * POST /api/ai/diaries/{id}/analyze    主动重试分析（幂等）
 * </pre>
 *
 * <h2>⚠️ 为什么"重新分析"是 POST 而不是 PUT/PATCH</h2>
 *
 * <p>它确实"修改了任务状态"，看起来像 PUT。但语义上它是
 * <b>发起一个动作</b>（"请分析这篇日记"），不是"把资源改成某个样子" ——
 * 客户端并不指定改成什么状态，也不需要幂等地给出目标状态。
 *
 * <p>更实际的理由：<b>POST 可以安全重发</b>。本接口是幂等的
 * （同一篇日记永远只有一条任务），所以重复 POST 不会产生副作用，
 * 这与 POST 在 HTTP 语义里的"非安全但可重复提交"定位一致。
 *
 * <h2>⚠️ 为什么没有 {@code /api/ai/tasks} 列表接口</h2>
 *
 * <p>开发文档 §4.6 里没有列，也想不到真实需求：
 * 用户关心的是"我这篇日记分析好了没"（走
 * {@code GET /api/diaries/{id}/analysis}），不是"系统里有多少任务"。
 * 没有需求就不加接口 —— 每加一个接口都要配权限、测试、文档。
 */
@RestController
@RequestMapping("/api/ai")
public class AiTaskController {

    private final AiTaskService aiTaskService;

    public AiTaskController(AiTaskService aiTaskService) {
        this.aiTaskService = aiTaskService;
    }

    /**
     * 查询任务状态。
     *
     * <pre>GET /api/ai/tasks/42</pre>
     *
     * <p>返回 {@code status} 是<b>对外映射后</b>的取值
     * （{@code pending / running / success / failed / cancelled}），
     * 不是后端的枚举名。{@code RETRYING} 会被归到 {@code pending} ——
     * 对用户来说"等待重试"与"排队中"没有区别。
     * 映射规则见 {@code AiTaskResponse.toExternalStatus}。
     *
     * <p>失败：任务不存在或不属于当前用户 → 404 / {@code 40401}。
     *
     * @param id 任务 ID
     * @return 任务状态
     */
    @GetMapping("/tasks/{id}")
    public Result<AiTaskResponse> task(@PathVariable("id") Long id) {
        return Result.ok(aiTaskService.getTask(id, SecurityUtils.getCurrentUserId()));
    }

    /**
     * 请求（重新）分析某篇日记。
     *
     * <pre>POST /api/ai/diaries/42/analyze</pre>
     *
     * <p>成功返回 200，{@code data} 是任务的<b>最新</b>状态。
     *
     * <p><b>⚠️ 本接口是"排队"，不是"执行"</b>：返回时任务通常还是
     * {@code pending}，真正的分析由后台 Worker 完成。前端拿到
     * {@code pending} 后应当轮询 {@code GET /api/diaries/{id}/analysis}，
     * <b>不要</b>期待这次请求返回 {@code success}。
     *
     * <h2>⚠️ 三种调用结果都不算失败</h2>
     * <ul>
     *   <li>本来就在跑 → 什么都不做，返回当前状态（{@code can_retry=false}）</li>
     *   <li>已经成功过 → 重置为 PENDING，重新分析一遍（会覆盖旧结果）</li>
     *   <li>上次失败了 → 重置为 PENDING，重试次数归零</li>
     * </ul>
     *
     * <p>失败：
     * <ul>
     *   <li>未登录 → 401 / {@code 40101}</li>
     *   <li>日记不存在或不属于当前用户 → 404 / {@code 40401}</li>
     *   <li>服务端 AI 能力未开启（{@code AI_ANALYSIS_ENABLED=false}）
     *       → 502 / {@code 50011}</li>
     * </ul>
     *
     * @param id 日记 ID
     * @return 任务的最新状态
     */
    @PostMapping("/diaries/{id}/analyze")
    public Result<AiTaskResponse> analyze(@PathVariable("id") Long id) {
        return Result.ok(aiTaskService.requestReanalysis(id, SecurityUtils.getCurrentUserId()));
    }
}
