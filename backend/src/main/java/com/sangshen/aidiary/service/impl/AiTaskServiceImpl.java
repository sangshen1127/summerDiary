package com.sangshen.aidiary.service.impl;

import com.sangshen.aidiary.common.ErrorCode;
import com.sangshen.aidiary.config.AiAnalysisProperties;
import com.sangshen.aidiary.dto.response.AiTaskResponse;
import com.sangshen.aidiary.entity.AiTask;
import com.sangshen.aidiary.entity.enums.AiTaskTypes.SourceType;
import com.sangshen.aidiary.entity.enums.AiTaskTypes.TaskType;
import com.sangshen.aidiary.exception.BusinessException;
import com.sangshen.aidiary.mapper.AiTaskMapper;
import com.sangshen.aidiary.service.AiTaskService;
import com.sangshen.aidiary.service.DiaryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 异步任务服务实现。
 *
 * <h2>本类里最容易写错的三件事</h2>
 * <ol>
 *   <li><b>用"先查再插"代替 {@code INSERT IGNORE}</b>：
 *       查与插之间有竞态窗口，两个并发请求会各插一条。
 *       好在数据库的唯一索引兜底 —— 但那样会抛
 *       {@code DuplicateKeyException}（500），而不是安静地幂等。
 *       所以统一走 {@link AiTaskMapper#insertIgnoreDuplicate}。</li>
 *   <li><b>重新分析时新建任务</b>：唯一约束会让它失败。
 *       正确做法是 {@link AiTaskMapper#resetForRetry}（复用同一行）。</li>
 *   <li><b>在 Controller 路径上同步执行分析</b>：那会把异步架构的意义全部抹掉。
 *       本类只改状态，执行永远交给 {@code AiTaskWorker}。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么本类不注入 {@code CognitionService}</h2>
 *
 * <p>不是为了"解耦好看"，是为了<b>让错误不可能发生</b>：
 * 拿不到 {@code CognitionService} 就写不出"在这里调模型"的代码。
 * 依赖注入的边界就是架构约束的执行者。
 */
@Service
public class AiTaskServiceImpl implements AiTaskService {

    private static final Logger log = LoggerFactory.getLogger(AiTaskServiceImpl.class);

    private final AiTaskMapper aiTaskMapper;
    private final DiaryService diaryService;
    private final AiAnalysisProperties properties;

    public AiTaskServiceImpl(AiTaskMapper aiTaskMapper,
                             DiaryService diaryService,
                             AiAnalysisProperties properties) {
        this.aiTaskMapper = aiTaskMapper;
        this.diaryService = diaryService;
        this.properties = properties;
    }

    @Override
    public AiTaskResponse getTask(Long taskId, Long userId) {
        AiTask task = aiTaskMapper.selectByIdAndUserId(taskId, userId);
        if (task == null) {
            // 「不存在」与「不属于我」返回同一个 40401（开发文档 §4.2）
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return AiTaskResponse.from(task, properties.getMaxRetries());
    }

    @Override
    public AiTaskResponse requestReanalysis(Long diaryId, Long userId) {
        // ── 步骤 1：确认这篇日记是我的（不是 → 40401）──────────────
        // 必须放在最前面：否则任何人都能给别人的日记排队分析。
        if (diaryId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "日记 ID 不能为空");
        }
        diaryService.requireOwned(diaryId, userId);

        // ── 步骤 2：服务端有没有 AI 能力 ──────────────────────────
        // 关着的时候入队也没有 Worker 去执行，会留下永远 PENDING 的记录，
        // 前端会一直显示"分析中"。所以直接拒绝，让前端显示「AI 未启用」。
        //
        // ⚠️ 这里用 50011（AI 服务不可用，HTTP 502）而不是 40001：
        //    请求本身没问题，是"服务端这个能力暂时没有"。
        //    前端据此显示灰态提示，而不是把用户输入标红。
        if (!properties.isAnalysisEnabled()) {
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                    "AI 分析功能当前未启用，无法发起分析");
        }

        // ── 步骤 3：没有任务就补一条 ──────────────────────────────
        AiTask existing = findTask(diaryId, userId);
        if (existing == null) {
            AiTask pending = AiTask.newPending(
                    userId, SourceType.DIARY, diaryId,
                    TaskType.DIARY_ANALYZE, properties.getPromptVersion());
            int inserted = aiTaskMapper.insertIgnoreDuplicate(pending);
            // inserted == 0 是正常情况：并发请求抢先插入了同一条（幂等键相同）。
            // 不当作错误 —— 结果一样是"有且只有一条任务"。
            log.debug("重新分析：入队新任务 outcome={} userId={} diaryId={}",
                    inserted == 1 ? "inserted" : "duplicate-ignored", userId, diaryId);
        } else {
            // ── 步骤 4：已有任务 → 尝试重置 ────────────────────────
            // resetForRetry 的 WHERE 带 status IN ('FAILED','SUCCESS')：
            //   返回 1 = 已重新排队
            //   返回 0 = 当前状态不允许（PENDING/RUNNING/RETRYING/CANCELLED）
            // 返回 0 不是错误，直接返回最新状态给前端即可。
            int reset = aiTaskMapper.resetForRetry(existing.getId());
            log.debug("重新分析：重置已有任务 outcome={} taskId={} diaryId={}",
                    reset, existing.getId(), diaryId);
        }

        // ── 步骤 5：回读最新状态 ─────────────────────────────────
        // ⚠️ 必须回读，不能凭上面两个分支"猜"结果：
        //    - Worker 可能已经在毫秒级内把任务捞成 RUNNING
        //    - resetForRetry 返回 0 时我们根本不知道它现在是什么状态
        //    - created_at / started_at 这些时间字段只有数据库才有
        // 返回"猜的状态"会让前端轮询逻辑出错（比如显示 pending 但实际已成功）。
        AiTask fresh = findTask(diaryId, userId);
        if (fresh == null) {
            // 理论上不可达：上面刚保证了存在（插入或已存在）。
            // 真发生说明数据被并发清掉了，属于异常，交给 50001。
            throw new IllegalStateException(
                    "重新分析后读不到任务: userId=" + userId + " diaryId=" + diaryId);
        }
        return AiTaskResponse.from(fresh, properties.getMaxRetries());
    }

    /**
     * 查某篇日记的分析任务（没有则返回 null）。
     *
     * <p>用 {@code sourceId + taskType} 而不是 taskId 来定位：
     * 调用方手上只有 diaryId（URL 里就这一个 ID），
     * 而"一篇日记一条分析任务"是幂等键保证的不变量。
     */
    private AiTask findTask(Long diaryId, Long userId) {
        return aiTaskMapper.selectBySource(
                userId, SourceType.DIARY, diaryId, TaskType.DIARY_ANALYZE);
    }
}
