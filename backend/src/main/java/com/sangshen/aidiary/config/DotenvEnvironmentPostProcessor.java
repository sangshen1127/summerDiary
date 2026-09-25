package com.sangshen.aidiary.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把项目根目录的 {@code .env} 加载进 Spring Environment。
 *
 * <h2>为什么需要这个类</h2>
 *
 * <p>本项目用 {@code .env} 作为「唯一的本地配置真源」，因为 {@code docker compose}
 * 会自动读取它 —— 这一点让很多人以为 Spring Boot 也会读。<b>但 Spring Boot 不会。</b>
 *
 * <p>不加这个类的后果（真实踩过的坑）：
 * <pre>
 * .env 里写        DB_PASSWORD=ai_diary_dev_pw
 * docker compose   读 .env → MySQL 容器用这个密码建库建用户   ✅
 * Spring Boot      不读 .env → 退回 application.yml 的默认值 change-me  ❌
 *
 * 于是启动时报：Access denied for user 'ai_diary'@'localhost' (using password: YES)
 * </pre>
 *
 * <p>Spring Boot 只认这几种配置来源：{@code application.yml}、
 * {@code application-{profile}.yml}、JVM 系统属性、以及<b>操作系统环境变量</b>。
 * {@code .env} 不在其中。
 *
 * <h2>为什么用 EnvironmentPostProcessor</h2>
 *
 * <p>它在「环境准备好之后、任何 Bean 创建之前」执行，也就是<b>早于数据源初始化</b>。
 * 如果用 {@code @PropertySource} 或 {@code @PostConstruct}，
 * 那时 {@code DataSource} 已经用错误的密码建好了，来不及。
 *
 * <h2>优先级设计（关键）</h2>
 *
 * <p>本属性源被加为 <b>最低优先级</b>（{@code addLast}）。所以：
 * <pre>
 * 操作系统环境变量  &gt;  JVM -D 参数  &gt;  application.yml  &gt;  .env
 * </pre>
 *
 * <p>这个顺序是刻意的：
 * <ul>
 *   <li>CI / 生产环境用真实的操作系统环境变量注入密钥，能覆盖 {@code .env}（容器里通常没有 .env）</li>
 *   <li>命令行临时调试用 {@code -Dkey=value} 或
 *       {@code --spring.datasource.password=xxx} 也能覆盖</li>
 *   <li>{@code .env} 只是本地开发的便利层，优先级最低最合理</li>
 * </ul>
 *
 * <h2>安全说明</h2>
 *
 * <p>本类只把值读进内存，不打印、不落盘。日志红线（不含密钥）由本类保证 ——
 * 解析异常只报行号，不报内容。
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE_NAME = "dotenvFile";

    /** 从运行目录向上最多找 3 层，兼容「在 backend/ 下运行」和「在项目根运行」两种方式 */
    private static final int MAX_PARENT_LOOKUP = 3;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        Path envFile = locateEnvFile();
        if (envFile == null) {
            // 找不到 .env 是完全正常的（CI/生产就没有），静默跳过。
            // 此时配置全部来自 application.yml + 操作系统环境变量。
            return;
        }

        Map<String, Object> values;
        try {
            values = parse(envFile);
        } catch (IOException e) {
            // ⚠️ 只报路径和异常类型，绝不打印文件内容（里面可能有密钥）
            System.err.println("[Dotenv] 无法读取 " + envFile + "（" + e.getClass().getSimpleName()
                    + "），将只使用 application.yml 与系统环境变量");
            return;
        }

        if (values.isEmpty()) {
            return;
        }

        // addLast → 优先级最低，系统环境变量和 application.yml 都能覆盖它
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, values));
    }

    /**
     * 查找 .env。
     *
     * <p>查找顺序：
     * <ol>
     *   <li>系统属性 {@code dotenv.path} 指定的路径（便于测试和特殊部署）</li>
     *   <li>当前工作目录及其上溯 3 层内的 {@code .env}</li>
     * </ol>
     *
     * <p>为什么要上溯：IDEA 默认把工作目录设成模块目录（{@code backend/}），
     * 而 {@code .env} 在项目根。命令行 {@code mvn spring-boot:run} 在
     * {@code backend/} 下执行也一样。上溯一层就能找到。
     * 生产环境把 jar 放在任意目录，找不到就跳过，行为正确。
     */
    private Path locateEnvFile() {
        String explicit = System.getProperty("dotenv.path");
        if (explicit != null && !explicit.isBlank()) {
            Path p = Paths.get(explicit);
            return Files.isRegularFile(p) ? p : null;
        }

        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i <= MAX_PARENT_LOOKUP && dir != null; i++) {
            Path candidate = dir.resolve(".env");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    /**
     * 解析 .env 文件。
     *
     * <p>支持的语法（刻意保持最小，不引入 dotenv 依赖）：
     * <pre>
     * KEY=value                  → 普通赋值
     * KEY="value with spaces"    → 双引号包裹（# 不会被当注释）
     * KEY='value'                → 单引号包裹
     * # comment                  → 整行注释
     * KEY=value   # trailing     → 行尾注释（仅未加引号时）
     * export KEY=value           → 兼容 shell 习惯，忽略 export 前缀
     * </pre>
     *
     * <p>不支持的语法：变量插值（{@code KEY=$OTHER}）、多行值。
     * 本项目用不到，需要时应换用成熟的 dotenv 库而不是在这里堆功能。
     */
    private Map<String, Object> parse(Path envFile) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(envFile, StandardCharsets.UTF_8);

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw == null) {
                continue;
            }

            // 去掉 UTF-8 BOM（某些编辑器会写）
            if (i == 0 && !raw.isEmpty() && raw.charAt(0) == '\uFEFF') {
                raw = raw.substring(1);
            }

            String line = raw.trim();

            // 跳过空行和整行注释
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            // 兼容 `export KEY=value`
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).trim();
            }

            int eq = line.indexOf('=');
            if (eq <= 0) {
                // 没有 = 或 = 在开头，不是合法赋值，跳过
                continue;
            }

            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();

            if (key.isEmpty()) {
                continue;
            }

            result.put(key, unquote(value));
        }

        return result;
    }

    /**
     * 去掉包裹引号并处理行尾注释。
     *
     * <p>注意：加引号的值<b>不</b>去掉行尾的 {@code #}，
     * 因为密码里可能真的含 {@code #}。这也是推荐用引号包裹密码的原因。
     */
    private String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);

            if (first == '"' && last == '"') {
                return value.substring(1, value.length() - 1);
            }
            if (first == '\'' && last == '\'') {
                return value.substring(1, value.length() - 1);
            }
        }

        // 未加引号：允许行尾注释（前面要有空格，避免误伤含 # 的密码）
        int hash = value.indexOf(" #");
        if (hash > 0) {
            return value.substring(0, hash).trim();
        }

        return value;
    }
}
