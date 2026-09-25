package com.sangshen.aidiary.service.impl;

import com.sangshen.aidiary.config.AiAnalysisProperties;
import com.sangshen.aidiary.dto.response.DiaryAnalysisDetailResponse;
import com.sangshen.aidiary.entity.AiTask;
import com.sangshen.aidiary.entity.DiaryAnalysis;
import com.sangshen.aidiary.entity.enums.AiTaskTypes.SourceType;
import com.sangshen.aidiary.entity.enums.AiTaskTypes.TaskType;
import com.sangshen.aidiary.mapper.AiTaskMapper;
import com.sangshen.aidiary.mapper.DiaryAnalysisMapper;
import com.sangshen.aidiary.service.DiaryAnalysisService;
import com.sangshen.aidiary.service.DiaryService;
import org.springframework.stereotype.Service;

/**
 * 日记 AI 分析查询服务实现。
 *
 * <h2>为什么最多只查两张表、两次 SQL</h2>
 *
 * <pre>
 *   1. diaryService.requireOwned(diaryId, userId)   —— 权限（1 次查询）
 *   2. aiTaskMapper.selectBySource(...)             —— 状态（1 次查询）
 *   3. diaryAnalysisMapper.selectByDiaryIdAndUserId —— 结果（1 次查询）
 * </pre>
 *
 * <p>第 3 步<b>无条件执行</b>，即使状态是 FAILED。
 * 为什么不"看到 FAILED 就跳过查询"：
 * 用户点「重新分析」后失败时，库里仍然保留着<b>上一次成功的结果</b>
 * （{@code upsert} 覆盖，不是先删后插）。跳过查询会让前端在失败时
 * 把上一次的结果也一起藏起来 —— 用户会觉得"越点越空"。
 * 多一次走唯一索引的查询换一个更符合直觉的界面，这个交换是划算的。
 *
 * <h2>⚠️ enabled 来自配置而不是数据库</h2>
 *
 * <p>{@code app.ai.analysis-enabled} 是全局开关，与具体用户无关。
 * 它和 {@code user.ai_enabled}（用户自己的偏好）是<b>两件事</b>，
 * 前端两个都要看，理由写在 {@link DiaryAnalysisDetailResponse} 的类注释里。
 */
@Service
public class DiaryAnalysisServiceImpl implements DiaryAnalysisService {

    private final DiaryService diaryService;
    private final AiTaskMapper aiTaskMapper;
    private final DiaryAnalysisMapper diaryAnalysisMapper;
    private final AiAnalysisProperties properties;

    public DiaryAnalysisServiceImpl(DiaryService diaryService,
                                    AiTaskMapper aiTaskMapper,
                                    DiaryAnalysisMapper diaryAnalysisMapper,
                                    AiAnalysisProperties properties) {
        this.diaryService = diaryService;
        this.aiTaskMapper = aiTaskMapper;
        this.diaryAnalysisMapper = diaryAnalysisMapper;
        this.properties = properties;
    }

    @Override
    public DiaryAnalysisDetailResponse getAnalysis(Long diaryId, Long userId) {
        // ── 步骤 1：确权（不是我的日记 → 40401，且不泄露"是否存在"）──
        diaryService.requireOwned(diaryId, userId);

        boolean enabled = properties.isAnalysisEnabled();
        int maxRetries = properties.getMaxRetries();

        // ── 步骤 2：查任务状态 ──────────────────────────────────
        AiTask task = aiTaskMapper.selectBySource(
                userId, SourceType.DIARY, diaryId, TaskType.DIARY_ANALYZE);

        if (task == null) {
            // 还没有任务。这是【正常情况】而不是 404：
            //   - AI 开关关着时创建的日记
            //   - 日记建于 Phase 3 之前
            // 前端据此显示"尚未分析"，并可按 enabled 决定给不给按钮。
            return DiaryAnalysisDetailResponse.notAnalyzed(diaryId, enabled, maxRetries);
        }

        // ── 步骤 3：查结果（可能为 null —— 还在跑、或失败了）──────
        DiaryAnalysis analysis =
                diaryAnalysisMapper.selectByDiaryIdAndUserId(diaryId, userId);

        return DiaryAnalysisDetailResponse.of(diaryId, enabled, task, maxRetries, analysis);
    }
}
