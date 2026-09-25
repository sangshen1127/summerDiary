package com.sangshen.aidiary.dto.response;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 时间转换：数据库的「无时区 UTC」→ 接口的「带 Z 的 ISO-8601」。
 *
 * <h2>为什么需要这个类（这是本项目一个真实的坑）</h2>
 *
 * <p>开发文档 §4.4 的契约是：
 * <ul>
 *   <li>数据库：{@code DATETIME}，统一存 UTC</li>
 *   <li>接口：<b>ISO-8601 带时区</b>，例如 {@code 2025-03-15T08:30:00Z}</li>
 * </ul>
 *
 * <p>但 MySQL 的 {@code DATETIME} 列<b>本身不存时区</b>，JDBC 读出来是
 * {@code LocalDateTime}（无时区）。如果直接把它放进 DTO，Jackson 会序列化成：
 * <pre>
 * "2026-09-23T09:15:30"        ← 没有 Z！
 * </pre>
 *
 * <p>而契约要求的是：
 * <pre>
 * "2026-09-23T09:15:30Z"       ← 有 Z，明确是 UTC
 * </pre>
 *
 * <h2>为什么"没有 Z"很危险（且不报错）</h2>
 *
 * <p>JavaScript 的 {@code new Date("2026-09-23T09:15:30")} 会按
 * <b>浏览器本地时区</b>解析，而 {@code new Date("2026-09-23T09:15:30Z")}
 * 按 UTC 解析。对东八区用户，两者相差整整 8 小时 ——
 * 而且<b>不会抛任何错误</b>，只是显示的时间不对。
 *
 * <p>前端 {@code formatTime.ts} 目前做了"没带 Z 就补一个"的防御，
 * 但那是在替后端擦屁股：一旦换成 Apifox、第三方客户端或未来的移动端，
 * 就没有这层防御了。所以正确做法是<b>后端就返回带 Z 的时间</b>。
 *
 * <h2>为什么用 Instant 而不是 OffsetDateTime</h2>
 *
 * <p>两者 Jackson 都能序列化成带 Z 的形式。选 {@code Instant} 的理由：
 * <ul>
 *   <li>它在类型层面就表达了"这是一个时刻"，而 {@code OffsetDateTime}
 *       还带着"偏移量是多少"的信息 —— 我们的偏移量恒为 UTC，没有信息量</li>
 *   <li>项目里 {@code HealthController} 已经在用 {@code Instant.now()}，
 *       实测序列化成 {@code 2026-09-23T08:13:33.459787300Z}，符合契约</li>
 * </ul>
 *
 * <p><b>不用</b> {@code @JsonFormat(pattern = "...Z", timezone = "UTC")}
 * 去给 {@code LocalDateTime} 硬拼一个 Z：那样做出来的 {@code LocalDateTime}
 * 在类型上依然"没有时区"，任何拿到它的人（包括我们自己后续的代码）
 * 都可能再次误判。转换应该在类型上做对，而不是靠注解修饰字符串。
 */
public final class UtcTime {

    private UtcTime() {
        // 工具类，禁止实例化
    }

    /**
     * 把「语义为 UTC 的无时区时间」转成「带 UTC 时区的时刻」。
     *
     * <p>之所以能确定它是 UTC：数据库连接串配了 {@code serverTimezone=UTC}
     * （见 {@code application.yml} 的 datasource.url），
     * 且表定义里 {@code created_at} 用 {@code CURRENT_TIMESTAMP} 写入，
     * 写入与读出用的是同一个时区约定。
     *
     * @param localDateTime 语义为 UTC 的无时区时间，可为 null
     * @return 带 UTC 时区的时刻；输入 null 时返回 null
     *         （可选字段如 {@code updated_at} 可能是 null，不能抛异常）
     */
    public static Instant toInstant(LocalDateTime localDateTime) {
        return localDateTime == null ? null : localDateTime.toInstant(ZoneOffset.UTC);
    }
}
