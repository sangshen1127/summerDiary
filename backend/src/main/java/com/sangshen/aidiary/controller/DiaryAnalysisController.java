package com.sangshen.aidiary.controller;

import com.sangshen.aidiary.common.Result;
import com.sangshen.aidiary.dto.response.DiaryAnalysisDetailResponse;
import com.sangshen.aidiary.security.SecurityUtils;
import com.sangshen.aidiary.service.DiaryAnalysisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 日记 AI 分析结果接口。
 *
 * <p>契约见开发文档 §4.6：
 * <pre>GET /api/diaries/{id}/analysis   查看 AI 摘要和结构化分析</pre>
 *
 * <h2>⚠️ 为什么单独一个 Controller，而不是塞进 {@code DiaryController}</h2>
 *
 * <p>路径上前者是后者的子路径，塞进去也合法。分开是因为<b>依赖不同</b>：
 * {@code DiaryController} 只需要 {@code DiaryService}（读写日记 + 加解密），
 * 本类只需要 {@code DiaryAnalysisService}（只读分析）。
 *
 * <p>合在一起会让 {@code DiaryController} 多依赖一个 Service，
 * 而"日记 CRUD"和"AI 分析"是两件会分别演进的事 ——
 * Phase 4（对话）、Phase 5（记忆）还会各自加 Controller。
 * 按关注点切分，比按 URL 前缀切分更耐改。
 *
 * <h2>⚠️ 本接口永远返回 200（除了 401/404）</h2>
 *
 * <p>"还没分析好"<b>不是</b>错误。前端轮询时最怕的就是
 * "正常等待"和"真的失败"混在同一个错误码里 ——
 * 所以这里把状态放在 {@code data.status} 里表达，
 * 而不是用 4xx/5xx。详见 {@code DiaryAnalysisService} 的注释。
 */
@RestController
@RequestMapping("/api/diaries")
public class DiaryAnalysisController {

    private final DiaryAnalysisService diaryAnalysisService;

    public DiaryAnalysisController(DiaryAnalysisService diaryAnalysisService) {
        this.diaryAnalysisService = diaryAnalysisService;
    }

    /**
     * 查询日记的 AI 分析状态与结果。
     *
     * <pre>
     * GET /api/diaries/42/analysis
     *
     * {
     *   "code": 0,
     *   "message": "success",
     *   "data": {
     *     "diary_id": 42,
     *     "enabled": true,
     *     "status": "success",
     *     "can_retry": true,
     *     "retry_count": 0,
     *     "max_retries": 3,
     *     "error_code": null,
     *     "error_message": null,
     *     "analysis": {
     *       "diary_id": 42,
     *       "summary": "……",
     *       "emotion": { "label": "平静", "score": 0.5 },
     *       "topics": ["散步"],
     *       "entities": [],
     *       "recent_state": [],
     *       "long_term_facts": [],
     *       "schema_version": "1.0",
     *       "prompt_version": "mock-v1",
     *       "created_at": "2026-09-25T10:00:00Z"
     *     }
     *   }
     * }
     * </pre>
     *
     * <p>⚠️ {@code emotion} / {@code topics} 等在响应里是<b>真正的 JSON
     * 对象/数组</b>，不是被转义的字符串 —— 前端不需要 {@code JSON.parse}。
     * 实现见 {@code DiaryAnalysisResponse} 的 {@code @JsonRawValue}。
     *
     * <p>⚠️ 前端轮询约定：只在 {@code status} 是
     * {@code "pending"} 或 {@code "running"} 时轮询，
     * 终态（{@code success / failed / cancelled / null}）要停 ——
     * 否则页面开着就会一直发请求。
     *
     * <p>失败：
     * <ul>
     *   <li>未登录 → 401 / {@code 40101}</li>
     *   <li>日记不存在、不属于当前用户、或已删除 → 404 / {@code 40401}</li>
     * </ul>
     *
     * @param id 日记 ID
     * @return 分析状态与结果
     */
    @GetMapping("/{id}/analysis")
    public Result<DiaryAnalysisDetailResponse> analysis(@PathVariable("id") Long id) {
        // ⚠️ userId 只从安全上下文取，绝不接受请求参数 —— 开发文档 §4.7 权限铁律
        return Result.ok(diaryAnalysisService.getAnalysis(id, SecurityUtils.getCurrentUserId()));
    }
}
