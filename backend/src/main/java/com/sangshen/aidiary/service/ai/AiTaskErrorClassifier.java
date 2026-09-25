package com.sangshen.aidiary.service.ai;

import com.sangshen.aidiary.common.crypto.DiaryDecryptionException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 判断一次任务失败是否值得重试，并把异常归类成一个可读的错误码。
 *
 * <h2>⚠️ 为什么必须分类：无差别重试会烧钱且无效</h2>
 *
 * <p>《我的职责与任务清单》第 178 行的明确要求：
 * <blockquote>
 * 只对网络错误、429、5xx 指数退避；<b>4xx 参数错误不重试</b>；
 * JSON 校验失败先发一次修复请求再失败。
 * </blockquote>
 *
 * <p>无差别重试三种失败的后果：
 *
 * <table border="1">
 *   <caption>错误分类与处置</caption>
 *   <tr><th>类别</th><th>例子</th><th>重试有用吗</th></tr>
 *   <tr><td>网络 / 超时</td><td>连接超时、读超时、DNS 失败</td>
 *       <td><b>有用</b>：多半是瞬时抖动</td></tr>
 *   <tr><td>限流</td><td>HTTP 429</td>
 *       <td><b>有用</b>：退避后通常能过</td></tr>
 *   <tr><td>服务端故障</td><td>HTTP 500 / 502 / 503</td>
 *       <td><b>有用</b>：上游恢复后能过</td></tr>
 *   <tr><td>请求本身有问题</td><td>HTTP 400 / 401 / 403 / 404</td>
 *       <td><b>没用</b>：同样的请求再发 100 次也是同样结果，
 *           只是白烧 100 次额度</td></tr>
 *   <tr><td>结果无法解析</td><td>模型返回的 JSON 不合法</td>
 *       <td>不该"原样重试"。严格说应该"发一次修复请求"
 *           （AI 侧职责），框架层先当作不可重试</td></tr>
 *   <tr><td>我们的数据有问题</td><td>密文解不开、日记被删</td>
 *       <td><b>没用</b>：重试改变不了数据和密钥</td></tr>
 * </table>
 *
 * <h2>⚠️ 分类靠异常类型，而且会<b>穿透 cause 链</b></h2>
 *
 * <p>{@code CognitionService.analyze} 的实现方往往会把原始异常包一层
 * （比如 {@code new RuntimeException(new SocketTimeoutException(...))}），
 * 因为它是"调用模型失败"而不是"IO 失败"。如果只检查最外层类型，
 * 一次真实的<b>网络超时会被归成 UNKNOWN</b> —— 而 UNKNOWN 不重试，
 * 症状是"偶尔抖一下就永久失败，用户只能手动点重试"。
 *
 * <p>所以两个方法都会沿 {@code getCause()} 往下找（限深 10 层防成环）。
 * 这条规则对实现方的要求就只剩"别把 cause 丢掉"，比"别包装"现实得多。
 *
 * <h2>为什么用 {@code isRetryable} + {@code classify} 两个方法</h2>
 *
 * <p>它们回答的是两个不同问题：前者决定"走不走重试"，后者决定
 * "写进 {@code error_code} 的那串标签是什么"。分开之后，
 * 排查时可以只看 error_code 就知道当时发生了什么，
 * 而不需要去读代码里的 if 分支。
 */
public final class AiTaskErrorClassifier {

    /** 沿 cause 链查找的最大深度（防异常链成环导致死循环）。 */
    private static final int MAX_CAUSE_DEPTH = 10;

    private AiTaskErrorClassifier() {
        // 工具类
    }

    /**
     * 一次失败是否值得重试。
     *
     * <h2>判定顺序（改了会影响重试行为，别随手调）</h2>
     * <ol>
     *   <li><b>网络/超时 → 可重试</b>。放在最前面：异常被包装成什么类型
     *       都改变不了"这是一次瞬时抖动"的事实</li>
     *   <li><b>HTTP 状态码</b>：429 与 5xx 可重试，其余 4xx 不可重试</li>
     *   <li><b>JSON 解析失败 → 不可重试</b>（"发修复请求"是 AI 侧职责，
     *       框架层再做一次也是同样结果）</li>
     *   <li><b>其余一律不可重试</b></li>
     * </ol>
     *
     * <p>⚠️ 最后一条曾经是反的（未知归为"可重试"）。那看起来"更保险"，
     * 实际会把 {@code NullPointerException} 这类编码错误重试 3 次 ——
     * 既掩盖了真实 bug，又白烧 3 次模型额度。
     * <b>宁可让它立刻 FAILED 暴露出来。</b>
     *
     * @param error 捕获到的异常，可为 null（null 视为不可重试）
     * @return true 表示应该重试
     */
    public static boolean isRetryable(Throwable error) {
        if (error == null) {
            return false;
        }

        // ── 1. 网络层（穿透包装）────────────────────────────────
        // HttpTimeoutException 是 HttpClient 的超时；
        // SocketTimeoutException 是底层 socket 超时。
        if (hasCause(error, e -> e instanceof HttpTimeoutException
                || e instanceof SocketTimeoutException
                || e instanceof IOException)) {
            return true;
        }

        // ── 2. HTTP 状态码类 ───────────────────────────────────
        Integer status = extractHttpStatus(error);
        if (status != null) {
            // 429 限流：退避后重试有效
            if (status == 429) {
                return true;
            }
            // 5xx：上游故障，值得重试
            if (status >= 500 && status <= 599) {
                return true;
            }
            // 4xx（除 429）：请求本身有问题，重试无意义
            return false;
        }

        // ── 3. JSON 解析失败：不可重试 ──────────────────────────
        // "发一次修复请求"是 AI 侧在 analyze() 内部该做的事；
        // 如果它已经试过修复还是失败，框架再重试也只是重复失败。
        if (hasCause(error, AiTaskErrorClassifier::looksLikeParseFailure)) {
            return false;
        }

        // ── 4. 未知：不重试（理由见方法注释）─────────────────────
        // 显式写出来而不是靠"落到最后"，是为了让意图一眼可见。
        return false;
    }

    /**
     * 把异常归类成写进 {@code ai_task.error_code} 的标签。
     *
     * <p>标签保持**有限集合**（不要拼入异常 message），
     * 这样可以直接 GROUP BY 统计"哪类失败最多"。
     *
     * <p>判定顺序与 {@link #isRetryable} 保持一致 ——
     * 两个方法给出的结论必须自洽，否则会出现
     * "error_code 说 AI_TIMEOUT，但决定不重试"这种自相矛盾的记录。
     *
     * @param error 异常，可为 null
     * @return 错误码标签，取值集合见类注释与开发文档 §4.2
     */
    public static String classify(Throwable error) {
        if (error == null) {
            return "UNKNOWN";
        }
        if (hasCause(error, e -> e instanceof DiaryDecryptionException)) {
            return "DECRYPT_FAILED";
        }

        Integer status = extractHttpStatus(error);
        if (status != null) {
            if (status == 429) {
                return "AI_HTTP_429";
            }
            if (status >= 500) {
                return "AI_HTTP_5XX";
            }
            if (status >= 400) {
                return "AI_HTTP_4XX";
            }
            return "AI_HTTP_" + status;
        }

        if (hasCause(error, e -> e instanceof HttpTimeoutException
                || e instanceof SocketTimeoutException)) {
            return "AI_TIMEOUT";
        }
        if (hasCause(error, e -> e instanceof IOException)) {
            return "AI_NETWORK";
        }
        if (hasCause(error, AiTaskErrorClassifier::looksLikeParseFailure)) {
            return "JSON_INVALID";
        }
        return "UNKNOWN";
    }

    /**
     * 把异常链拍平，从外到内。
     *
     * @param error 起始异常
     * @return 异常链（最多 {@value #MAX_CAUSE_DEPTH} 个）
     */
    private static List<Throwable> chainOf(Throwable error) {
        List<Throwable> chain = new ArrayList<>(4);
        Throwable cur = error;
        while (cur != null && chain.size() < MAX_CAUSE_DEPTH) {
            chain.add(cur);
            // getCause() 返回自身是某些实现的坑，会造成死循环
            cur = cur.getCause() == cur ? null : cur.getCause();
        }
        return chain;
    }

    /** 异常链里是否存在满足条件的节点。 */
    private static boolean hasCause(Throwable error, java.util.function.Predicate<Throwable> predicate) {
        for (Throwable t : chainOf(error)) {
            if (predicate.test(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否像"JSON 解析失败"。
     *
     * <p>按类名判断而不是按具体类型：AI 侧用什么 JSON 库（Jackson /
     * Gson / LangChain4j 自己的）我们管不着，但类名里几乎一定带
     * {@code Json} 或 {@code Parse}。
     */
    private static boolean looksLikeParseFailure(Throwable error) {
        String simpleName = error.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        return simpleName.contains("json") || simpleName.contains("parse");
    }

    /**
     * 从异常链里尽力找出 HTTP 状态码。
     *
     * <p>⚠️ 这里刻意**不依赖任何具体 HTTP 客户端类型**
     * （LangChain4j / okhttp / HttpClient 各有自己的异常）。
     * 用反射读常见的 status 字段/方法，找不到就返回 null。
     *
     * <p>为什么不用 instanceof 判断具体类型：那会把本类绑死在
     * AI 侧选用的 HTTP 客户端上 —— 换供应商就要改我的分类器。
     * 反射虽不优雅，但**解耦**在这里比优雅重要。
     *
     * @param error 异常（会遍历 cause 链）
     * @return 状态码，找不到时返回 null
     */
    private static Integer extractHttpStatus(Throwable error) {
        Throwable cur = error;
        int depth = 0;
        // 限制深度，防止异常链成环导致死循环
        while (cur != null && depth++ < 10) {
            // 常见写法一：字段 statusCode / status
            Integer v = readIntMember(cur, "statusCode");
            if (v == null) {
                v = readIntMember(cur, "status");
            }
            if (v != null && v >= 100 && v <= 599) {
                return v;
            }
            cur = cur.getCause();
        }
        return null;
    }

    /** 反射读取 int 型字段或 getter；读不到返回 null。 */
    private static Integer readIntMember(Object target, String name) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            Object val = f.get(target);
            if (val instanceof Integer i) {
                return i;
            }
        } catch (Exception ignored) {
            // 没有这个字段是正常的，继续试别的
        }
        try {
            String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            java.lang.reflect.Method m = target.getClass().getMethod(getter);
            Object val = m.invoke(target);
            if (val instanceof Integer i) {
                return i;
            }
        } catch (Exception ignored) {
            // 同上
        }
        return null;
    }
}
