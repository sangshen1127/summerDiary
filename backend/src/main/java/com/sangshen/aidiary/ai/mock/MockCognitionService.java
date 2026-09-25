package com.sangshen.aidiary.ai.mock;

import com.sangshen.aidiary.ai.CognitionService;
import com.sangshen.aidiary.ai.DiaryAnalysisResult;
import com.sangshen.aidiary.config.AiAnalysisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link CognitionService} 的 Mock 实现 —— 没有 API Key 时也能跑通整条链路。
 *
 * <h2>⚠️ 它存在的意义（开发文档 §10.2）</h2>
 *
 * <blockquote>
 * 在 {@code AI_ANALYSIS_ENABLED=false} 或测试 profile 下，
 * Mock 返回符合文档 §5.3 schema 的<b>固定合法 JSON</b>。
 * <br>这意味着：Phase 0 到 Phase 3 你完全可以独立完成并验收，
 * <b>不依赖同学的进度</b>。
 * </blockquote>
 *
 * <p>所以这个类<b>不是</b>"临时占位、将来要删"——它是长期存在的：
 * 别人 clone 项目、CI 跑测试、演示环境，都需要在没有任何 API Key
 * 的情况下让"保存日记 → 入队 → 执行 → 状态变 SUCCESS"跑通。
 *
 * <h2>⚠️ 启用条件：为什么是 {@code havingValue="false"} 而不是 missingBean</h2>
 *
 * <p>我第一版想用 {@code @ConditionalOnMissingBean}（"没有真实现时才用 Mock"），
 * 但它在 {@code @Component} 上的**处理顺序不可靠** ——
 * 该注解在自动配置类里才保证顺序，普通组件的扫描顺序不确定，
 * 可能出现在真实现注册之前就判定"没有"，导致两个 Bean 同时存在。
 *
 * <p>改用显式开关，两边条件互斥且不依赖顺序：
 *
 * <table border="1">
 *   <caption>Bean 启用矩阵</caption>
 *   <tr><th>{@code app.ai.use-mock-when-no-key}</th><th>生效的实现</th></tr>
 *   <tr><td>未配置 / true（默认）</td><td><b>本 Mock</b></td></tr>
 *   <tr><td>false</td><td>AI 侧的真实实现</td></tr>
 * </table>
 *
 * <p>也就是说：**Mock 是默认实现，真实实现通过把开关设为 false 来接管**。
 * 这与 {@code application-ai.yml} 里 {@code use-mock-when-no-key: true}
 * 的默认值一致。
 *
 * <h2>⚠️ 这里写错过一次：{@code havingValue} 曾经是 {@code "false"}</h2>
 *
 * <p>后果非常隐蔽，值得记下来：
 * <ul>
 *   <li>默认配置（{@code use-mock-when-no-key: true}）下本类<b>不生效</b>，
 *       而 {@code AiTaskWorker} 只在 {@code analysis-enabled=true} 时才创建。
 *       两者叠加的结果是：<b>默认启动完全正常</b>（没有 Worker 也就不需要
 *       {@code CognitionService}），直到你把 {@code AI_ANALYSIS_ENABLED}
 *       打开做验收时，才在启动阶段炸出
 *       {@code NoSuchBeanDefinitionException: CognitionService}</li>
 *   <li>也就是说，这个错误<b>只在"要真正跑 AI 链路"的那一刻</b>才现形，
 *       前面的编译、单测、Phase 1/2 验收<b>全部照常通过</b></li>
 * </ul>
 *
 * <p>教训：{@code @ConditionalOnProperty} 的 {@code havingValue} 与
 * 属性名里的语义方向（{@code use-mock-<b>when</b>-no-key}）很容易读反。
 * 判断方法只有一个：把属性值代入注解，看条件表达式到底成不成立 ——
 * 不要靠"读起来像"。本项目的开关矩阵已在上表列出，改动前先对表。
 *
 * <h2>⚠️ 返回的 JSON 必须"合法且形状正确"</h2>
 *
 * <p>如果 Mock 返回的 JSON 形状和真实模型不一致，
 * 前端在 Mock 环境下能跑、切到真模型就崩 —— 那 Mock 就失去了意义。
 * 所以这里的字段严格对齐 AI Agent 文档 §5.3 的 schema，
 * 并且**故意填了非空值**（空数组/空串无法验证"字段被正确解析"）。
 */
@Component
@ConditionalOnProperty(name = "app.ai.use-mock-when-no-key",
        havingValue = "true", matchIfMissing = true)
public class MockCognitionService implements CognitionService {

    private static final Logger log = LoggerFactory.getLogger(MockCognitionService.class);

    /**
     * Mock 返回的 schema 版本。与 AI Agent 文档 §5.3 对齐。
     */
    private static final String SCHEMA_VERSION = "1.0";

    /** 本 Mock 声称使用的 Prompt 版本，写进任务表便于识别"这是 Mock 产出的"。 */
    private static final String PROMPT_VERSION = "mock-v1";

    /** 验收开关取值：正常成功。 */
    private static final String OUTCOME_SUCCESS = "success";

    /** 验收开关取值：假装模型读超时（可重试）。 */
    private static final String OUTCOME_TIMEOUT = "timeout";

    /** 验收开关取值：假装模型返回的 JSON 解析不了（不可重试）。 */
    private static final String OUTCOME_INVALID_JSON = "invalid-json";

    private final AiAnalysisProperties properties;

    public MockCognitionService(AiAnalysisProperties properties) {
        this.properties = properties;
    }

    @Override
    public DiaryAnalysisResult analyze(Long userId, Long diaryId, String content) {
        // ⚠️ 只记 ID，绝不记 content（§5.4 红线）。
        //    content 是明文正文，进日志就是泄露。
        log.debug("Mock 分析: userId={} diaryId={} contentLength={} outcome={}",
                userId, diaryId, content == null ? 0 : content.length(),
                properties.getMockOutcome());

        // ── 验收专用的失败模拟 ──────────────────────────────────
        // ⚠️ 抛出的异常类型是【刻意挑选】的，不是随便 new 一个 RuntimeException：
        //    AiTaskErrorClassifier 靠异常类型（会穿透 cause 链）判断
        //    "该不该重试"，随便抛一个只会被归成 UNKNOWN（不可重试），
        //    那样就验证不到"指数退避重试 3 次"这条路径了。
        String outcome = properties.getMockOutcome();
        if (OUTCOME_TIMEOUT.equals(outcome)) {
            // 包装成 RuntimeException：CognitionService 的签名不声明受检异常。
            // 这正好也验证了分类器"能穿透 cause 链"的假设 ——
            // 真实 HTTP 客户端也普遍这样包装。
            throw new RuntimeException("Mock: 模拟模型读超时（read timeout 30000ms）",
                    new java.net.SocketTimeoutException("Mock: read timeout"));
        }
        if (OUTCOME_INVALID_JSON.equals(outcome)) {
            // 类名里带 Json 是刻意的：分类器按类名判定 JSON 解析类失败。
            throw new MockJsonParseException("Mock: 模拟模型返回的 JSON 无法解析");
        }

        // ── 验收专用：假装模型很慢 ──────────────────────────────
        // ⚠️ 用 Thread.sleep 是**故意的**：它模拟的正是"一次阻塞的模型调用"，
        //    而这正是异步架构要隔离掉的东西。放在这里（而不是加延迟到接口层）
        //    才能真实地验证"保存日记没有被它拖住"。
        //
        //    它运行在 Worker 线程（spring.task.execution 的 ai-task-* 线程池），
        //    **不会**占用请求线程 —— 这一点很重要，如果哪天它跑到了请求线程上，
        //    "保存日记变慢"会立刻在验收里暴露出来。
        long delay = properties.getMockDelayMs();
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                // 恢复中断标志：吞掉中断会让"应用正在关闭"这类信号丢失
                Thread.currentThread().interrupt();
                throw new RuntimeException("Mock: 等待模拟延迟时被中断", e);
            }
        }

        return new DiaryAnalysisResult(
                SCHEMA_VERSION,
                "这是 Mock 生成的摘要，用于在没有 API Key 时验证链路。",
                // emotion：对象形式，验证前端能否解析嵌套结构
                "{\"label\":\"平静\",\"score\":0.5,\"evidence\":\"Mock 数据\"}",
                // topics：数组形式，验证前端能否解析列表
                "[\"Mock\",\"链路验证\"]",
                // entities：空数组（真实场景也常为空，要能处理）
                "[]",
                "[]",
                "[]",
                PROMPT_VERSION);
    }

    /**
     * 模拟"模型返回的 JSON 解析不了"。
     *
     * <p>单独建一个类型（而不是用现成的 {@code JsonParseException}）有两个原因：
     * <ol>
     *   <li>不引入 AI 侧的 JSON 库依赖 —— Mock 应该能在最小依赖下工作</li>
     *   <li>类名里带 {@code Json}，正好命中
     *       {@code AiTaskErrorClassifier} 按类名的判定规则，
     *       于是能得到 {@code JSON_INVALID} 这个真实会出现的错误码</li>
     * </ol>
     */
    static class MockJsonParseException extends RuntimeException {
        MockJsonParseException(String message) {
            super(message);
        }
    }
}
