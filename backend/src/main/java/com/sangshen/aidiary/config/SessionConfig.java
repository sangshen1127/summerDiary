package com.sangshen.aidiary.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.servlet.SessionCookieConfig;

/**
 * 会话 Cookie 配置 —— 直接作用在 Servlet 容器层面。
 *
 * <h2>为什么不用 {@code server.servlet.session.cookie.*} 和 Spring Session 的 CookieSerializer</h2>
 *
 * <p>{@code server.servlet.session.cookie.*} 这几个属性确实存在
 * （已通过读取 {@code spring-configuration-metadata.json} 验证），
 * 但有两个问题：
 * <ol>
 *   <li><b>secure 属性不能跟随自定义环境变量。</b>
 *       Boot 只认 {@code SERVER_SERVLET_SESSION_COOKIE_SECURE} 这种
 *       完整前缀环境变量，而我们用的是简短的 {@code AUTH_COOKIE_SECURE}。
 *       两套命名并存会让"到底哪个生效"变得难判断。</li>
 *   <li><b>Spring Session 的 {@code CookieSerializer} 不一定被接管。</b>
 *       它需要 Spring Session 的过滤器或解析器介入，若项目没有引入
 *       Spring Session 的自动配置，那个 Bean 会被创建但<b>永远不生效</b> ——
 *       正是我们反复强调的"静默失效"类型的问题。</li>
 * </ol>
 *
 * <p>改用 {@link WebServerFactoryCustomizer}：直接改写容器的
 * {@link SessionCookieConfig}，<b>必然生效</b>，且没有隐藏依赖。
 *
 * <h2>Servlet 6.0 的 setAttribute</h2>
 *
 * <p>SameSite 不是标准的 Cookie 属性（虽然所有现代浏览器都支持），
 * 所以没有专属 setter，要借 {@code setAttribute("SameSite", "Lax")}。
 * 这是 Servlet 6.0 新增的能力，Tomcat 10 支持。
 */
@Configuration
public class SessionConfig {

    /**
     * 定制会话 Cookie。
     *
     * <table border="1">
     *   <caption>各属性的作用</caption>
     *   <tr><th>属性</th><th>作用</th></tr>
     *   <tr>
     *     <td><b>HttpOnly</b></td>
     *     <td><b>核心安全属性。</b>JavaScript 完全读不到这个 Cookie
     *         （{@code document.cookie} 里看不见）。
     *         日记应用要渲染用户 Markdown 与 AI 输出，XSS 攻击面天然较大；
     *         一旦被注入脚本，HttpOnly 能保证会话 ID 偷不走。</td>
     *   </tr>
     *   <tr>
     *     <td><b>SameSite=Lax</b></td>
     *     <td>跨站点请求时默认不发送 Cookie —— <b>防 CSRF</b>。
     *         选 Lax 而非 Strict：Strict 会让"从外部链接点进来"时
     *         登录态丢失；Lax 在「顶级导航 + GET」时仍发送，安全与体验平衡。</td>
     *   </tr>
     *   <tr>
     *     <td><b>Secure</b></td>
     *     <td>{@code true} 时浏览器只在 HTTPS 下发送。
     *         <b>生产必须 true</b>；本地开发是 http，设 true 会导致
     *         Cookie 根本发不出去，所以本地为 false。</td>
     *   </tr>
     *   <tr>
     *     <td>Name</td>
     *     <td>默认的 {@code JSESSIONID} 太通用，多应用部署在同一域名下
     *         可能互相覆盖。用带项目前缀的名字避免冲突。</td>
     *   </tr>
     * </table>
     *
     * @param cookieName    Cookie 名称，来自 {@code AUTH_COOKIE_NAME}
     * @param secure        是否只允许 HTTPS，来自 {@code AUTH_COOKIE_SECURE}
     * @param activeProfile 当前激活的 profile，用于生产环境安全断言
     * @return 容器工厂定制器
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> sessionCookieCustomizer(
            @Value("${auth.cookie.name:AI_DIARY_SESSION}") String cookieName,
            @Value("${auth.cookie.secure:false}") boolean secure,
            @Value("${spring.profiles.active:dev}") String activeProfile) {

        // ── 生产环境安全断言 ────────────────────────────────────
        // 生产用 http 是配置错误，而且症状隐蔽（Cookie 时有时无、
        // 或者被中间人窃取）。这里直接让启动失败，逼着配置改对。
        // 这是「快速失败优于带病运行」原则的又一次应用。
        if ("prod".equals(activeProfile) && !secure) {
            throw new IllegalStateException("""
                    ============================================================
                    生产环境配置错误：AUTH_COOKIE_SECURE 必须为 true
                    ============================================================
                    Secure=false 时，浏览器会在明文 HTTP 上发送会话 Cookie，
                    会话 ID 可被中间人窃取，等同于账号可被窃取。

                    修复：在服务器环境变量中设置
                        AUTH_COOKIE_SECURE=true
                    并确保站点通过 HTTPS 访问。
                    ============================================================
                    """);
        }

        return factory -> factory.addInitializers(servletContext -> {
            SessionCookieConfig config = servletContext.getSessionCookieConfig();

            config.setName(cookieName);
            config.setPath("/");
            config.setHttpOnly(true);
            config.setSecure(secure);

            // SameSite 没有专属 setter，借 setAttribute 传递（Servlet 6.0）
            config.setAttribute("SameSite", "Lax");

            // 不设 MaxAge：保持会话级 Cookie（关浏览器失效），
            // 真正的有效期由 server.servlet.session.timeout 控制（我们配 7 天）。
            // 两处都设会让"哪个说了算"变得难判断，让服务端唯一掌控更清晰。
        });
    }
}
