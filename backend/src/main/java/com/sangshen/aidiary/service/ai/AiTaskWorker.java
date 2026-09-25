package com.sangshen.aidiary.service.ai;

import com.sangshen.aidiary.ai.CognitionService;
import com.sangshen.aidiary.ai.DiaryAnalysisResult;
import com.sangshen.aidiary.common.crypto.AesGcmUtil;
import com.sangshen.aidiary.config.AiAnalysisProperties;
import com.sangshen.aidiary.entity.AiTask;
import com.sangshen.aidiary.entity.Diary;
import com.sangshen.aidiary.entity.DiaryAnalysis;
import com.sangshen.aidiary.entity.enums.AiTaskStatus;
import com.sangshen.aidiary.mapper.AiTaskMapper;
import com.sangshen.aidiary.mapper.DiaryAnalysisMapper;
import com.sangshen.aidiary.mapper.DiaryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 异步任务执行器 —— 从 {@code ai_task} 捞待办并执行。
 *
 * <h2>⚠️ 为什么用"轮询数据库"而不是消息队列</h2>
 *
 * <p>轮询在生产环境通常是要避免的（延迟高、空转消耗）。这里选它是因为：
 * <ul>
 *   <li><b>任务状态必须持久化</b>：前端要轮询"分析好没有"，失败要能重试。
 *       用内存队列的话，进程重启任务就丢了，而"任务丢了"是无法对用户交代的</li>
 *   <li><b>幂等与并发控制靠数据库的行锁</b>，不需要额外引入 Redis/MQ</li>
 *   <li>作业规模是"学生项目"级别：单机、每天几十篇日记。
 *       轮询一次的开销（走索引的 LIMIT 查询）完全可以接受</li>
 * </ul>
 *
 * <p>如果将来要上量，正确做法是引入 MQ 做"唤醒"，而<b>仍然</b>把状态放数据库 ——
 * 数据库是"事实"，队列只是"通知"。现在不必提前做。
 *
 * <h2>⚠️ 执行流程里的顺序不能乱</h2>
 *
 * <pre>
 *   1. 扫描可执行任务（跨用户，Worker 专用）
 *   2. 逐条「原子抢占」→ 抢到才执行
 *   3. 重新读任务（拿到抢占后的最新状态与 userId）
 *   4. 查日记（含密文）
 *   5. 解密
 *   6. 调 CognitionService.analyze()
 *   7. upsert 分析结果
 *   8. 标记 SUCCESS
 * </pre>
 *
 * <p>第 2 步必须在第 6 步<b>之前</b>：抢占是"排队"，先抢到再干活。
 * 如果把抢占放到后面，两个 Worker 都会调用模型（重复计费）。
 *
 * <p>第 3 步不是多余的：{@link AiTaskMapper#selectClaimable} 返回的对象
 * 是抢占<b>之前</b>的快照，其中 {@code userId} 虽然有值，但
 * {@code status/startedAt} 已经过期。重新读一次拿到权威数据，
 * 避免用陈旧状态做判断。
 *
 * <h2>⚠️ 一个任务的失败绝不能影响其他任务</h2>
 *
 * <p>{@link #executeOne} 内部把所有异常都收在方法里（记录 + 标记失败），
 * <b>不往外抛</b>。否则一条坏任务会让整轮扫描中断，后面的任务全部饿死 ——
 * 而且因为 {@code @Scheduled} 默认会吞掉异常并继续下一轮，
 * 症状是"某些日记永远不分析"，很难定位到"是被前一条任务连累的"。
 */
@Component
@ConditionalOnProperty(name = "app.ai.analysis-enabled", havingValue = "true")
public class AiTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(AiTaskWorker.class);

    /** 可被抢占的状态。PENDING = 新任务；RETRYING = 等待重试的任务。 */
    private static final List<AiTaskStatus> CLAIMABLE =
            List.of(AiTaskStatus.PENDING, AiTaskStatus.RETRYING);

    /** 单次扫描最多取多少条（SQL 层限制），防止一次捞太多打满内存。 */
    private static final int SCAN_LIMIT = 20;

    private final AiTaskMapper aiTaskMapper;
    private final DiaryMapper diaryMapper;
    private final DiaryAnalysisMapper diaryAnalysisMapper;
    private final CognitionService cognitionService;
    private final AesGcmUtil aesGcmUtil;
    private final AiAnalysisProperties properties;

    public AiTaskWorker(AiTaskMapper aiTaskMapper,
                        DiaryMapper diaryMapper,
                        DiaryAnalysisMapper diaryAnalysisMapper,
                        CognitionService cognitionService,
                        AesGcmUtil aesGcmUtil,
                        AiAnalysisProperties properties) {
        this.aiTaskMapper = aiTaskMapper;
        this.diaryMapper = diaryMapper;
        this.diaryAnalysisMapper = diaryAnalysisMapper;
        this.cognitionService = cognitionService;
        this.aesGcmUtil = aesGcmUtil;
        this.properties = properties;
    }

    /**
     * 定时扫描并执行待办任务。
     *
     * <p>间隔由 {@code app.ai.worker-interval-ms} 控制（默认 5 秒）。
     * 用 {@code fixedDelay}（上一轮<b>结束</b>后等 N 毫秒）而不是
     * {@code fixedRate}：后者在任务执行时间超过间隔时会堆积重叠执行。
     */
    @Scheduled(fixedDelayString = "${app.ai.worker-interval-ms:5000}")
    public void pollAndExecute() {
        List<AiTask> candidates;
        try {
            candidates = aiTaskMapper.selectClaimable(SCAN_LIMIT);
        } catch (Exception e) {
            // 扫描本身失败（数据库不可用等）。这里必须吞掉异常，
            // 否则 @Scheduled 每 5 秒刷一条错误日志把日志淹没。
            log.error("扫描待执行任务失败: {}", e.getClass().getSimpleName());
            return;
        }

        if (candidates.isEmpty()) {
            return;
        }

        int batch = Math.min(properties.getWorkerBatchSize(), candidates.size());
        log.debug("扫描到 {} 条待执行任务，本轮处理 {} 条", candidates.size(), batch);

        for (int i = 0; i < batch; i++) {
            executeOne(candidates.get(i));
        }
    }

    /**
     * 抢占并执行一条任务。
     *
     * <p>入参用扫描时拿到的 {@link AiTask} 对象（而不是裸 ID）：
     * 抢占后需要 {@code userId} 去做后续的权限查询，
     * 而 {@code selectClaimable} 已经把 userId 带出来了。
     *
     * <p>⚠️ 但**不能信任这个对象里的 status / startedAt** ——
     * 它是抢占<b>之前的快照</b>。所以抢占成功后会重新读一次。
     *
     * @param candidate 扫描到的任务快照（只需要它的 id 与 userId 可信）
     */
    void executeOne(AiTask candidate) {
        Long taskId = candidate.getId();
        Long userId = candidate.getUserId();

        // ── 抢占 ────────────────────────────────────────────────
        int claimed = aiTaskMapper.claimForRunning(taskId, CLAIMABLE);
        if (claimed == 0) {
            // 正常情况：被另一个 Worker 抢走，或状态已被改。
            // 不是错误，debug 级别即可。
            log.debug("任务未被抢占（可能已被其他 Worker 取走）: taskId={}", taskId);
            return;
        }

        // ── 重新读取权威状态 ────────────────────────────────────
        // 抢占前那份快照的 status 还是 PENDING/RETRYING，
        // startedAt 也是 null。这里重新读，拿到 RUNNING + startedAt。
        AiTask task = aiTaskMapper.selectByIdAndUserId(taskId, userId);
        if (task == null) {
            // 理论上不可能：刚抢占成功。除非任务被并发删除。
            log.warn("抢占成功但读不到任务（可能被并发删除）: taskId={}", taskId);
            return;
        }

        long startedAt = System.currentTimeMillis();
        try {
            runAnalysis(task);
            aiTaskMapper.markSuccess(taskId, task.getPromptVersion());
            log.info("分析任务成功: taskId={} userId={} diaryId={} costMs={}",
                    taskId, task.getUserId(), task.getSourceId(),
                    System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            handleFailure(task, e, System.currentTimeMillis() - startedAt);
        }
    }

    /**
     * 真正执行分析：读日记 → 解密 → 调 AI → 落库。
     *
     * <p>抛出的任何异常都由 {@link #handleFailure} 统一处理。
     */
    private void runAnalysis(AiTask task) {
        // ── 1. 读日记 ──────────────────────────────────────────
        // 用 selectByIdAndUserId 而不是"先按 id 查再判断归属"：
        // 权限条件在 SQL 里，别人的日记这里直接查不到。
        Diary diary = diaryMapper.selectByIdAndUserId(task.getSourceId(), task.getUserId());
        if (diary == null) {
            // 日记不存在、不属于该用户、或已被软删除。
            // 这三种情况都没有重试的意义 —— 数据不会自己回来。
            // 抛一个"不可重试"语义的异常，交给 handleFailure 转成 CANCELLED。
            throw new TaskAbortedException("日记不存在或已被删除: diaryId=" + task.getSourceId());
        }

        // ── 2. 解密 ────────────────────────────────────────────
        // ⚠️ 明文只在这个方法的作用域里存在，不写入任何日志、
        //    不放进异常 message、不塞进事件对象。
        String plainContent = aesGcmUtil.decrypt(diary.getContentCiphertext());

        // ── 3. 调用 AI（AI 侧实现，可能抛各种异常）──────────────
        DiaryAnalysisResult result = cognitionService.analyze(
                task.getUserId(), diary.getId(), plainContent);

        if (result == null) {
            // 接口约定里写了"不能返回 null"。走到这里说明实现方违约了。
            // 当作失败处理而不是当成功 —— 否则任务标 SUCCESS 但前端没内容，
            // 变成静默失败，用户完全不知道为什么"AI 什么都没说"。
            throw new IllegalStateException(
                    "CognitionService.analyze 返回了 null（违反接口约定）");
        }

        // ── 4. 落库 ────────────────────────────────────────────
        DiaryAnalysis entity = new DiaryAnalysis();
        entity.setUserId(task.getUserId());
        entity.setDiaryId(diary.getId());
        entity.setSummary(result.summary());
        entity.setEmotionJson(result.emotionJson());
        entity.setTopicsJson(result.topicsJson());
        entity.setEntitiesJson(result.entitiesJson());
        entity.setRecentStateJson(result.recentStateJson());
        entity.setLongTermFactsJson(result.longTermFactsJson());
        entity.setSchemaVersion(result.schemaVersion());
        entity.setPromptVersion(result.promptVersion());
        diaryAnalysisMapper.upsert(entity);

        log.debug("分析结果已落库: diaryId={}", diary.getId());
    }

    /**
     * 处理一次执行失败：分类 → 决定是否重试 → 记账。
     *
     * <h2>⚠️ 重试次数在"失败"时累计，不是"重试"时</h2>
     *
     * <p>{@code markFailed} 里做了 {@code retry_count = retry_count + 1}。
     * 这样即使进程在"标记重试"之前崩溃，计数也已经落了库，
     * 不会出现"无限重试"。
     */
    private void handleFailure(AiTask task, Exception error, long costMs) {
        long taskId = task.getId();

        // ── 不可重试的"数据问题"：直接取消 ──────────────────────
        if (error instanceof TaskAbortedException) {
            aiTaskMapper.markCancelled(taskId, AiTaskErrorSanitizer.sanitize(error));
            log.info("任务已取消（来源不可用）: taskId={} diaryId={} reason={}",
                    taskId, task.getSourceId(), error.getMessage());
            return;
        }

        String errorCode = AiTaskErrorClassifier.classify(error);
        String errorMessage = AiTaskErrorSanitizer.sanitize(error);
        boolean retryable = AiTaskErrorClassifier.isRetryable(error);

        // 先记失败（retry_count 在这里 +1）
        aiTaskMapper.markFailed(taskId, errorCode, errorMessage);

        // 读回最新 retry_count —— markFailed 之后的权威值
        AiTask fresh = aiTaskMapper.selectByIdAndUserId(taskId, task.getUserId());
        int retryCount = fresh == null ? task.getRetryCount() + 1 : fresh.getRetryCount();
        int maxRetries = properties.getMaxRetries();

        if (!retryable) {
            // 不可重试：保持在 FAILED，等用户显式重试。
            // ⚠️ 不记 error 级别：4xx/JSON 错误是"预期内的失败"，
            //    用 warn 更合适，避免污染告警。
            log.warn("分析任务失败（不可重试）: taskId={} diaryId={} code={} costMs={}",
                    taskId, task.getSourceId(), errorCode, costMs);
            return;
        }

        if (retryCount >= maxRetries) {
            // 重试次数用尽：同样是终态 FAILED，但需要 error 级别告警，
            // 因为"反复重试都失败"通常意味着上游持续故障。
            log.error("分析任务失败且重试次数已用尽: taskId={} diaryId={} code={} "
                            + "retryCount={}/{} costMs={}",
                    taskId, task.getSourceId(), errorCode, retryCount, maxRetries, costMs);
            return;
        }

        // 转 RETRYING，等下一轮扫描捞起。
        // ⚠️ 退避是怎么生效的：这里**不睡线程**（那会占住 Worker），
        //    而是靠"下一轮扫描时这条任务的 created_at 仍然很早" ——
        //    扫描按 created_at 升序，所以它会很快被再捞到。
        //
        //    Phase 3 的取舍：不做精确的"到期时间"字段。
        //    要精确退避需要加 next_run_at 列 + 扫描条件，属于优化项。
        //    当前实现对 10-70 秒量级的重试已经够用，
        //    而且 BASE 间隔本身就是"最小等待"的近似。
        //
        //    ⚠️ 也正因如此，这个 nextDelayMs **只是日志里的估算**，
        //       不是"实际一定会等这么久"。别把它当契约用。
        aiTaskMapper.markRetrying(taskId);
        long nextDelayMs = properties.getRetryBackoffBaseMs() * (1L << (retryCount - 1));
        log.info("分析任务失败，将重试: taskId={} diaryId={} code={} retryCount={}/{} "
                        + "approxBackoffMs={}",
                taskId, task.getSourceId(), errorCode, retryCount, maxRetries, nextDelayMs);
    }

    /**
     * 表示"任务应当被取消，而不是重试"的内部控制流异常。
     *
     * <p>为什么用一个专门的异常类型而不是返回 boolean：
     * {@link #runAnalysis} 的中途有多个检查点，"中止"要能穿透到
     * {@link #handleFailure}。用异常表达最直接，也避免层层返回状态码。
     *
     * <p>它<b>不会</b>被记录成"失败"——{@code handleFailure} 见到它
     * 直接转 CANCELLED，因为"日记被删了"不是我们的错误。
     */
    static class TaskAbortedException extends RuntimeException {
        TaskAbortedException(String message) {
            super(message);
        }
    }
}
