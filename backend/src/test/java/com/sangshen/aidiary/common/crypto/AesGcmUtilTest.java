package com.sangshen.aidiary.common.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AesGcmUtil} 的单元测试。
 *
 * <p>这是 Phase 2 的第一道防线：加密读写格式一旦定错，
 * 后续所有日记的存取都会带着错误格式落库，返工代价极高。
 *
 * <p><b>这些测试不依赖 Spring 容器，也不连数据库</b> —— 纯 JDK 加解密，
 * 所以可以在任何环境（含沙箱）下用 {@code mvn -o test} 秒级跑完。
 */
@DisplayName("AesGcmUtil 日记正文加解密")
class AesGcmUtilTest {

    /** 固定测试密钥。测试专用，与任何真实环境无关。 */
    private static final byte[] TEST_KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private static final String TEST_KEY_BASE64 = Base64.getEncoder().encodeToString(TEST_KEY);

    private static AesGcmUtil newUtil() {
        return new AesGcmUtil(TEST_KEY);
    }

    // ══════════════════════════════════════════════════════════
    // 1. 基本往返
    // ══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. 加密后必须能解回原文")
    class RoundTrip {

        @Test
        @DisplayName("1.1 中文日记正文往返一致")
        void chineseRoundTrip() {
            AesGcmUtil util = newUtil();
            String plaintext = "今天下午雨停了，和阿哲去河边走了走，看到一只白鹭。";

            String ciphertext = util.encrypt(plaintext);

            assertThat(util.decrypt(ciphertext)).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("1.2 空字符串可以加密（用户只写标题不写正文是合法输入）")
        void emptyStringRoundTrip() {
            AesGcmUtil util = newUtil();

            String ciphertext = util.encrypt("");

            assertThat(util.decrypt(ciphertext)).isEmpty();
        }

        @ParameterizedTest
        @DisplayName("1.3 emoji / 生僻字 / 换行等 Unicode 往返一致（验证 UTF-8 编码协商正确）")
        @ValueSource(strings = {
                "心情不错 😀🌧️🌈",
                "生僻字测试：龘靐齉爩蠿龗",
                "多行\n正文\r\n带制表符\t和引号\"以及反斜杠\\",
                "混合 content with 中文 and English 123 !@#$%^&*()",
                "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67 带零宽连接符的家庭 emoji"
        })
        void unicodeRoundTrip(String plaintext) {
            AesGcmUtil util = newUtil();

            assertThat(util.decrypt(util.encrypt(plaintext))).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("1.4 长正文（4500 字 / 13500 字节）往返一致，长度不被截断")
        void longTextRoundTrip() {
            AesGcmUtil util = newUtil();
            String plaintext = "这是一段用来测试长正文的句子。".repeat(300);

            String ciphertext = util.encrypt(plaintext);

            // 断言用实际值算，不写死数字 —— 避免"数错字数导致测试假失败"
            assertThat(plaintext.codePointCount(0, plaintext.length())).isEqualTo(4500);
            assertThat(util.decrypt(ciphertext)).isEqualTo(plaintext);
        }
    }

    // ══════════════════════════════════════════════════════════
    // 2. IV 随机性（GCM 的安全命门）
    // ══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. 每次加密必须用新的随机 IV")
    class IvRandomness {

        @Test
        @DisplayName("2.1 同一段明文加密两次，密文必须不同（IV 不复用）")
        void samePlaintextProducesDifferentCiphertext() {
            AesGcmUtil util = newUtil();
            String plaintext = "重复的正文内容";

            String first = util.encrypt(plaintext);
            String second = util.encrypt(plaintext);

            // ⚠️ 这条断言是 GCM 安全性的核心：如果相等，说明 IV 被复用，
            //    攻击者可以异或出明文差异，属于灾难性缺陷。
            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("2.2 前 12 字节（IV）不同，说明随机源在起作用")
        void ivPartDiffers() {
            AesGcmUtil util = newUtil();
            String plaintext = "同样的内容";

            byte[] firstIv = extractIv(util.encrypt(plaintext));
            byte[] secondIv = extractIv(util.encrypt(plaintext));

            assertThat(firstIv).isNotEqualTo(secondIv);
        }

        @Test
        @DisplayName("2.3 连续加密 200 次，IV 不出现重复（粗筛随机源没被写死）")
        void ivNeverRepeats() {
            AesGcmUtil util = newUtil();
            Set<String> seenIvs = new HashSet<>();

            for (int i = 0; i < 200; i++) {
                byte[] iv = extractIv(util.encrypt("内容 " + i));
                // 12 字节随机空间下 200 次抽样撞车概率约 1.6e-25，可以放心断言
                assertThat(seenIvs.add(Base64.getEncoder().encodeToString(iv)))
                        .as("第 %d 次加密的 IV 与之前重复", i)
                        .isTrue();
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // 3. 存储格式（必须与开发文档 §5.3 的契约逐字节一致）
    // ══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. 存储格式符合 Base64(IV(12) || ciphertext || tag(16))")
    class StorageFormat {

        @Test
        @DisplayName("3.1 密文是合法 Base64")
        void ciphertextIsValidBase64() {
            String ciphertext = newUtil().encrypt("格式检查");

            assertThat(ciphertext).matches("^[A-Za-z0-9+/]+={0,2}$");
            assertThat(Base64.getDecoder().decode(ciphertext)).isNotEmpty();
        }

        @Test
        @DisplayName("3.2 前 12 字节是 IV")
        void firstTwelveBytesAreIv() {
            assertThat(extractIv(newUtil().encrypt("IV 长度"))).hasSize(AesGcmUtil.IV_LENGTH);
        }

        @Test
        @DisplayName("3.3 密文总长度 = 12(IV) + UTF-8明文字节数 + 16(tag)")
        void totalLengthMatchesContract() {
            AesGcmUtil util = newUtil();
            String plaintext = "长度应当可预测";
            int plaintextBytes = plaintext.getBytes(StandardCharsets.UTF_8).length;

            byte[] raw = Base64.getDecoder().decode(util.encrypt(plaintext));

            // GCM 是流式模式，密文长度恒等于明文长度（NoPadding 的真实含义）
            assertThat(raw).hasSize(AesGcmUtil.IV_LENGTH + plaintextBytes + AesGcmUtil.TAG_LENGTH_BITS / 8);
        }

        @Test
        @DisplayName("3.4 空明文时总长度 = 12 + 0 + 16 = 28 字节")
        void emptyPlaintextLength() {
            byte[] raw = Base64.getDecoder().decode(newUtil().encrypt(""));

            assertThat(raw).hasSize(28);
        }
    }

    // ══════════════════════════════════════════════════════════
    // 4. 防篡改（GCM 认证标签的核心价值）
    // ══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. 密文被篡改必须解密失败，而不是返回乱码")
    class TamperDetection {

        @Test
        @DisplayName("4.1 改动密文正文区一个 bit → 解密抛异常")
        void flipCiphertextBit() {
            AesGcmUtil util = newUtil();
            byte[] raw = Base64.getDecoder().decode(util.encrypt("不许被改动的内容"));

            // 挑一个位于密文区（IV 之后）的字节翻转最低位
            raw[AesGcmUtil.IV_LENGTH + 1] ^= 0x01;
            String tampered = Base64.getEncoder().encodeToString(raw);

            assertThatThrownBy(() -> util.decrypt(tampered))
                    .isInstanceOf(DiaryDecryptionException.class);
        }

        @Test
        @DisplayName("4.2 改动 IV 一个 bit → 解密抛异常")
        void flipIvBit() {
            AesGcmUtil util = newUtil();
            byte[] raw = Base64.getDecoder().decode(util.encrypt("IV 也不能被改"));

            raw[0] ^= 0x01;
            String tampered = Base64.getEncoder().encodeToString(raw);

            assertThatThrownBy(() -> util.decrypt(tampered))
                    .isInstanceOf(DiaryDecryptionException.class);
        }

        @Test
        @DisplayName("4.3 改动认证标签一个 bit → 解密抛异常（这是 GCM 相对 CBC 的关键优势）")
        void flipAuthTagBit() {
            AesGcmUtil util = newUtil();
            byte[] raw = Base64.getDecoder().decode(util.encrypt("标签必须被校验"));

            // 最后一个字节属于 authTag
            raw[raw.length - 1] ^= 0x01;
            String tampered = Base64.getEncoder().encodeToString(raw);

            assertThatThrownBy(() -> util.decrypt(tampered))
                    .isInstanceOf(DiaryDecryptionException.class);
        }

        @Test
        @DisplayName("4.4 截断密文（去掉末尾 tag）→ 解密抛异常，不返回残缺明文")
        void truncatedCiphertext() {
            AesGcmUtil util = newUtil();
            byte[] raw = Base64.getDecoder().decode(util.encrypt("截断测试"));

            byte[] truncated = new byte[raw.length - 4];
            System.arraycopy(raw, 0, truncated, 0, truncated.length);
            String tampered = Base64.getEncoder().encodeToString(truncated);

            assertThatThrownBy(() -> util.decrypt(tampered))
                    .isInstanceOf(DiaryDecryptionException.class);
        }

        @Test
        @DisplayName("4.5 用另一个密钥解密 → 抛异常（模拟密钥配错/被换）")
        void wrongKeyFails() {
            AesGcmUtil encryptor = new AesGcmUtil(TEST_KEY);
            AesGcmUtil other = new AesGcmUtil("abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8));

            String ciphertext = encryptor.encrypt("用 A 密钥加密");

            assertThatThrownBy(() -> other.decrypt(ciphertext))
                    .isInstanceOf(DiaryDecryptionException.class);
        }
    }

    // ══════════════════════════════════════════════════════════
    // 5. 坏输入与异常消息红线
    // ══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. 坏输入处理与日志红线")
    class BadInput {

        @Test
        @DisplayName("5.1 encrypt(null) 抛 IllegalArgumentException，不静默返回 null")
        void encryptNullRejected() {
            assertThatThrownBy(() -> newUtil().encrypt(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("null");
        }

        @Test
        @DisplayName("5.2 decrypt(null) 抛 DiaryDecryptionException")
        void decryptNullRejected() {
            assertThatThrownBy(() -> newUtil().decrypt(null))
                    .isInstanceOf(DiaryDecryptionException.class);
        }

        @Test
        @DisplayName("5.3 非 Base64 的密文 → 抛异常，且消息里不含输入内容")
        void invalidBase64Rejected() {
            String garbage = "这不是base64!!!";

            assertThatThrownBy(() -> newUtil().decrypt(garbage))
                    .isInstanceOf(DiaryDecryptionException.class)
                    // 日志红线：用户数据不得随异常消息进入日志
                    .hasMessageNotContaining(garbage);
        }

        @Test
        @DisplayName("5.4 长度不足的密文 → 抛异常，且消息里不含密文内容")
        void tooShortCiphertextRejected() {
            String tooShort = Base64.getEncoder().encodeToString(new byte[20]);

            assertThatThrownBy(() -> newUtil().decrypt(tooShort))
                    .isInstanceOf(DiaryDecryptionException.class)
                    .hasMessageContaining("长度不足")
                    .hasMessageNotContaining(tooShort);
        }

        @Test
        @DisplayName("5.5 异常堆栈中不得出现密钥内容（开发文档 §5.4 红线）")
        void stackTraceMustNotLeakKey() {
            AesGcmUtil util = newUtil();
            String ciphertext = util.encrypt("红线检查");
            byte[] raw = Base64.getDecoder().decode(ciphertext);
            raw[raw.length - 1] ^= 0x01;

            try {
                util.decrypt(Base64.getEncoder().encodeToString(raw));
                throw new AssertionError("应当抛异常");
            } catch (DiaryDecryptionException ex) {
                StringBuilder trace = new StringBuilder();
                for (StackTraceElement el : ex.getStackTrace()) {
                    trace.append(el).append('\n');
                }
                assertThat(ex.getMessage()).doesNotContain(TEST_KEY_BASE64);
                assertThat(trace.toString()).doesNotContain(TEST_KEY_BASE64);
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // 6. 构造与密钥校验
    // ══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. 密钥校验")
    class KeyValidation {

        @Test
        @DisplayName("6.1 合法 Base64 密钥可构造，并能正常往返")
        void validBase64KeyWorks() {
            AesGcmUtil util = AesGcmUtil.fromBase64Key(TEST_KEY_BASE64);

            assertThat(util.decrypt(util.encrypt("正常"))).isEqualTo("正常");
        }

        @Test
        @DisplayName("6.2 密钥带首尾空格也能用（.env 复制粘贴常见问题，自动 trim）")
        void base64KeyWithWhitespaceTolerated() {
            AesGcmUtil util = AesGcmUtil.fromBase64Key("  " + TEST_KEY_BASE64 + "  ");

            assertThat(util.decrypt(util.encrypt("trim 检查"))).isEqualTo("trim 检查");
        }

        @Test
        @DisplayName("6.3 16 字节密钥（AES-128）被拒绝，且消息只报长度不报内容")
        void sixteenByteKeyRejected() {
            String key16 = Base64.getEncoder().encodeToString(new byte[16]);

            assertThatThrownBy(() -> AesGcmUtil.fromBase64Key(key16))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("32")
                    .hasMessageNotContaining(key16);
        }

        @Test
        @DisplayName("6.4 空密钥被拒绝")
        void blankKeyRejected() {
            assertThatThrownBy(() -> AesGcmUtil.fromBase64Key("   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("DIARY_ENCRYPTION_KEY");
        }

        @Test
        @DisplayName("6.5 null 密钥被拒绝")
        void nullKeyRejected() {
            assertThatThrownBy(() -> AesGcmUtil.fromBase64Key(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("6.6 非 Base64 密钥被拒绝，且消息里不含密钥内容")
        void nonBase64KeyRejected() {
            String notBase64 = "!!!这不是base64的密钥!!!";

            assertThatThrownBy(() -> AesGcmUtil.fromBase64Key(notBase64))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Base64")
                    .hasMessageNotContaining(notBase64);
        }

        @Test
        @DisplayName("6.7 密钥被修改后无法解密原密钥加密的数据（确认密钥真的参与了运算）")
        void keyIsActuallyUsed() {
            AesGcmUtil original = AesGcmUtil.fromBase64Key(TEST_KEY_BASE64);
            String ciphertext = original.encrypt("原始密钥加密的内容");

            byte[] mutatedKey = TEST_KEY.clone();
            mutatedKey[0] ^= 0x01;
            AesGcmUtil mutated = new AesGcmUtil(mutatedKey);

            assertThatThrownBy(() -> mutated.decrypt(ciphertext))
                    .isInstanceOf(DiaryDecryptionException.class);
        }
    }

    // ══════════════════════════════════════════════════════════
    // 7. 随机密钥冒烟（证明不依赖测试里的固定密钥）
    // ══════════════════════════════════════════════════════════

    @Test
    @DisplayName("7. 用随机生成的 32 字节密钥也能正常往返（与 .env 生成方式一致）")
    void randomKeyWorks() {
        byte[] key = new byte[AesGcmUtil.KEY_LENGTH];
        new SecureRandom().nextBytes(key);

        AesGcmUtil util = AesGcmUtil.fromBase64Key(Base64.getEncoder().encodeToString(key));

        assertThat(util.decrypt(util.encrypt("随机密钥测试"))).isEqualTo("随机密钥测试");
    }

    /** 从密文里切出 IV，用于验证格式与随机性。 */
    private static byte[] extractIv(String ciphertextBase64) {
        byte[] raw = Base64.getDecoder().decode(ciphertextBase64);
        byte[] iv = new byte[AesGcmUtil.IV_LENGTH];
        System.arraycopy(raw, 0, iv, 0, AesGcmUtil.IV_LENGTH);
        return iv;
    }
}
