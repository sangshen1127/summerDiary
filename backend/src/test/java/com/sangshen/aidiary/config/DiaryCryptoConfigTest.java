package com.sangshen.aidiary.config;

import com.sangshen.aidiary.common.crypto.AesGcmUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DiaryCryptoConfig} 的单元测试 —— 重点是<b>失败路径</b>。
 *
 * <p>为什么失败路径比成功路径更值得测：这个类的唯一价值就是「配置错的时候让启动失败」。
 * 成功路径（拿到合法密钥造出 Bean）在应用每次正常启动时都被验证了一遍；
 * 而失败路径平时<b>永远不会被执行</b>，等到真需要它的那天才发现它没生效，
 * 就失去了快速失败的全部意义。
 *
 * <p>测试不启动 Spring 容器，直接调用 {@code aesGcmUtil()} ——
 * 校验逻辑全在这个方法里，用不着为它拉起整个上下文。
 */
@DisplayName("DiaryCryptoConfig 启动期密钥校验")
class DiaryCryptoConfigTest {

    private static final byte[] VALID_KEY_BYTES =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private static final String VALID_KEY = Base64.getEncoder().encodeToString(VALID_KEY_BYTES);

    /** {@code .env.example} 里的占位值，必须与配置类和 .env.example 三处一致。 */
    private static final String PLACEHOLDER_KEY = "replace-with-base64-encoded-32-bytes";

    private final DiaryCryptoConfig config = new DiaryCryptoConfig();

    private static DiaryProperties propsWith(String key) {
        DiaryProperties properties = new DiaryProperties();
        properties.setEncryptionKey(key);
        return properties;
    }

    // ══════════════════════════════════════════════════════════
    // 成功路径
    // ══════════════════════════════════════════════════════════

    @Test
    @DisplayName("1. 合法密钥 → 成功创建可用的 AesGcmUtil（Bean 真的能加解密）")
    void validKeyCreatesWorkingBean() {
        AesGcmUtil util = config.aesGcmUtil(propsWith(VALID_KEY));

        assertThat(util).isNotNull();
        assertThat(util.decrypt(util.encrypt("启动校验通过"))).isEqualTo("启动校验通过");
    }

    @Test
    @DisplayName("2. 密钥前后有空格也能成功（.env 复制粘贴常见问题）")
    void keyWithSurroundingWhitespaceAccepted() {
        AesGcmUtil util = config.aesGcmUtil(propsWith("  " + VALID_KEY + "  "));

        assertThat(util.decrypt(util.encrypt("空格容忍"))).isEqualTo("空格容忍");
    }

    // ══════════════════════════════════════════════════════════
    // 失败路径 —— 本类的核心职责
    // ══════════════════════════════════════════════════════════

    @ParameterizedTest
    @DisplayName("3. 密钥缺失/空白 → 启动失败，且提示里给出修复步骤")
    @NullSource
    @ValueSource(strings = {"", "   ", "\t"})
    void missingKeyFailsFast(String key) {
        assertThatThrownBy(() -> config.aesGcmUtil(propsWith(key)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("配置缺失")
                // 快速失败的价值在于「可操作」，所以必须验证提示里真的有下一步
                .hasMessageContaining("DIARY_ENCRYPTION_KEY")
                .hasMessageContaining("修复步骤");
    }

    @Test
    @DisplayName("4. 密钥仍是 .env.example 的占位值 → 启动失败并指出根因")
    void placeholderKeyFailsFast() {
        assertThatThrownBy(() -> config.aesGcmUtil(propsWith(PLACEHOLDER_KEY)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("占位值")
                // 这条诊断信息是给「忘了填 .env」的人看的，必须点明
                .hasMessageContaining(".env.example");
    }

    @Test
    @DisplayName("5. 16 字节密钥（AES-128）→ 启动失败，提示实际字节数")
    void sixteenByteKeyFailsFast() {
        String key16 = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> config.aesGcmUtil(propsWith(key16)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是 32 字节")
                .hasMessageContaining("16 字节");
    }

    @Test
    @DisplayName("6. 64 个十六进制字符（openssl rand -hex 32 的常见误用）→ 启动失败")
    void hexKeyFailsFast() {
        // 这种值长度正好也是 64 字符，看上去"够长"，但根本不是 Base64
        String hexKey = "a".repeat(64);

        assertThatThrownBy(() -> config.aesGcmUtil(propsWith(hexKey)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("7. 非 Base64 密钥 → 启动失败，且提示里不回显密钥内容")
    void nonBase64KeyFailsFastWithoutLeaking() {
        String notBase64 = "!!!这不是密钥!!!";

        assertThatThrownBy(() -> config.aesGcmUtil(propsWith(notBase64)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64")
                // 开发文档 §5.4 红线：密钥不得进入异常消息（会被日志记录）
                .hasMessageNotContaining(notBase64);
    }

    @Test
    @DisplayName("8. 长度不对时的失败消息里不得包含密钥内容（红线）")
    void lengthFailureDoesNotLeakKey() {
        String key16 = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> config.aesGcmUtil(propsWith(key16)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(key16);
    }

    @Test
    @DisplayName("9. 失败消息只用 ASCII 分隔线（GBK 控制台下不出现乱码）")
    void failureMessageUsesAsciiOnly() {
        assertThatThrownBy(() -> config.aesGcmUtil(propsWith("")))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(ex -> assertThat(ex.getMessage())
                        // ═ ─ ━ 这类框线字符在 Windows GBK 控制台会显示成乱码
                        .doesNotContain("\u2550")
                        .doesNotContain("\u2500")
                        .doesNotContain("\u2501"));
    }

    @Test
    @DisplayName("10. 默认属性值为空 → 未配置时必然失败，不存在「静默用默认密钥」的可能")
    void defaultPropertiesAreEmptyAndFail() {
        DiaryProperties defaults = new DiaryProperties();

        // 兜底值必须是空字符串，而不是某个可用的默认密钥
        assertThat(defaults.getEncryptionKey()).isEmpty();
        assertThatThrownBy(() -> config.aesGcmUtil(defaults))
                .isInstanceOf(IllegalStateException.class);
    }
}
