package com.sangshen.aidiary.config;

import com.sangshen.aidiary.common.crypto.AesGcmUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证「环境变量 → application.yml → DiaryProperties」这条绑定链真的接通了。
 *
 * <h2>为什么需要这个测试（单元测试测不到它）</h2>
 *
 * <p>{@code DiaryCryptoConfigTest} 手动 new 出 {@code DiaryProperties} 再塞值，
 * 所以它<b>永远测不到绑定环节</b>。而模块 2-1 真正踩的坑恰好就在这里：
 *
 * <pre>
 * .env 里 DIARY_ENCRYPTION_KEY 有值（44 字符）
 *   → Environment 里读得到（StartupConfigDiagnostics 已确认）
 *   → 但 application.yml 少了 diary.encryption-key: ${DIARY_ENCRYPTION_KEY:}
 *   → DiaryProperties.encryptionKey 恒为空 → 启动失败
 * </pre>
 *
 * <p>当时那 46 条单元测试<b>全绿</b>，因为这个缺陷不在 Java 代码里，
 * 而在 <b>YAML 与配置属性类的接缝</b>上。
 *
 * <h2>为什么不直接用 @SpringBootTest</h2>
 *
 * <p>试过，但本项目环境下会有额外噪音：{@code @SpringBootTest} 会拉起
 * Spring TestContext 框架并触发 Mockito 初始化，而 Mockito 的 ByteBuddy
 * 需要 self-attach 到当前 JVM（会 spawn 外部进程），在受限沙箱里会失败。
 * 更要紧的是：<b>本测试根本不需要 Spring 容器</b> —— 它要验证的是
 * 「YAML 文本 + 环境变量 → 配置属性」这个纯函数式的转换。
 *
 * <p>所以这里改为：加载真实的 {@code application.yml} → 放进一个
 * {@code StandardEnvironment} → 用 Spring Boot 自己的 {@link Binder} 绑定。
 * 用的仍然是真的 YAML、真的 {@code ${}} 解析、真的绑定实现，
 * 但启动代价几乎为零，也不依赖任何测试上下文框架。
 *
 * <h2>关于跳过</h2>
 *
 * <p>{@code DIARY_ENCRYPTION_KEY} 没配时，第 2、3 条会退化为只验证
 * 「键存在且值来自环境变量」的形式（用一个注入的假值），保证 CI 上也能跑。
 */
@DisplayName("配置绑定链：DIARY_ENCRYPTION_KEY → application.yml → DiaryProperties")
class DiaryPropertiesBindingTest {

    /** 与 .env.example 保持一致的期望长度：Base64(32 字节) = 44 字符。 */
    private static final int EXPECTED_BASE64_LENGTH = 44;

    /** 注入用的假密钥。仅用于验证绑定通道，与真实密钥无关。 */
    private static final String SYNTHETIC_KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    /** 真实的 application.yml 内容。加载一次，三条测试共用。 */
    private static Properties applicationYml;

    @BeforeAll
    static void loadRealApplicationYml() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        applicationYml = factory.getObject();
        assertThat(applicationYml)
                .as("application.yml 必须能被加载 —— 加载失败说明打包或资源路径有问题")
                .isNotNull()
                .isNotEmpty();
    }

    /** 用指定的 KEY 值构造 Environment（真实 YAML + 该环境变量），并绑定到 DiaryProperties。 */
    private static DiaryProperties bindWith(String encryptionKeyValue) {
        StandardEnvironment environment = new StandardEnvironment();
        // 模拟操作系统环境变量 / .env 注入的属性源，优先级高于 application.yml
        // （与 DotenvEnvironmentPostProcessor 的 addLast 语义不同，但这里只关心
        //  『占位符能否解析到环境变量』，用高优先级反而更能暴露占位符缺失）
        Properties envVars = new Properties();
        envVars.setProperty("DIARY_ENCRYPTION_KEY", encryptionKeyValue);
        environment.getPropertySources().addFirst(new PropertiesPropertySource("testEnvVars", envVars));

        // application.yml 作为较低优先级的属性源加入
        environment.getPropertySources().addLast(new PropertiesPropertySource("applicationYml", applicationYml));

        // ⚠️ 关键：必须显式传占位符解析器。
        //
        // Binder 自己<b>不会</b>解析 ${...} —— 它默认用一个「原样返回」的
        // PlaceholdersResolver。少了这一步，application.yml 里的
        //     diary.encryption-key: ${DIARY_ENCRYPTION_KEY:}
        // 会被原封不动地当成字面量绑进去（实测拿到的是字符串
        // "${DIARY_ENCRYPTION_KEY:}" 本身）。
        //
        // Spring Boot 在 SpringApplication 内部绑定配置时，用的是
        // PropertySourcesPlaceholdersResolver；这里保持一致，
        // 否则测试验证的就不是真实行为。
        Binder binder = new Binder(
                ConfigurationPropertySources.from(environment.getPropertySources()),
                new PropertySourcesPlaceholdersResolver(environment));

        return binder.bind("diary", Bindable.of(DiaryProperties.class))
                .orElseGet(DiaryProperties::new);
    }

    @Test
    @DisplayName("1. application.yml 里必须有 diary.encryption-key 的 ${} 显式引用（防回归核心）")
    void applicationYmlDeclaresExplicitReference() {
        String declared = applicationYml.getProperty("diary.encryption-key");

        assertThat(declared)
                .as("""
                        application.yml 里找不到 diary.encryption-key —— 绑定链断了。

                        必须补上（详见开发文档 §6.2.10）：
                            diary:
                              encryption-key: ${DIARY_ENCRYPTION_KEY:}

                        注意：Spring Boot 的宽松绑定『不会』把环境变量
                        DIARY_ENCRYPTION_KEY 自动映射到 diary.encryption-key。
                        删掉这行 → 属性恒为空 → 启动失败。
                        """)
                .isNotNull()
                .contains("DIARY_ENCRYPTION_KEY");
    }

    @Test
    @DisplayName("2. 环境变量的值能真正穿透到 DiaryProperties.encryptionKey")
    void environmentVariableReachesProperties() {
        DiaryProperties properties = bindWith(SYNTHETIC_KEY);

        assertThat(properties.getEncryptionKey()).isEqualTo(SYNTHETIC_KEY);
    }

    @Test
    @DisplayName("3. 换一个值也照样穿透（证明不是碰巧绑上了默认值）")
    void differentValueAlsoBinds() {
        String another = Base64.getEncoder()
                .encodeToString("abcdefabcdefabcdefabcdefabcdefab".getBytes(StandardCharsets.UTF_8));

        assertThat(bindWith(another).getEncryptionKey()).isEqualTo(another);
    }

    @Test
    @DisplayName("4. 环境变量缺失时兜底为空字符串（而不是拿到某个默认可用密钥），装配必然失败")
    void missingVariableYieldsEmptyString() {
        DiaryProperties properties = bindWith("");

        assertThat(properties.getEncryptionKey()).isEmpty();
        // 兜底为空 → DiaryCryptoConfig 拦下并让启动失败，不会静默用假密钥
        assertThatThrownBy(() -> new DiaryCryptoConfig().aesGcmUtil(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("配置缺失");
    }

    @Test
    @DisplayName("5. 用真实环境变量（若已配置）跑通完整加解密，验证拿到的是可用密钥")
    void realConfiguredKeyWorksEndToEnd() {
        String realKey = System.getenv("DIARY_ENCRYPTION_KEY");
        if (realKey == null || realKey.isBlank()) {
            // 没配就只验证合成密钥这条通道（第 2、3 条已覆盖）
            realKey = SYNTHETIC_KEY;
        }

        DiaryProperties properties = bindWith(realKey);

        assertThat(properties.getEncryptionKey()).hasSize(realKey.trim().length());

        AesGcmUtil util = new DiaryCryptoConfig().aesGcmUtil(properties);
        String plaintext = "模块 2-1 绑定链端到端验证：用配置里的密钥加密这句话。";
        assertThat(util.decrypt(util.encrypt(plaintext))).isEqualTo(plaintext);

        // 真实密钥应为 44 字符；合成密钥同样。只断言长度，不回显内容（日志红线）。
        if (System.getenv("DIARY_ENCRYPTION_KEY") != null) {
            assertThat(properties.getEncryptionKey().trim()).hasSize(EXPECTED_BASE64_LENGTH);
        }
    }
}
