package com.sangshen.aidiary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 模块配置绑定（{@code app.ai.*}），对应 {@code application-ai.yml}。
 *
 * <h2>⚠️ 本类只绑定「框架需要的那几个开关」，不绑定 AI 侧的全部配置</h2>
 *
 * <p>{@code application-ai.yml} 由 AI 模块负责人主改（文件头已注明）。
 * 我这边只读三个东西：
 * <ul>
 *   <li>{@link #analysisEnabled} —— 决定"要不要为日记创建分析任务"</li>
 *   <li>{@link #promptVersion} —— 写进任务的 prompt_version，便于追溯</li>
 *   <li>{@link #maxRetries} —— 重试上限（原样透传给 Worker）</li>
 * </ul>
 *
 * <p>其余（chat / embedding / vector / reliability 的细分项）由 AI 侧
 * 自己的配置类负责。**刻意不给整个 {@code app.ai} 建一个大而全的绑定类** ——
 * 那样每次 AI 侧加配置我这边都要重新编译，而两边的修改节奏是不同的。
 *
 * <h2>⚠️ 发现的文档不一致（记录在案）</h2>
 *
 * <p>开发文档 §10.2 的 Mock 示例写的是
 * {@code @ConditionalOnProperty(name = "ai.analysis.enabled", ...)}，
 * 但真实配置是 {@code app.ai.analysis-enabled}（见 application-ai.yml）。
 * 本类以**配置文件为准**。这条差异已在变更记录里指出，避免同学按文档写时踩空。
 *
 * <h2>为什么 {@link #analysisEnabled} 兜底值是 false</h2>
 *
 * <p>与 {@code application-ai.yml} 保持一致：没有 API Key 的人 clone 下来
 * 也能正常启动、正常写日记，只是不产生分析任务（前端显示「AI 未启用」）。
 * 这是刻意的默认值 —— 保证「不接 AI 也能走完日记闭环」这条 Phase 2 的标准
 * 在 Phase 3 之后依然成立。
 */
@ConfigurationProperties(prefix = "app.ai")
public class AiAnalysisProperties {

    /**
     * 总开关。false 时不创建新的分析任务。
     *
     * <p>⚠️ 注意"不创建任务"与"不执行任务"是两件事：
     * 关闭后已经入队的任务<b>仍会被 Worker 执行</b>（否则关开关会留下
     * 永远卡在 PENDING 的记录）。判断在<b>入队时</b>做，不在执行时做。
     */
    private boolean analysisEnabled = false;

    /**
     * 日记分析所用的 Prompt 版本，写进 {@code ai_task.prompt_version}。
     *
     * <p>用途：排查"同一篇日记两次分析结果不同"时，能看出是不是换了 Prompt。
     */
    private String promptVersion = "v1";

    /**
     * 单任务最大重试次数。
     *
     * <p>与 {@code app.ai.reliability.max-retries}（{@code AI_MAX_RETRIES}）
     * 是同一个值的两种读法 —— 这里给它一个带默认值的独立入口，
     * 是为了让 Worker 不必依赖 reliability 的完整绑定。
     * 默认 3 取自 AI Agent 文档 §8.2。
     */
    private int maxRetries = 3;

    /**
     * Worker 每轮最多处理多少条任务。
     *
     * <p>限流用：一次捞太多会把线程池和数据库连接池打满。
     * 与"扫描批次大小"（selectClaimable 的 limit）分开配置 ——
     * 那个是单次 SQL 的上限，这个是每轮的总处理量。
     */
    private int workerBatchSize = 5;

    /**
     * ⚠️ <b>验收专用</b>：让 Mock 假装模型失败，用来验证失败链路。
     *
     * <h2>为什么需要一个"故意失败"的开关</h2>
     *
     * <p>Phase 3 的验收标准是：
     * <blockquote>
     * 模型超时/报错时<b>正文照样保存成功</b>，任务标 FAILED 可重试。
     * </blockquote>
     *
     * <p>没有 API Key 的时候，"让模型超时"这件事本身做不到 ——
     * 于是这条<b>最重要的</b>验收标准反而无法验证。
     * 本开关让 {@code MockCognitionService} 主动抛出与真实失败
     * 同类型的异常（超时 / JSON 不合法），从而把整条链路
     * （分类 → 重试 → 脱敏 → 前端展示）跑通。
     *
     * <h2>取值</h2>
     * <table border="1">
     *   <caption>mock-outcome 的取值</caption>
     *   <tr><th>值</th><th>Mock 的行为</th><th>用来验证</th></tr>
     *   <tr><td>{@code success}（默认）</td><td>返回固定合法 JSON</td>
     *       <td>正常链路</td></tr>
     *   <tr><td>{@code timeout}</td><td>抛带 {@code SocketTimeoutException} 的异常</td>
     *       <td>可重试失败：退避重试 3 次后终态 FAILED</td></tr>
     *   <tr><td>{@code invalid-json}</td><td>抛 {@code MockJsonParseException}</td>
     *       <td>不可重试失败：立刻 FAILED，避免白烧额度</td></tr>
     * </table>
     *
     * <p>⚠️ 环境变量 {@code AI_MOCK_OUTCOME}。生产环境必须保持
     * {@code success} —— 否则所有分析都会"莫名其妙失败"。
     * 认不出的值一律当 {@code success} 处理（打错字不至于让功能全挂）。
     */
    private String mockOutcome = "success";

    /**
     * ⚠️ <b>验收专用</b>：Mock 假装"模型很慢"，睡这么多毫秒再返回。
     *
     * <h2>为什么需要它（默认 0，即不睡）</h2>
     *
     * <p>Mock 是不耗时的，这反而让两条重要的验证做不了：
     * <ol>
     *   <li><b>"保存日记不等待模型"</b>：Mock 瞬间返回，于是"同步调模型"
     *       与"异步执行"在耗时上<b>看不出区别</b> ——
     *       一个真的写成同步调用的实现也能通过测试</li>
     *   <li><b>前端"分析中"界面</b>：状态在毫秒级内从 pending 变 success，
     *       轮询、转圈、按钮禁用这些分支根本来不及出现，
     *       浏览器验收也就看不到它们</li>
     * </ol>
     *
     * <p>设成 1500 之后：保存日记仍然 &lt; 1 秒返回（证明没有等模型），
     * 而任务要 1.5 秒后才变 SUCCESS —— 中间态可以被稳定观察到。
     *
     * <p>⚠️ 只影响 Mock。接上真实模型后这个值没有任何作用。
     */
    private long mockDelayMs = 0L;

    /**
     * 指数退避的基准间隔（毫秒）。
     *
     * <p>第 n 次重试等待 {@code base * 2^(n-1)}：默认 10s → 20s → 40s
     * （配合 {@code maxRetries=3}，总跨度约 70 秒）。
     *
     * <h2>为什么做成配置项</h2>
     *
     * <p>它原来是个常量，注释里写着"验收时想观察重试就得调它，但只能改代码"。
     * 变成配置后，验收脚本可以直接把它设成 500ms ——
     * 于是"退避重试 3 次后终态 FAILED"这条链路能在几秒内跑完，
     * 而不是让验收的人干等 70 秒（等待时间一长，人就会跳过这条用例，
     * 而那恰恰是最该验证的一条）。
     *
     * <p>⚠️ 生产环境不要调得太小：退避的意义就是"别在上游故障时
     * 继续加压"。默认 10 秒是给单机小项目用的温和值。
     */
    private long retryBackoffBaseMs = 10_000L;

    public boolean isAnalysisEnabled() {
        return analysisEnabled;
    }

    public void setAnalysisEnabled(boolean analysisEnabled) {
        this.analysisEnabled = analysisEnabled;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getWorkerBatchSize() {
        return workerBatchSize;
    }

    public void setWorkerBatchSize(int workerBatchSize) {
        this.workerBatchSize = workerBatchSize;
    }

    public String getMockOutcome() {
        return mockOutcome;
    }

    public void setMockOutcome(String mockOutcome) {
        this.mockOutcome = mockOutcome;
    }

    public long getRetryBackoffBaseMs() {
        return retryBackoffBaseMs;
    }

    public void setRetryBackoffBaseMs(long retryBackoffBaseMs) {
        this.retryBackoffBaseMs = retryBackoffBaseMs;
    }

    public long getMockDelayMs() {
        return mockDelayMs;
    }

    public void setMockDelayMs(long mockDelayMs) {
        this.mockDelayMs = mockDelayMs;
    }

    /** 只报开关状态，不含任何密钥/正文。 */
    public String toSafeString() {
        return "AiAnalysisProperties{analysisEnabled=" + analysisEnabled
                + ", promptVersion='" + promptVersion + '\''
                + ", maxRetries=" + maxRetries
                + ", workerBatchSize=" + workerBatchSize
                + ", mockOutcome='" + mockOutcome + '\''
                + ", mockDelayMs=" + mockDelayMs
                + ", retryBackoffBaseMs=" + retryBackoffBaseMs
                + '}';
    }
}
