package com.sangshen.aidiary.config;

import com.sangshen.aidiary.common.crypto.AesGcmUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Base64;

/**
 * 日记加密的装配与启动期校验。
 *
 * <h2>这个类存在的核心理由：快速失败</h2>
 *
 * <p>密钥配错有两种典型表现，它们都会在<b>很久之后</b>才暴露：
 *
 * <table border="1">
 *   <caption>密钥配置错误的两种症状</caption>
 *   <tr><th>配置错误</th><th>不校验时的症状</th></tr>
 *   <tr>
 *     <td>密钥为空 / 还是占位值</td>
 *     <td>应用<b>正常启动</b>，健康检查 200，注册登录全好。<br>
 *         直到用户写下第一篇日记、再点进详情页 —— 才发现写不进去或读不出来。<br>
 *         排查时容易怀疑 MyBatis、怀疑数据库列类型，方向完全被带偏。</td>
 *   </tr>
 *   <tr>
 *     <td>密钥长度不对（比如 16 字节的 AES-128 密钥）</td>
 *     <td>Tomcat 能起来。第一次加密抛
 *         {@code InvalidKeyException: Invalid AES key length}。<br>
 *         同样是把配置问题伪装成运行期故障。</td>
 *   </tr>
 * </table>
 *
 * <p>对照 {@code StartupConfigDiagnostics} 里数据库密码的占位值检查 ——
 * 那是同一个原则的第 N 次应用：<b>配置错误必须在启动时响亮地失败，
 * 而不是在运行期静默降级。</b>
 *
 * <h2>为什么把 Bean 创建和校验放在同一个方法里</h2>
 *
 * <p>因为校验的产物就是那个 Bean。<b>不校验就不可能造出合法的
 * {@link AesGcmUtil}</b>（它的构造函数本来就会拒绝非 32 字节的密钥）。
 * 拆成"先校验、再创建"两个 Bean 会给人"校验可以被跳过"的错觉 ——
 * 而它不能。
 */
@Configuration
@EnableConfigurationProperties(DiaryProperties.class)
public class DiaryCryptoConfig {

    private static final Logger log = LoggerFactory.getLogger(DiaryCryptoConfig.class);

    /** 预期的密钥字节数。与 {@link AesGcmUtil#KEY_LENGTH} 保持一致。 */
    private static final int EXPECTED_KEY_BYTES = AesGcmUtil.KEY_LENGTH;

    /**
     * 已知的占位值（来自 {@code .env.example}）。
     *
     * <p>与 {@code StartupConfigDiagnostics.PLACEHOLDER_VALUES} 和
     * {@code .env.example} 三处必须一致 —— 改一处要同步另外两处。
     */
    private static final String PLACEHOLDER_KEY = "replace-with-base64-encoded-32-bytes";

    /**
     * 创建加解密工具 Bean，并在创建前完成密钥校验。
     *
     * @param properties 由 {@code @EnableConfigurationProperties} 绑定，
     *                   值来自环境变量 {@code DIARY_ENCRYPTION_KEY}
     * @return 可直接注入 {@code DiaryServiceImpl} 的单例工具
     * @throws IllegalStateException 密钥缺失、是占位值、不是合法 Base64、或不是 32 字节
     */
    @Bean
    public AesGcmUtil aesGcmUtil(DiaryProperties properties) {
        String configured = properties.getEncryptionKey();

        // ── 1. 存在性与占位值检查 ───────────────────────────────
        if (configured == null || configured.isBlank()) {
            throw missingKeyFailure(
                    "DIARY_ENCRYPTION_KEY 未配置（值为空）",
                    "当前解析到的值是空字符串，说明环境变量没读到。");
        }
        if (PLACEHOLDER_KEY.equals(configured.trim())) {
            throw missingKeyFailure(
                    "DIARY_ENCRYPTION_KEY 仍是 .env.example 里的占位值",
                    "这说明 .env 是从 .env.example 复制来的，但密钥那一行没有真正替换。");
        }

        // ── 2. Base64 解码 + 长度检查 ───────────────────────────
        // 注意：AesGcmUtil.fromBase64Key 内部也会做一次长度校验并抛
        // IllegalArgumentException。这里提前做一遍<b>不是冗余</b> ——
        // 为的是把失败包装成带修复步骤的 IllegalStateException，
        // 而不是让一个裸的 IllegalArgumentException 冒到启动日志里
        // （后者不会告诉使用者"下一步该做什么"）。
        byte[] decoded = decodeOrFail(configured);
        if (decoded.length != EXPECTED_KEY_BYTES) {
            // 只报长度，绝不报内容
            throw new IllegalStateException("""

                    ============================================================
                    配置错误：DIARY_ENCRYPTION_KEY 解码后不是 %d 字节（实际 %d 字节）
                    ============================================================
                    AES-256-GCM 要求密钥长度恰好是 32 字节，Base64 编码后是 44 个字符
                    （结尾带一个 '='）。

                    常见原因：
                      1. 用了 openssl rand -hex 32   -> 得到 64 个十六进制字符，
                                                        不是 Base64，长度会不对
                      2. 用了 openssl rand -base64 16 -> 只生成 16 字节（AES-128）
                      3. 复制时被截断，或前后混入了空格/换行/引号

                    修复：重新生成一个 32 字节密钥，替换项目根目录 .env 里的那一行。

                      PowerShell:
                        [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 }))
                      Linux / macOS / Git Bash:
                        openssl rand -base64 32

                    [!] 重要：密钥一旦用于加密过数据就不能再换 ——
                        换了密钥，之前所有日记正文都将无法解密。
                        现在还没有真实日记，改是安全的。
                    ============================================================
                    """.formatted(EXPECTED_KEY_BYTES, decoded.length));
        }

        // ── 3. 构造工具类 ───────────────────────────────────────
        AesGcmUtil util = new AesGcmUtil(decoded);

        // 打印诊断信息。只报长度和算法参数，绝不报密钥内容。
        log.info("日记正文加密已启用: algorithm=AES/GCM/NoPadding, keyLength={}位, ivLength={}字节, tagLength={}位",
                EXPECTED_KEY_BYTES * 8, AesGcmUtil.IV_LENGTH, AesGcmUtil.TAG_LENGTH_BITS);

        return util;
    }

    /**
     * 把 Base64 解码失败转成带修复步骤的启动失败提示。
     *
     * <p>同样刻意不把 {@code configured} 的内容拼进消息 —— 它是密钥。
     */
    private byte[] decodeOrFail(String configured) {
        try {
            return Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("""

                    ============================================================
                    配置错误：DIARY_ENCRYPTION_KEY 不是合法的 Base64 字符串
                    ============================================================
                    密钥必须是 Base64 编码的 32 字节（共 44 个字符，以 '=' 结尾）。
                    当前值的长度是 %d 个字符 —— 长度不对通常说明它根本不是 Base64。

                    修复：在项目根目录 .env 里替换 DIARY_ENCRYPTION_KEY，用下面任一命令生成：

                      PowerShell:
                        [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 }))
                      Linux / macOS / Git Bash:
                        openssl rand -base64 32

                    [注意] 该值不需要加引号，前后也不要留空格。
                    ============================================================
                    """.formatted(configured.trim().length()), ex);
        }
    }

    /** 密钥缺失/占位值的统一失败提示。[注意] 分隔线只用 ASCII 字符，避免 GBK 控制台乱码。 */
    private IllegalStateException missingKeyFailure(String headline, String diagnosis) {
        return new IllegalStateException("""

                ============================================================
                配置缺失：%s
                ============================================================
                %s

                为什么必须启动失败而不是给个默认密钥：
                  日记正文全部用这个密钥加密。如果兜底一个默认值，
                  忘记配置的人会一直用这个公开的密钥加密自己的日记，
                  而且直到出事都不会有任何提示。

                修复步骤（在项目根目录下执行）：
                  1. 确认 .env 存在：      Copy-Item .env.example .env
                  2. 生成一个 32 字节密钥：
                       PowerShell:
                         [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 }))
                       Linux / macOS / Git Bash:
                         openssl rand -base64 32
                  3. 把生成的值填进 .env：
                       DIARY_ENCRYPTION_KEY=<上一步的输出>

                [注意] 若你刚刚才创建 .env，需重启应用才会生效。
                [警告] 密钥丢失 = 所有日记正文永久无法解密，请务必备份。
                ============================================================
                """.formatted(headline, diagnosis));
    }
}
