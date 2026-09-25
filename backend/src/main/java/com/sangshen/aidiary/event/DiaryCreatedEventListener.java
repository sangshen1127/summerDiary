package com.sangshen.aidiary.event;

import com.sangshen.aidiary.config.AiAnalysisProperties;
import com.sangshen.aidiary.entity.AiTask;
import com.sangshen.aidiary.entity.enums.AiTaskTypes.SourceType;
import com.sangshen.aidiary.entity.enums.AiTaskTypes.TaskType;
import com.sangshen.aidiary.mapper.AiTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 日记创建后，为它入队一条分析任务。
 *
 * <h2>⚠️⚠️ 这是整个异步链路的起点，两个注解都不能改</h2>
 *
 * <h3>{@code @TransactionalEventListener(phase = AFTER_COMMIT)} 为什么必须是 AFTER_COMMIT</h3>
 *
 * <p>默认的 {@code @EventListener} 在<b>事务提交之前</b>就执行了。
 * 如果那样用，会出两个问题：
 * <ol>
 *   <li><b>Worker 可能读到未提交的数据</b>：任务已经入队，Worker 立刻捞走，
 *       但它要读的那篇日记还没提交 —— 读到 null，任务失败</li>
 *   <li><b>日记回滚了，任务却留下了</b>：事务后续失败时日记被回滚，
 *       事件却已经发出去了，任务指向一个不存在的日记</li>
 * </ol>
 *
 * <p>AFTER_COMMIT 保证：只有日记<b>真的存下来了</b>，才会入队任务。
 *
 * <h3>为什么用 {@code @Async}（以及不用它会怎样）</h3>
 *
 * <p>入队只是一次 INSERT，很快。但 {@code @Async} 仍然必要，因为：
 * <ul>
 *   <li>AFTER_COMMIT 的监听器是在<b>提交线程上同步执行</b>的。
 *       若将来这里加了稍重的逻辑（比如批量入队多个任务），
 *       会直接拖慢用户的"保存日记"响应</li>
 *   <li>它执行时原事务已提交，新事务（REQUIRES_NEW）独立 ——
 *       入队失败不会影响已经存好的日记</li>
 * </ul>
 *
 * <p>⚠️ {@code @Async} 需要 {@code @EnableAsync} 才生效。本项目的
 * {@code AiDiaryApplication} 上有这个注解（Phase 3 加入），
 * 并且配置了专用线程池（{@code spring.task.execution}，
 * 线程名前缀 {@code ai-task-}）。
 * <b>如果漏了 {@code @EnableAsync}，本方法会退化成同步执行</b> ——
 * 不报错，只是"保存日记"的响应变慢。这类静默退化很难发现。
 *
 * <h2>为什么注入 Mapper 而不是 Service</h2>
 *
 * <p>入队这一步没有业务规则（不涉及权限校验、不需要查日记），
 * 就是一条 INSERT。走 Service 会多一层无意义的转发。
 * 需要业务逻辑的是 Worker（它要判断可重试性等），不是这里。
 */
@Component
public class DiaryCreatedEventListener {

    private static final Logger log = LoggerFactory.getLogger(DiaryCreatedEventListener.class);

    private final AiTaskMapper aiTaskMapper;
    private final AiAnalysisProperties properties;

    public DiaryCreatedEventListener(AiTaskMapper aiTaskMapper,
                                     AiAnalysisProperties properties) {
        this.aiTaskMapper = aiTaskMapper;
        this.properties = properties;
    }

    /**
     * 消费日记创建事件：入队一条 {@code DIARY_ANALYZE} 任务。
     *
     * <h2>⚠️ 关掉 AI 开关时不入队</h2>
     *
     * <p>"用户关闭 AI 分析后不再创建新认知任务"是《我的职责与任务清单》
     * 第 185 行的明确要求。判断放在<b>入队时</b>而不是执行时 ——
     * 否则关开关会在任务表里留下永远 PENDING 的记录。
     *
     * <h2>⚠️ 幂等：同一篇日记只入队一次</h2>
     *
     * <p>幂等键 = {@code userId + sourceType + sourceId + taskType + version}。
     * 用 {@code insertIgnoreDuplicate}（INSERT IGNORE），重复时返回 0 行而非报错。
     *
     * <p>这意味着<b>编辑日记不会重新触发分析</b>（幂等键没变）。
     * 这是 Phase 3 的刻意取舍：避免用户反复编辑时反复烧模型的额度。
     * 需要重新分析时走显式接口（{@code POST /api/ai/diaries/{id}/analyze}），
     * 那会重置同一条任务而不新建。
     *
     * <h2>为什么异常只记日志、不往外抛</h2>
     *
     * <p>此时日记<b>已经提交成功</b>了。如果这里抛异常：
     * <ul>
     *   <li>日记不会回滚（事务已结束），用户看到的是"保存成功"</li>
     *   <li>但堆栈会一路往上冒到线程池，成为一条难懂的异步异常日志</li>
     *   <li>更糟：如果将来有人加了 {@code @Transactional} 到调用链上，
     *       可能造成"看起来保存失败"的误判</li>
     * </ul>
     *
     * <p>所以这里 catch 住，记 error 日志（带 diaryId 便于补偿）。
     * <b>"入队失败"的正确表现是"这篇日记暂时没有分析结果"，
     * 而不是"日记保存失败"。</b>
     *
     * @param event 日记创建事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDiaryCreated(DiaryCreatedEvent event) {
        if (!properties.isAnalysisEnabled()) {
            // debug 级别：这是正常配置下的常规路径，不是异常
            log.debug("AI 分析未启用，跳过入队: diaryId={}", event.diaryId());
            return;
        }

        try {
            // ⚠️ 任务的全部字段（状态、重试次数、幂等键、版本）都由
            //    AiTask.newPending 统一构造 —— 入队有两个入口
            //    （这里 + 用户点「重新分析」），字段规则只能有一处定义。
            AiTask task = AiTask.newPending(
                    event.userId(), SourceType.DIARY, event.diaryId(),
                    TaskType.DIARY_ANALYZE, properties.getPromptVersion());

            int inserted = aiTaskMapper.insertIgnoreDuplicate(task);

            if (inserted == 1) {
                // 只记 ID，不记日记内容（§5.4 红线）
                log.info("已入队日记分析任务: taskId={} userId={} diaryId={}",
                        task.getId(), event.userId(), event.diaryId());
            } else {
                // 幂等命中：同一篇日记已经有一条任务了。
                // 这不是错误 —— 常见于"日记保存后重试了整条请求"。
                log.debug("分析任务已存在，跳过入队: userId={} diaryId={}",
                        event.userId(), event.diaryId());
            }
        } catch (Exception e) {
            // ⚠️ 绝不打印 event/正文；只记录定位所需的 ID
            log.error("入队日记分析任务失败（日记已保存成功，仅分析任务缺失）: "
                            + "userId={} diaryId={} errorType={}",
                    event.userId(), event.diaryId(), e.getClass().getSimpleName(), e);
        }
    }
}
