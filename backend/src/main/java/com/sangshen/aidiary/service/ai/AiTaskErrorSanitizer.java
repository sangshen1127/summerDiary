package com.sangshen.aidiary.service.ai;

import java.util.regex.Pattern;

/**
 * 错误信息脱敏与截断 —— 写进 {@code ai_task.error_message} 之前的最后一道关。
 *
 * <h2>它防两件事</h2>
 *
 * <ol>
 *   <li><b>隐私泄露</b>：异常 message 里可能夹带日记正文片段、Prompt 片段、
 *       完整模型响应、URL 上的 API Key。开发文档 §5.4 把这些都列为红线。</li>
 *   <li><b>写库失败</b>：{@code error_message} 是 {@code VARCHAR(500)}，
 *       超长会被 MySQL 直接拒绝（严格模式下报错，非严格模式下静默截断）。
 *       前者让"标记失败"这个动作本身失败 —— 任务会永远卡在 RUNNING。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么"截断"这件事必须显式做，不能指望数据库</h2>
 *
 * <p>如果依赖 MySQL 静默截断（非严格模式），行为是"悄悄丢掉后面部分" ——
 * 排查时看到一段断在中间的报错，会以为是日志被切了，
 * 而不是"当初就没存全"。
 *
 * <p>我们主动截断并**在末尾加省略标记**，让人一眼看出这是被截过的。
 * 这是"显式优于隐式"在数据层面的应用。
 *
 * <h2>关于脱敏规则的强度</h2>
 *
 * <p>这是**兜底防线**，不是主要防线。主要防线是：
 * <ul>
 *   <li>AI 侧实现方按 {@code CognitionService} 的约定，
 *       不把正文/Prompt 放进异常 message</li>
 *   <li>框架自己不把正文传给任何可能抛异常并带出 message 的地方</li>
 * </ul>
 *
 * <p>兜底规则只能做"看起来像密钥/像长文本"的粗筛，
 * 不可能做到万无一失（比如中文正文没有任何特征）。
 * <b>所以不要把安全性寄托在这里</b> —— 这里只是降低"万一漏了"的损失。
 */
public final class AiTaskErrorSanitizer {

    /** 与 {@code ai_task.error_message} 的列宽一致。改这里必须同步改 DDL（走新迁移）。 */
    private static final int MAX_LENGTH = 500;

    /** 截断后追加的标记。用 ASCII，避免控制台编码问题。 */
    private static final String ELLIPSIS = "...[truncated]";

    /**
     * 疑似密钥的模式。
     *
     * <p>覆盖两种最常见形态：
     * <ul>
     *   <li>{@code sk-} 开头的 OpenAI 风格 key</li>
     *   <li>{@code Authorization: Bearer xxx} 或 URL 里的 {@code api_key=xxx}</li>
     * </ul>
     */
    private static final Pattern[] SECRET_PATTERNS = {
            Pattern.compile("sk-[A-Za-z0-9_\\-]{8,}"),
            Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._\\-]{8,}"),
            Pattern.compile("(?i)(api[_-]?key|apikey|access[_-]?token|secret)\\s*[=:]\\s*[A-Za-z0-9._\\-/+=]{8,}"),
    };

    private static final String MASK = "***";

    private AiTaskErrorSanitizer() {
        // 工具类
    }

    /**
     * 把异常整理成可安全入库的错误信息。
     *
     * @param error 异常，可为 null
     * @return 脱敏并被截断到 500 字符以内的文本；永不为 null
     */
    public static String sanitize(Throwable error) {
        if (error == null) {
            return "未知错误";
        }

        // 用 类名 + message，不用完整堆栈：
        //   1. 堆栈有几百行，塞进 500 字符必然被截，反而看不到关键信息
        //   2. 堆栈里可能有内部类名/路径，属于实现细节
        String raw = error.getClass().getSimpleName();
        String msg = error.getMessage();
        if (msg != null && !msg.isBlank()) {
            raw = raw + ": " + msg;
        }

        return truncate(maskSecrets(raw));
    }

    /**
     * 把任意字符串整理成可安全入库的形式（脱敏 + 截断）。
     *
     * <p>用于调用方已经自己组织好文案、但长度不可控的场景。
     *
     * @param text 原始文本，可为 null
     * @return 安全的文本；永不为 null
     */
    public static String sanitizeText(String text) {
        if (text == null || text.isBlank()) {
            return "（无详细信息）";
        }
        return truncate(maskSecrets(text));
    }

    /** 把疑似密钥替换成掩码。 */
    static String maskSecrets(String text) {
        String result = text;
        for (Pattern p : SECRET_PATTERNS) {
            result = p.matcher(result).replaceAll(MASK);
        }
        return result;
    }

    /**
     * 截断到列宽以内。
     *
     * <p>注意要按<b>字符数</b>而不是字节数判断 ——
     * MySQL 的 VARCHAR(500) 在 utf8mb4 下是 500 个字符，
     * 一个汉字算 1 个字符。用字节数截会把中文错误信息砍得过短。
     */
    static String truncate(String text) {
        if (text == null) {
            return "未知错误";
        }
        if (text.length() <= MAX_LENGTH) {
            return text;
        }
        // 留出省略标记的位置，保证总长不超限
        int keep = MAX_LENGTH - ELLIPSIS.length();
        return text.substring(0, keep) + ELLIPSIS;
    }

    /** 暴露列宽供测试断言使用（不要在生产代码里用来做别的事）。 */
    public static int maxLength() {
        return MAX_LENGTH;
    }
}
