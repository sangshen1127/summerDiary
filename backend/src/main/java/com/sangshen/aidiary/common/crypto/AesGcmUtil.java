package com.sangshen.aidiary.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 日记正文加解密工具（AES-256-GCM）。
 *
 * <p>契约见开发文档 §5.3。本类<b>只暴露</b> {@link #encrypt(String)} 与
 * {@link #decrypt(String)} 两个方法 —— 调用方（只有 {@code DiaryServiceImpl}）
 * 不需要知道 IV、tag、编码格式的任何细节。
 *
 * <h2>一、为什么是 GCM 而不是 CBC</h2>
 *
 * <p>CBC 只保证<b>机密性</b>：能防"看到内容"。但它不保证<b>完整性</b> ——
 * 有人改了密文里一个字节，CBC 解密后只会得到一段乱码，<b>不会报错</b>。
 * 对日记应用来说这很危险：数据库被改动后我们完全察觉不到。
 *
 * <p>GCM 是 AEAD（带关联数据的认证加密）模式，它在加密的同时算出一个
 * 16 字节的 <b>认证标签（authTag）</b>。解密时先验证标签：
 * 只要密文、IV、标签任何一处被改过，解密就<b>抛异常</b>而不是返回垃圾数据。
 * 所以"防篡改"是免费的，不需要额外做 HMAC。
 *
 * <h2>二、存储格式（自描述，单列可存）</h2>
 *
 * <pre>
 * content_ciphertext = Base64( IV(12字节) || ciphertext || authTag(16字节) )
 * </pre>
 *
 * <p>三个部分拼在一个 {@code TEXT} 列里，解密时从前面切 12 字节就是 IV、
 * 后面切 16 字节就是 tag，中间全是密文 —— 不需要额外的列记录长度。
 *
 * <p>用 Base64 文本而不是 {@code BLOB}：便于直接在数据库客户端里查看和比对，
 * 代价是体积比原始字节大 33%。
 *
 * <h2>三、⚠️ IV 绝对不能复用</h2>
 *
 * <p>GCM 的安全性<b>建立在一个前提上</b>：同一个密钥下，每个 IV 只用一次。
 * 一旦同一个 (密钥, IV) 组合加密了两段不同的明文，攻击者不需要密钥就能
 * 异或出两段明文的差异，认证标签也会失效 —— 这是 GCM 的<b>灾难性</b>失败模式，
 * 不是"稍微弱一点"。
 *
 * <p>所以：<b>每次加密都调用 {@link SecureRandom} 生成一个新的 12 字节 IV</b>，
 * 存进密文里。<b>不要</b>把 IV 做成字段、常量、或按日记 ID 派生。
 *
 * <h2>四、为什么不用零填充（NoPadding 的真实含义）</h2>
 *
 * <p>{@code AES/GCM/NoPadding} 里的 {@code NoPadding} <b>不是</b>"缺少填充导致
 * 长度泄露"的意思。GCM 是流式模式（CTR 家族），明文长度就是密文长度，
 * 本来就不需要填充。这里写 {@code NoPadding} 是 JCE 的硬性要求：
 * GCM 只接受它，写 {@code PKCS5Padding} 会直接报错。
 *
 * <h2>五、失败时抛什么</h2>
 *
 * <p>解密失败（密钥不对、密文被篡改、密文损坏）统一抛
 * {@link DiaryDecryptionException}，它是未检查异常，由
 * {@code GlobalExceptionHandler} 兜成 {@code 50001}。
 *
 * <p><b>刻意不抛 {@code BusinessException}</b>：那不是"用户操作导致的业务失败"，
 * 而是服务端数据或配置出了问题。返回 4xx 会误导前端以为"改个参数就能重试"。
 *
 * <h2>六、线程安全</h2>
 *
 * <p>本类无状态：{@link SecureRandom} 自身线程安全，{@link Cipher} 每次调用
 * 在方法内新建（{@code Cipher} <b>不是</b>线程安全的，绝不要做成字段复用）。
 * 所以它可以安全地作为单例 Bean 注入。
 */
public final class AesGcmUtil {

    /** 变换名。GCM 在 JCE 里必须配 {@code NoPadding}，见类注释第四节。 */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** 算法族名，用于构造 {@link SecretKeySpec}。 */
    private static final String ALGORITHM = "AES";

    /**
     * IV 长度：12 字节（96 位）。
     *
     * <p>这是 GCM 的<b>推荐值</b>。GCM 规范允许其他长度，但非 96 位的 IV 会先被
     * 内部哈希再使用，既慢又更容易用错。JCE 对非 96 位 IV 的行为在不同版本上
     * 不完全一致，固定 12 字节是最不容易出错的选法。
     */
    public static final int IV_LENGTH = 12;

    /**
     * 认证标签长度：16 字节（128 位）。
     *
     * <p>这是 GCM 支持的最长标签，安全强度最高。<b>不要</b>为了省 14 个字节
     * 降到 12 或 8 —— 标签越短，伪造密文的难度越低。
     */
    public static final int TAG_LENGTH_BITS = 128;

    /** 密钥长度：32 字节（256 位）。 */
    public static final int KEY_LENGTH = 32;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param keyBytes 32 字节原始密钥。调用方负责确保长度正确；
     *                 长度不对会在这里立刻失败，而不是等到第一次加密
     *                 （快速失败：配置错误应该在启动时就暴露）
     */
    public AesGcmUtil(byte[] keyBytes) {
        if (keyBytes == null) {
            throw new IllegalArgumentException("AES 密钥不能为 null");
        }
        if (keyBytes.length != KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "AES-256 密钥必须是 " + KEY_LENGTH + " 字节，实际 " + keyBytes.length + " 字节");
        }
        this.key = new SecretKeySpec(keyBytes, ALGORITHM);
    }

    /**
     * 从 Base64 字符串构造。
     *
     * <p>对应环境变量 {@code DIARY_ENCRYPTION_KEY} 的格式
     * （{@code openssl rand -base64 32} 的输出）。
     *
     * @param base64Key Base64 编码的 32 字节密钥
     * @throws IllegalArgumentException 不是合法 Base64，或解码后不是 32 字节
     */
    public static AesGcmUtil fromBase64Key(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException("DIARY_ENCRYPTION_KEY 未配置（为空或 null）");
        }

        byte[] decoded;
        try {
            // 用 getDecoder（严格模式）而不是 getMimeDecoder：
            // Mime 解码器会静默忽略非法字符，导致"密钥看起来对但其实是错的"。
            decoded = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException ex) {
            // 不要把 base64Key 拼进异常消息 —— 它是密钥（开发文档 §5.4 红线）
            throw new IllegalArgumentException("DIARY_ENCRYPTION_KEY 不是合法的 Base64 字符串", ex);
        }

        try {
            return new AesGcmUtil(decoded);
        } finally {
            // 解码出来的中间数组用完就抹掉，减少密钥在堆里的副本数量。
            // 注意：这不能"消灭"密钥 —— SecretKeySpec 内部还会持有一份副本，
            // 真正的防护是靠"不打印、不进日志"。这里只是顺手减少一份残留。
            Arrays.fill(decoded, (byte) 0);
        }
    }

    /**
     * 加密。
     *
     * <p>每次调用都会生成<b>新的随机 IV</b>，所以同一段明文加密两次得到的
     * 密文<b>一定不同</b>（这是正确行为，不是 bug —— 它意味着攻击者无法通过
     * 比对密文判断两篇日记内容是否相同）。
     *
     * @param plaintext 明文正文。允许为空字符串（用户可能只写了标题）；
     *                  但<b>不允许 null</b> —— 数据库列是 {@code NOT NULL}，
     *                  而且 null 会让"忘了判空"的问题藏到更远的地方
     * @return Base64(IV || ciphertext || authTag)
     * @throws IllegalArgumentException plaintext 为 null
     * @throws IllegalStateException    加密失败（正常情况下不会发生，
     *                                  密钥已构造期校验过）
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("待加密的正文不能为 null（空内容请传空字符串）");
        }

        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // IV || (ciphertext + authTag)
            // JCE 的 AES/GCM 会把 authTag 自动附在 doFinal 返回值的末尾，
            // 所以不需要手动拼接 tag —— 见开发文档 §5.3 的说明。
            byte[] combined = new byte[IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(ciphertext, 0, combined, IV_LENGTH, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);

        } catch (GeneralSecurityException ex) {
            // 走到这里基本只可能是 JDK 环境问题（比如策略文件限制），
            // 属于部署故障，记 error 让运维能立刻发现。
            throw new IllegalStateException("日记正文加密失败", ex);
        }
    }

    /**
     * 解密。
     *
     * <p>解密前会验证认证标签，所以下面三种情况都会失败（而不是返回乱码）：
     * <ul>
     *   <li>密文/IV/标签被改动过</li>
     *   <li>用了不同的密钥（例如别人的 {@code DIARY_ENCRYPTION_KEY}）</li>
     *   <li>密文被截断或格式损坏</li>
     * </ul>
     *
     * @param ciphertextBase64 {@link #encrypt(String)} 的返回值
     * @return 明文正文
     * @throws DiaryDecryptionException 任何解密失败。异常消息<b>不含</b>密文和密钥，
     *                                  避免它们随堆栈进入日志（开发文档 §5.4）
     */
    public String decrypt(String ciphertextBase64) {
        if (ciphertextBase64 == null) {
            throw new DiaryDecryptionException("待解密的密文为 null");
        }

        byte[] combined;
        try {
            combined = Base64.getDecoder().decode(ciphertextBase64);
        } catch (IllegalArgumentException ex) {
            // 不把密文内容拼进消息：密文本身虽不可直接读，但也属于用户数据
            throw new DiaryDecryptionException("密文不是合法的 Base64 格式");
        }

        // 至少要能装下 IV + 一个空明文对应的 tag
        int minimumLength = IV_LENGTH + TAG_LENGTH_BITS / 8;
        if (combined.length < minimumLength) {
            throw new DiaryDecryptionException(
                    "密文长度不足（至少需要 " + minimumLength + " 字节，实际 " + combined.length + " 字节）");
        }

        byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH);
        int ciphertextLength = combined.length - IV_LENGTH;
        byte[] ciphertext = Arrays.copyOfRange(combined, IV_LENGTH, combined.length);

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            byte[] plaintext = cipher.doFinal(ciphertext, 0, ciphertextLength);
            return new String(plaintext, StandardCharsets.UTF_8);

        } catch (GeneralSecurityException ex) {
            // 这里最常见的是 AEADBadTagException（认证标签校验失败）。
            // 刻意不把它当成"系统错误"上抛：对上层来说它就是一个"解不开"的结果。
            // 具体原因（密钥不对 vs 密文被改）无法区分，也不应该在响应里区分 ——
            // 那等于告诉攻击者"你猜对了密钥长度但内容不对"。
            throw new DiaryDecryptionException("日记正文解密失败，可能是密钥不匹配或数据已被篡改", ex);
        }
    }
}
