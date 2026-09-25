package com.sangshen.aidiary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 日记正文加密的配置。
 *
 * <p>只有一个属性，但仍然单独建类而不是散用 {@code @Value}，原因有两个：
 * <ol>
 *   <li>密钥的校验逻辑（必须是 Base64 的 32 字节）需要一个明确的归属地。
 *       用 {@code @Value} 的话校验只能写在某个 Bean 的构造函数里，
 *       "谁负责保证密钥正确"就变得模糊。</li>
 *   <li>密钥需要被<b>两处</b>读取：{@code AesGcmUtil}（真正用）
 *       和 {@code StartupConfigDiagnostics}（只报告状态）。共享一个配置类
 *       能保证两处读到的是同一个值。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么 properties 类里没有日志、没有 toString</h2>
 *
 * <p>本类持有密钥。一旦有人给它加上 Lombok 的 {@code @Data}/{@code @ToString}，
 * 或者打了 {@code log.info("config={}", properties)}，密钥就会进日志 ——
 * 这是开发文档 §5.4 的红线。
 *
 * <p>所以这里<b>刻意手写</b> getter/setter，并且不提供 {@code toString()}。
 * 默认的 {@code Object.toString()} 只输出类名和哈希码，是安全的。
 * 将来如果确实需要打印，必须像 {@code StartupConfigDiagnostics.describeSecret}
 * 那样只输出长度。
 */
@ConfigurationProperties(prefix = "diary")
public class DiaryProperties {

    /**
     * 日记正文加密密钥，Base64 编码的 32 字节。
     *
     * <p>值的来源链条：
     * <pre>
     * 项目根 .env 的 DIARY_ENCRYPTION_KEY
     *   → DotenvEnvironmentPostProcessor 注入 Environment（属性名就是 DIARY_ENCRYPTION_KEY）
     *   → application.yml 里 diary.encryption-key: ${DIARY_ENCRYPTION_KEY:}
     *   → 本属性
     * </pre>
     *
     * <p>⚠️ 中间那一步的 {@code ${DIARY_ENCRYPTION_KEY:}} <b>不能省</b>。
     * 模块 2-1 实测踩过：以为 Spring Boot 的宽松绑定会自动把环境变量
     * {@code DIARY_ENCRYPTION_KEY} 绑到 {@code diary.encryption-key} 上 —— 不会。
     * 宽松绑定只解决「同一属性名的不同拼法」（{@code DIARY_ENCRYPTIONKEY} /
     * {@code diary.encryptionkey} / {@code diary.encryption-key}），
     * 而 {@code DIARY_ENCRYPTION_KEY} 里没有点号，无法推导出
     * {@code diary} 与 {@code encryption-key} 的分界。
     *
     * <p>兜底值是<b>空字符串而非某个默认密钥</b>，这是刻意的：
     * 给一个"能跑起来的默认密钥"会让忘记配置的人一直不知道自己在用假密钥，
     * 而且生产环境一旦漏配，所有日记都会用这个公开的默认密钥加密。
     * 空值会被 {@code DiaryCryptoConfig} 在启动期拦下并给出可操作的提示。
     */
    private String encryptionKey = "";

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }
}
