package com.sangshen.aidiary;

import com.sangshen.aidiary.config.AiAnalysisProperties;
import com.sangshen.aidiary.config.DiaryProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI 日记系统 —— 后端启动类。
 *
 * <p>模块划分见开发文档 §3.1：
 * <ul>
 *   <li>{@code controller} —— 只做参数校验、取 currentUserId、调 Service</li>
 *   <li>{@code service} —— 业务规则、权限、事务</li>
 *   <li>{@code mapper} —— MyBatis 接口，SQL 在 resources/mapper/*.xml</li>
 *   <li>{@code entity} —— 与数据库表一一对应，禁止直接返回给前端</li>
 *   <li>{@code dto} —— request / response / ai 三类，Controller 只返回 response</li>
 *   <li>{@code event} —— 领域事件与监听器（Phase 3 起）</li>
 * </ul>
 *
 * <p><b>安全铁律</b>：所有涉及用户数据的查询、更新、删除都必须显式带 userId。

 * <p><b>时区约定</b>：全项目统一 UTC。见开发文档 §4.4。
 *
 * <h2>关于 {@code @EnableAsync} 与 {@code @EnableScheduling}（Phase 3 加入，不能删）</h2>
 *
 * <p>异步任务链路依赖这两个注解：
 * <pre>
 * 保存日记 → 提交事务 → DiaryCreatedEventListener（@Async）→ 入队 ai_task
 *                    → AiTaskWorker（@Scheduled）→ 执行 → 写回状态
 * </pre>
 *
 * <p>⚠️ <b>漏掉任何一个都不会报错，只会静默失效</b>：
 * <ul>
 *   <li>漏 {@code @EnableAsync} → {@code @Async} 方法退化成同步执行，
 *       症状是"保存日记的响应变慢"，没有任何显式错误</li>
 *   <li>漏 {@code @EnableScheduling} → {@code @Scheduled} 方法<b>根本不会被调用</b>。
 *       症状是"任务永远停在 PENDING"，而日志里一条错误都没有 ——
 *       这是最难查的一类问题，因为看起来"什么都没发生"</li>
 * </ul>
 *
 * <p>这类静默失效正是本项目反复强调的：**"没报错"不等于"在正常工作"**。
 * 所以验收时必须实际造一篇日记、观察任务从 PENDING 变成 SUCCESS，
 * 而不是只看着应用启动成功。
 *
 * <p>线程池配置见 {@code application.yml} 的 {@code spring.task.execution}
 * （core 4 / max 8 / queue 100，线程名前缀 {@code ai-task-}）。
 */
@SpringBootApplication
@MapperScan("com.sangshen.aidiary.mapper")
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties({DiaryProperties.class, AiAnalysisProperties.class})
public class AiDiaryApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiDiaryApplication.class, args);
    }
}
