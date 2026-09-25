package com.sangshen.aidiary.mapper;

import com.sangshen.aidiary.entity.AiTask;
import com.sangshen.aidiary.entity.enums.AiTaskStatus;
import com.sangshen.aidiary.entity.enums.AiTaskTypes.SourceType;
import com.sangshen.aidiary.entity.enums.AiTaskTypes.TaskType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 异步任务 Mapper。
 *
 * <p>SQL 写在 {@code resources/mapper/AiTaskMapper.xml}。
 *
 * <h2>⚠️ 并发安全：Worker 抢占任务必须用原子 UPDATE</h2>
 *
 * <p>Worker 会周期性扫描待办任务。如果实现成：
 * <pre>{@code
 * // ❌ 危险：先查后改，两个 Worker 会抢到同一条
 * List<AiTask> tasks = mapper.selectPending(10);
 * for (AiTask t : tasks) { mapper.updateStatus(t.getId(), RUNNING); run(t); }
 * }</pre>
 * 那么两个 Worker 实例（或多线程）可能同时查到同一条任务，
 * 都调用模型 —— 结果是<b>重复计费 + 重复写分析结果</b>。
 *
 * <p>正确做法是"抢占"：用一个带条件与行数判断的 UPDATE，
 * 只有真正把状态从 PENDING/RETRYING 改成 RUNNING 的那个调用者
 * 才算抢到（返回 1），其余返回 0 直接跳过。
 * 见 {@link #claimForRunning}。
 *
 * <h2>⚠️ 所有方法都必须带 userId（除了 Worker 用的扫描方法）</h2>
 *
 * <p>前端能访问的查询（{@link #selectByIdAndUserId}）必须带 userId，
 * 防止用别人的 taskId 探测"这个任务存在吗"。
 *
 * <p>而 {@link #selectClaimable} 是 <b>Worker 专用</b>：它要跨用户
 * 扫描全局待办，所以刻意不带 userId —— 这一点在方法注释里写明，
 * 避免被误用到 Controller 路径上。
 */
@Mapper
public interface AiTaskMapper {

    /**
     * 插入任务。
     *
     * <p>❌ <b>不加</b> {@code ON DUPLICATE KEY UPDATE}，也不做
     * "先查再插"。原因见 {@link #insertIgnoreDuplicate} ——
     * 幂等由唯一索引 + 专门的忽略重复方法负责，职责分开更清晰。
     *
     * @param task 任务实体（id 会被回填）
     * @return 影响行数，正常为 1；幂等键重复时会抛 DuplicateKeyException
     */
    int insert(AiTask task);

    /**
     * 插入任务，若幂等键已存在则**静默忽略**（返回 0）。
     *
     * <p>这是入队路径应该用的方法。为什么不直接 try/catch
     * {@code DuplicateKeyException}：
     * <ul>
     *   <li>MySQL 的 {@code INSERT IGNORE} 把"重复"变成正常的 0 行，
     *       不需要用异常做流程控制（异常有栈开销，且容易被误当故障）</li>
     *   <li>调用方（事件监听器）本来就在事务里，
     *       捕获异常在某些隔离级别下会让事务进入回滚态</li>
     * </ul>
     *
     * <p>⚠️ {@code INSERT IGNORE} 会**连同其他警告一起忽略**
     * （比如字段超长被截断）。所以表结构必须严格 ——
     * 这也是为什么 DDL 里所有 NOT NULL 字段都给了默认值、
     * VARCHAR 长度留足余量。
     *
     * @param task 任务实体
     * @return 1 = 新插入；0 = 幂等键已存在，已忽略
     */
    int insertIgnoreDuplicate(AiTask task);

    /**
     * 按 ID + 归属查询任务。
     *
     * <p>「不存在」与「不属于当前用户」都返回 {@code null}，
     * Service 统一转 40401，不泄露存在性。
     *
     * @param id     任务 ID
     * @param userId 当前用户 ID
     * @return 任务，不存在或不属于该用户时返回 null
     */
    AiTask selectByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 按「来源」查任务（某篇日记的分析任务）。
     *
     * <p>前端详情页轮询用这个：它知道 diaryId，不一定知道 taskId。
     *
     * @param userId     当前用户 ID
     * @param sourceType 来源类型
     * @param sourceId   来源 ID
     * @param taskType   任务类型
     * @return 任务，不存在时返回 null
     */
    AiTask selectBySource(@Param("userId") Long userId,
                          @Param("sourceType") SourceType sourceType,
                          @Param("sourceId") Long sourceId,
                          @Param("taskType") TaskType taskType);

    /**
     * 扫描可执行的任务（<b>Worker 专用，刻意不带 userId</b>）。
     *
     * <p>Worker 要处理的是全局待办，所以这里跨用户查询。
     * <b>绝不能</b>把它用在 Controller 的查询路径上 ——
     * 那样任何登录用户都能看到别人的任务。
     *
     * <p>只取 {@code PENDING} 与 {@code RETRYING}（等待重试的）。
     * 按 {@code created_at} 升序 = 先来先服务，避免早入队的任务饿死。
     * 用 {@code LIMIT} 分批，防止一次捞太多把内存打满。
     *
     * @param limit 单批上限
     * @return 待执行任务列表
     */
    List<AiTask> selectClaimable(@Param("limit") int limit);

    /**
     * <b>原子抢占</b>一条任务：把状态从 PENDING/RETRYING 改成 RUNNING。
     *
     * <p>返回 1 表示抢到了（可以放心执行）；返回 0 表示
     * 已被别的 Worker 抢走、或状态已变 —— 调用方必须跳过。
     *
     * <p>为什么把 {@code fromStatuses} 写进 WHERE 而不是在 Java 里判断：
     * 判断和更新分成两步就会有竞态窗口。放进一条 UPDATE 里，
     * 由数据库的行锁保证"只有一个赢家"。
     *
     * <p>同时写入 {@code started_at}（本次执行的开始时间），
     * 便于排查"任务卡在 RUNNING 多久了"。
     *
     * @param id           任务 ID
     * @param fromStatuses 允许被抢占的当前状态（PENDING / RETRYING）
     * @return 1 = 抢占成功；0 = 没抢到
     */
    int claimForRunning(@Param("id") Long id,
                        @Param("fromStatuses") List<AiTaskStatus> fromStatuses);

    /**
     * 标记任务成功。
     *
     * <p>用条件更新（要求当前是 RUNNING），防止"任务被取消后
     * 迟到的执行结果又把它改成 SUCCESS"。
     *
     * @param id            任务 ID
     * @param promptVersion 本次实际使用的 Prompt 版本（便于追溯）
     * @return 影响行数
     */
    int markSuccess(@Param("id") Long id, @Param("promptVersion") String promptVersion);

    /**
     * 标记任务失败，并记录已脱敏的错误信息。
     *
     * <p>⚠️ {@code errorMessage} 由调用方保证已脱敏、已截断
     * （见 {@code AiTaskErrorSanitizer}）。这里<b>不做</b>校验 ——
     * 但数据库列只有 500 字符，超长会被 MySQL 拒绝，
     * 所以调用方必须截断（这是有意的"让漏截断立刻报错"设计）。
     *
     * <p>同时把 {@code retry_count} 加 1 —— 重试次数在"失败"时计数，
     * 而不是在"发起重试"时计数，这样即使 Worker 崩溃也不会漏计。
     *
     * @param id           任务 ID
     * @param errorCode    错误类别码
     * @param errorMessage 已脱敏的简短原因
     * @return 影响行数
     */
    int markFailed(@Param("id") Long id,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage);

    /**
     * 把失败的任务转成"等待重试"。
     *
     * @param id 任务 ID
     * @return 影响行数
     */
    int markRetrying(@Param("id") Long id);

    /**
     * 标记任务已取消（来源已被删除，没必要再跑）。
     *
     * @param id 任务 ID
     * @return 影响行数
     */
    int markCancelled(@Param("id") Long id, @Param("reason") String reason);

    /**
     * 把任务重置回 PENDING，用于「用户点重新分析」。
     *
     * <p>⚠️ 刻意<b>复用同一条任务记录</b>而不是新建：
     * 幂等键唯一约束决定了同一个"来源+类型+版本"只能有一条任务。
     * 重置时清空错误信息与重试次数，让它像新任务一样被 Worker 捞起。
     *
     * <p>条件 {@code status IN (FAILED, SUCCESS, CANCELLED)} 保证：
     * 正在 RUNNING 的任务不会被重复触发（那会变成两个 Worker 同时跑）。
     *
     * <p>CANCELLED 也允许重置的理由：取消的原因是"来源（日记）不见了"。
     * 若日记之后又可见，用户点「重新分析」应当真的重排；排除它的话
     * UPDATE 会返回 0 行，而前端因为 {@code can_retry=true} 仍显示按钮 ——
     * 变成一个点了没反应的死按钮。
     *
     * @param id 任务 ID
     * @return 1 = 已重置；0 = 当前状态不允许重置（如正在运行）
     */
    int resetForRetry(@Param("id") Long id);

    /**
     * 统计某用户某状态的任务数（诊断/看板用）。
     */
    long countByUserAndStatus(@Param("userId") Long userId,
                              @Param("status") AiTaskStatus status);
}
