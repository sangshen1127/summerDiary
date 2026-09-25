package com.sangshen.aidiary.common.crypto;

/**
 * 日记正文解密失败。
 *
 * <h2>为什么单独建一个异常类型</h2>
 *
 * <p>解密失败和普通业务失败<b>性质不同</b>：
 * <ul>
 *   <li>"日记不存在" → 用户操作问题 → {@code BusinessException(NOT_FOUND)} → 404</li>
 *   <li>"密文解不开" → 服务端数据或配置问题 → 本异常 → 500</li>
 * </ul>
 *
 * <p>如果图省事抛 {@code BusinessException(INTERNAL_ERROR)}，虽然 HTTP 状态码也是 500，
 * 但语义上把它混进了"业务异常"这一族 —— 而 {@code BusinessException} 在
 * {@code GlobalExceptionHandler} 里是按 {@code log.debug} 记录的（因为 4xx 不值得告警）。
 * 解密失败<b>必须留下 warn 级别的痕迹</b>：它可能意味着密钥配错了，或者数据被篡改过，
 * 两种情况都需要人去看。
 *
 * <h2>⚠️ 异常消息里绝不能有这些</h2>
 *
 * <p>按开发文档 §5.4 的红线，本异常的 message 和 cause <b>不得包含</b>：
 * 密文内容、明文内容、密钥。可以包含：长度、字节数、失败类别。
 *
 * <h2>为什么继承 RuntimeException 而不是受检异常</h2>
 *
 * <p>加解密发生在 Service 内部的很深处。做成受检异常会逼着
 * Controller、事件监听器等所有中间层写 {@code throws} 或 try-catch，
 * 但它们<b>没有任何有意义的处理方式</b>（该返回什么已经在全局处理器定好了）。
 * 这是 Effective Java 里"受检异常用于调用方能恢复的场景"的直接应用 ——
 * 这里调用方恢复不了。
 */
public class DiaryDecryptionException extends RuntimeException {

    public DiaryDecryptionException(String message) {
        super(message);
    }

    /**
     * @param message 见类注释：不得包含密文、明文、密钥
     * @param cause   底层异常（如 {@code AEADBadTagException}）。
     *                它的 message 不含密钥，可以保留用于排查
     */
    public DiaryDecryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
