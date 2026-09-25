package com.sangshen.aidiary.config;

import com.sangshen.aidiary.service.ai.AiTaskWorker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 把 AI 子系统的开关状态暴露到 {@code /actuator/health}。
 *
 * <h2>它解决的具体问题：验收时"不知道后端跑在哪种模式"</h2>
 *
 * <p>Phase 3 的验收需要后端以不同配置启动三次：
 * <pre>
 *   AI_ANALYSIS_ENABLED=false            → AI 关闭（默认）
 *   AI_ANALYSIS_ENABLED=true             → 正常分析（Mock 成功）
 *   ...=true + AI_MOCK_OUTCOME=timeout   → 验证失败与重试
 * </pre>
 *
 * <p>这三种模式下接口的表现完全不同（"尚未分析" / "分析成功" / "分析失败"）。
 * 如果验收脚本不知道该跑哪一组用例，就只能靠人去记"我现在起的是哪个" ——
 * 记错的结果是<b>用错误的预期去判定对错</b>，比如把"AI 关着所以没有任务"
 * 判成"入队失败"。所以状态必须能被<b>查询</b>，而不是靠约定。
 *
 * <h2>⚠️ 无论 AI 开没开，状态永远是 UP</h2>
 *
 * <p>这一点是刻意的，且很重要：{@code /actuator/health} 的总体状态被
 * Docker healthcheck / K8s 探针用来决定"这个实例能不能收流量"。
 * 如果 AI 没配就报 DOWN，会得到一个荒谬的结果 ——
 * <b>没配 API Key 的实例被反复重启</b>，而它其实完全能正常写日记。
 *
 * <p>"AI 没开"是<b>配置状态</b>，不是<b>健康状态</b>。
 * 所以这里只往 {@code details} 里塞信息，不改总状态。
 * 需要告警的话应该基于 details 里的 {@code analysisEnabled} 单独做规则。
 *
 * <h2>⚠️ 这里绝不暴露密钥</h2>
 *
 * <p>只报开关与模式名。API Key、Prompt 全文、任何用户数据都不出现 ——
 * 这是开发文档 §5.4 的红线。{@code AiAnalysisProperties#toSafeString}
 * 也是照这个原则写的。
 */
@Component("aiAnalysis")
public class AiAnalysisHealthIndicator implements HealthIndicator {

    private final AiAnalysisProperties properties;
    private final ObjectProvider<AiTaskWorker> workerProvider;

    /**
     * Worker 的轮询间隔（毫秒）。
     *
     * <p>为什么用 {@code @Value} 单独读、而不放进
     * {@link AiAnalysisProperties}：它只被 {@code @Scheduled} 的占位符
     * 使用，配置类里再加一个同名字段会出现"两个地方都能改它"的歧义。
     * 这里读的是同一个属性键，所以不会漂移。
     *
     * <p>验收时要靠它判断"我设的 500ms 到底生效了没有"。
     */
    private final long workerIntervalMs;

    public AiAnalysisHealthIndicator(AiAnalysisProperties properties,
                                     ObjectProvider<AiTaskWorker> workerProvider,
                                     @Value("${app.ai.worker-interval-ms:5000}") long workerIntervalMs) {
        this.properties = properties;
        this.workerProvider = workerProvider;
        this.workerIntervalMs = workerIntervalMs;
    }

    @Override
    public Health health() {
        // ⚠️ ObjectProvider 而不是直接注入 AiTaskWorker：
        //    Worker 上有 @ConditionalOnProperty，AI 关闭时**这个 Bean 不存在**，
        //    直接注入会让应用启动失败 —— 那正好是默认配置。
        //    ObjectProvider 允许"可能没有"，于是默认配置也能启动。
        boolean workerPresent = workerProvider.getIfAvailable() != null;

        return Health.up()
                .withDetail("analysisEnabled", properties.isAnalysisEnabled())
                .withDetail("mockOutcome", properties.getMockOutcome())
                .withDetail("mockDelayMs", properties.getMockDelayMs())
                .withDetail("promptVersion", properties.getPromptVersion())
                .withDetail("maxRetries", properties.getMaxRetries())
                .withDetail("workerIntervalMs", workerIntervalMs)
                // ⚠️ workerActive 是这里最有诊断价值的一项：
                //    analysisEnabled=true 但 workerActive=false 意味着
                //    @ConditionalOnProperty 写错了 —— 任务会被入队但**永远没人执行**，
                //    表现是前端一直转圈，而日志里一条错误都没有。
                .withDetail("workerActive", properties.isAnalysisEnabled() && workerPresent)
                .build();
    }
}
