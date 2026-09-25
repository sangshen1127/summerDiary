package com.sangshen.aidiary.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 启动期配置自检 —— 只在 dev profile 生效。
 *
 * <p>存在理由：本项目把 {@code .env} 作为本地配置真源，
 * 但 Spring Boot 原生不读 {@code .env}，靠
 * {@link DotenvEnvironmentPostProcessor} 注入。
 * 一旦注入失效，症状是「数据库密码不对」这种绕远的报错，
 * 排查时容易被误导到数据库侧。
 *
 * <p>这个组件在启动时明确回答三个问题：
 * <ol>
 *   <li>{@code .env} 有没有被加载成功？</li>
 *   <li>数据源最终解析到的 URL / 用户名是什么？</li>
 *   <li>密码来自哪个属性源（用于确认优先级符合预期）？</li>
 * </ol>
 *
 * <p><b>安全约束</b>：只打印密码的<b>长度和来源</b>，绝不打印密码内容。
 * 数据库 URL 和用户名不属于敏感信息。
 */
@Component
public class StartupConfigDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(StartupConfigDiagnostics.class);

    /**
     * 已知的占位值。出现这些值说明配置没填或没加载。
     *
     * <p>与 {@code application.yml} / {@code .env.example} 里的兜底值保持一致 —— 改那边记得改这里。
     */
    private static final Set<String> PLACEHOLDER_VALUES = Set.of(
            "change-me",
            "change-me-root",
            "replace-with-base64-encoded-32-bytes"
    );

    private final ConfigurableEnvironment environment;

    public StartupConfigDiagnostics(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void report() {
        String url = environment.getProperty("spring.datasource.url");
        String username = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");

        // ── 1. .env 是否被加载 ──────────────────────────────────
        boolean dotenvLoaded = false;
        for (PropertySource<?> ps : environment.getPropertySources()) {
            if (ps instanceof EnumerablePropertySource<?> eps) {
                for (String name : eps.getPropertyNames()) {
                    if (name.startsWith("DB_") || "DIARY_ENCRYPTION_KEY".equals(name)) {
                        dotenvLoaded = true;
                        break;
                    }
                }
            }
            if (dotenvLoaded) {
                break;
            }
        }

        log.info("========== 配置自检 ==========");
        log.info(".env 是否加载成功 : {}", dotenvLoaded ? "是 ✅" : "否 ❌（后端会用 application.yml 的默认值！）");
        log.info("datasource.url    : {}", url);
        log.info("datasource.username: {}", username);
        log.info("datasource.password: {}", describeSecret(password));

        // ── 2. 密码来自哪个属性源（确认优先级）──────────────────
        PropertySource<?> origin = findOrigin("spring.datasource.password");
        log.info("密码来源属性源     : {}", origin == null ? "(未找到)" : origin.getName());

        // ── 3. 关键属性来源，帮助判断 application.yml / .env 谁生效 ─
        log.info("DB_PORT 来源       : {}", nameOf(findOrigin("DB_PORT")));
        log.info("==============================");

        // ── 3.5 加密密钥自检 ────────────────────────────────────
        // 与上面的数据源自检并列：两者都是「配错了会在很久之后才炸」的配置。
        // 注意 DiaryCryptoConfig 已经做了严格的启动期校验（长度、Base64），
        // 这里<b>重复报告</b>不是冗余 —— 它回答的是另一个问题：
        // 「密钥有没有被读到」（来源），而不是「密钥对不对」（取值）。
        // 排查时这两个问题的方向完全不同。
        reportEncryptionKey();

        // ── 4. 占位值检查：把「静默降级」变成「启动即失败」──────
        failFastOnPlaceholderValues(password);
    }

    /**
     * 报告加密密钥的加载情况。
     *
     * <p>只输出：长度、来源属性源。绝不输出内容 —— 密钥泄露等于所有日记明文泄露。
     *
     * <p>这里<b>不</b>做长度校验：那是 {@code DiaryCryptoConfig} 的职责，
     * 而且它会在 Bean 创建期抛异常让启动失败，比这里"只报告不阻止"更强硬。
     * 两处都校验会让"到底谁负责"变得模糊。
     */
    private void reportEncryptionKey() {
        // 业务侧将来读的是 diary.encryption-key（由 DIARY_ENCRYPTION_KEY 宽松绑定而来）
        String boundValue = environment.getProperty("diary.encryption-key");
        // 直接读环境变量名，用于区分「变量没读到」和「绑定失败」这两种不同的问题
        String rawValue = environment.getProperty("DIARY_ENCRYPTION_KEY");
        PropertySource<?> origin = findOrigin("DIARY_ENCRYPTION_KEY");

        log.info("DIARY_ENCRYPTION_KEY 来源 : {}", nameOf(origin));
        log.info("DIARY_ENCRYPTION_KEY 状态 : {}", describeEncryptionKey(rawValue, boundValue));

        if (rawValue != null && boundValue == null) {
            // 这种情况说明属性源里有值但没绑定到 diary.encryption-key，
            // 基本只会是 DiaryProperties 的 prefix/字段名被改坏了。
            log.warn("DIARY_ENCRYPTION_KEY 有值但没绑定到 diary.encryption-key —— 检查 DiaryProperties 的 prefix 与字段名");
        }
    }

    /**
     * 把密钥状态描述成一句话。<b>只描述长度，绝不回显内容。</b>
     *
     * <p>预期长度是 44（Base64 编码 32 字节）。这里报 44 但实际解码失败的情况
     * 由 {@code DiaryCryptoConfig} 负责拦下，本方法只做粗筛。
     */
    private String describeEncryptionKey(String rawValue, String boundValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return "未配置 ❌（DiaryCryptoConfig 会让启动失败）";
        }
        if (PLACEHOLDER_VALUES.contains(rawValue.trim())) {
            return "仍是占位值 ❌（DiaryCryptoConfig 会让启动失败）";
        }
        if (boundValue == null) {
            return "已读到（长度 " + rawValue.trim().length() + "）但未绑定 ⚠️";
        }
        return "已配置 ✅（长度 " + rawValue.trim().length() + "，期望 44；内容不打印）";
    }

    /**
     * 检测到占位值时<b>直接让启动失败</b>，而不是带着错配置跑起来。
     *
     * <h2>为什么必须快速失败</h2>
     *
     * <p>踩过的真实场景：别人 clone 项目后忘了 {@code Copy-Item .env.example .env}，
     * 于是密码用了 {@code application.yml} 的兜底值 {@code change-me}。
     * 接着他看到的报错是：
     *
     * <pre>
     * Access denied for user 'ai_diary'@'localhost' (using password: YES)
     * </pre>
     *
     * <p>这个报错<b>指向数据库</b>，会让人去查 MySQL 用户权限、密码、网络，
     * 而真正的原因是「配置文件压根没创建」。排查方向被完全带偏。
     *
     * <p><b>静默降级比直接报错难查得多。</b>所以这里主动抛异常，
     * 把错误信息换成可操作的步骤。
     *
     * <h2>为什么不用兜底默认密码</h2>
     *
     * <p>给一个「能连上的默认密码」看似友好，实际更糟：它会掩盖配置缺失，
     * 而且一旦这个默认值在别人的环境里恰好能连上，就会长期潜伏。
     * 配置缺失就应该响亮地失败。
     *
     * <h2>确需使用 change-me 作为真实密码时</h2>
     *
     * <p>比如你确实想让 MySQL 用 {@code change-me} 这个密码（不推荐但合法）。
     * 此时把 {@code .env} 里的 {@code DB_PASSWORD} 用引号包起来：
     *
     * <pre>
     * DB_PASSWORD="change-me"     # 加引号 → 视为显式配置，跳过本检查
     * DB_PASSWORD=change-me       # 不加引号 → 视为占位值，启动失败
     * </pre>
     *
     * <p>解析逻辑见 {@link DotenvEnvironmentPostProcessor#unquote}：
     * 带引号的值会去掉引号，不带引号的会去掉行尾注释 ——
     * 所以从值本身无法区分两者，这里通过「值是否等于占位值」判断，
     * 而加引号是一种明确的「我知道我在做什么」的意图表达。
     * 若引号方案不便，也可加 JVM 参数 {@code -Dapp.config.skip-placeholder-check=true}
     * 显式跳过。
     */
    private void failFastOnPlaceholderValues(String password) {
        if (Boolean.parseBoolean(
                environment.getProperty("app.config.skip-placeholder-check", "false"))) {
            log.warn("已通过 app.config.skip-placeholder-check 跳过占位值检查");
            return;
        }

        if (PLACEHOLDER_VALUES.contains(password)) {
            // 注意：分隔线用等号等 ASCII 字符，不要用 ═ ─ 这类框线字符 ——
            // 在 Windows 控制台（GBK 代码页）下会显示成乱码，影响可读性。
            // 错误信息要给出「下一步做什么」，不只是「错了」。
            throw new IllegalStateException("""

                    ============================================================
                    配置缺失：数据库密码仍是占位值 "%s"
                    ============================================================
                    这说明 .env 没有被加载，或者 .env 里的 DB_PASSWORD 没改。

                    修复步骤（在项目根目录 D:\\summerDiary 下执行）：
                      1. Copy-Item .env.example .env
                      2. 编辑 .env，填写：
                           DB_PASSWORD=<你的密码，需与 docker compose 用的一致>
                           DIARY_ENCRYPTION_KEY=<Base64 编码的 32 字节>

                    生成加密密钥（PowerShell）：
                      [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 }))

                    [注意] 若你刚刚改过 .env，要知道 MySQL 只在「首次初始化
                           数据卷」时创建账号。改了密码需要重建数据卷：
                             docker compose rm -sf mysql
                             docker volume rm ai-diary-mysql-data
                             docker compose up -d mysql

                    确需把 "%s" 当作真实密码使用？见本类
                    failFastOnPlaceholderValues 的 Javadoc。
                    ============================================================
                    """.formatted(password, password));
        }
    }

    /** 只报长度和是否为空，绝不报内容。 */
    private String describeSecret(String value) {
        if (value == null) {
            return "(null)";
        }
        if (value.isEmpty()) {
            return "(空字符串)";
        }
        return "(长度 " + value.length() + "，内容不打印)";
    }

    /** 遍历属性源找出哪个能解析出该 key。注意 getPropertySources 的顺序就是优先级顺序。 */
    private PropertySource<?> findOrigin(String key) {
        for (PropertySource<?> ps : environment.getPropertySources()) {
            if (ps.containsProperty(key)) {
                return ps;
            }
        }
        return null;
    }

    private String nameOf(PropertySource<?> ps) {
        return ps == null ? "(未找到)" : ps.getName();
    }
}
