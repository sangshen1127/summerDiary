package com.sangshen.aidiary.entity.enums;

import java.util.Set;

/**
 * 异步任务状态机。
 *
 * <h2>合法状态流转</h2>
 *
 * <pre>
 *   PENDING ──► RUNNING ──► SUCCESS
 *                  │
 *                  └──► FAILED ──► RETRYING ──► RUNNING   （重试）
 *                                      │
 *                                      └──► FAILED        （超过最大重试次数）
 *
 *   PENDING ──► CANCELLED
 *   RUNNING ──► CANCELLED
 * </pre>
 *
 * <h2>⚠️ 为什么状态流转要写成代码而不是靠约定</h2>
 *
 * <p>"PENDING 的下一步只能是 RUNNING 或 CANCELLED" 这类规则，
 * 如果只写在文档里，实现时很容易写出
 * {@code task.setStatus(SUCCESS)} 直接从 PENDING 跳到 SUCCESS ——
 * 编译通过、测试可能也过（如果没测这条），但状态机已经破了。
 *
 * <p>所以把"允许的下一步"变成 {@link #canTransitionTo}，
 * Worker 每次改状态都经过它。这样非法流转会立刻抛异常，
 * 而不是让任务表里出现一个语义不明的中间态。
 *
 * <h2>⚠️ FAILED 与 RETRYING 的区别（容易搞混）</h2>
 *
 * <ul>
 *   <li>{@code FAILED} —— <b>本次执行</b>失败了，但可能还会重试。
 *       Worker 把它当作"待重试的失败"。</li>
 *   <li>{@code RETRYING} —— 已经决定要重试，等待下一轮被捞起来。
 *       它存在的意义是让"正在等重试"和"刚失败还没处理"可区分，
 *       便于排查"任务卡住了"。</li>
 * </ul>
 *
 * <p>两者最终都可能变成 {@code FAILED} 且不再流转 —— 那就是
 * "重试次数耗尽，等用户显式重试"的终态。终态是<b>同一个</b>
 * {@code FAILED}，靠 {@code retry_count >= maxRetries} 区分
 * "还会重试"和"已放弃"。刻意不新增 {@code ABANDONED} 状态：
 * 状态越少，前端和排查时的心智负担越小。
 */
public enum AiTaskStatus {

    /** 已入队，等待执行。 */
    PENDING,

    /** 正在执行。 */
    RUNNING,

    /** 执行成功。终态。 */
    SUCCESS,

    /** 执行失败。可能还会重试，也可能已放弃（看 retry_count）。 */
    FAILED,

    /** 等待重试。 */
    RETRYING,

    /** 已取消。终态。用于"来源已被删除，任务没必要再跑"。 */
    CANCELLED;

    /** 不会再变化的终态。 */
    private static final Set<AiTaskStatus> TERMINAL = Set.of(SUCCESS, CANCELLED);

    /**
     * 是否是终态（不会再流转）。
     *
     * <p>⚠️ {@code FAILED} <b>不在</b>终态集合里 —— 它可能被重试。
     * 判断"彻底结束"要用 {@link #isTerminal()} 或结合 retry_count。
     */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /**
     * 校验一次状态流转是否合法。
     *
     * <p><b>刻意不把 FAILED 的"放弃"写进这里</b>：状态机只关心
     * "结构上允许的流转"，而"次数用尽了没有"是 Worker 的业务判断
     * （它需要读 maxRetries 配置）。把两者混在一起会让状态机依赖配置。
     *
     * @param target 目标状态
     * @return 是否允许
     */
    public boolean canTransitionTo(AiTaskStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case PENDING -> target == RUNNING || target == CANCELLED;
            // RUNNING 可以到 SUCCESS / FAILED / CANCELLED。
            // 注意 RUNNING -> RETRYING 不直接允许：先标 FAILED 记录本次失败原因，
            // 再由 Worker 决定是否转 RETRYING —— 这样"每次失败的痕迹"都留在表里。
            case RUNNING -> target == SUCCESS || target == FAILED || target == CANCELLED;
            case FAILED -> target == RETRYING || target == RUNNING;
            case RETRYING -> target == RUNNING || target == CANCELLED;
            // 终态不再流转
            case SUCCESS, CANCELLED -> false;
        };
    }

    /**
     * 校验流转，非法则抛异常。
     *
     * <p>用"抛异常"而不是"返回 false 让调用方处理"：
     * 非法流转是<b>编码错误</b>，不是可恢复的业务情况。
     * 让它响亮地失败，比静默把状态写成非法值好得多。
     *
     * @param target 目标状态
     * @throws IllegalStateException 流转非法
     */
    public void requireTransitionTo(AiTaskStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException(
                    "非法的任务状态流转: " + this + " -> " + target
                            + "（合法目标见 AiTaskStatus.canTransitionTo）");
        }
    }
}
