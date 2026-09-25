package com.sangshen.aidiary.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sangshen.aidiary.common.ErrorCode;
import com.sangshen.aidiary.common.Result;
import com.sangshen.aidiary.security.UnmappedPathFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Spring Security 配置。
 *
 * <p>认证方案：<b>HttpOnly + SameSite Cookie 会话</b>（开发文档 §5.1）。
 * 全项目统一这一套，不允许各接口自行决定。
 *
 * <h2>为什么不用 JWT</h2>
 *
 * <p>JWT 的核心价值是「服务端无状态，便于跨服务共享登录态」——
 * 这个价值只在<b>微服务</b>架构下成立。本项目是单体（一个 JAR 一个进程），
 * 不存在跨服务共享问题，用 JWT 只会带来「签发后无法立即吊销」的麻烦。
 *
 * <p>Cookie 会话还能顺便获得两个好处：
 * <ol>
 *   <li>{@code HttpOnly} 让 JS 读不到会话 ID，抗 XSS 窃取 ——
 *       日记应用要渲染 Markdown 和 AI 输出，XSS 攻击面天然较大</li>
 *   <li>{@code session.invalidate()} 可立即踢下线 ——
 *       开发文档 §11 要求用户删除账户后会话必须立刻失效，JWT 做不到</li>
 * </ol>
 *
 * <h2>Phase 1 相对 Phase 0 的变化</h2>
 *
 * <p>Phase 0 是 {@code anyRequest().permitAll()}（骨架阶段还没有登录接口）。
 * 现在替换为细粒度授权，并接上统一的 401 / 403 处理。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 安全上下文仓库 —— 与 {@code AuthServiceImpl} 里用的是同一个类。
     *
     * <p>必须显式声明：Spring Security 6 默认用
     * {@code RequestAttributeSecurityContextRepository}，它只在<b>单次请求内</b>
     * 保存上下文，请求结束就丢了 —— 表现就是「登录成功，但下一个请求又 401」。
     *
     * <p>换成 {@link HttpSessionSecurityContextRepository} 后，
     * 认证信息存进 Servlet 会话，后续请求由 {@code SecurityContextHolderFilter}
     * 自动恢复。
     */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * ⚠️ 必须用 {@code @Qualifier} 指定 Bean 名称。
     *
     * <p>上下文里有<b>两个</b> {@code RequestMappingHandlerMapping}：
     * <ul>
     *   <li>{@code requestMappingHandlerMapping} —— Spring MVC 的，注册了我们的
     *       {@code /api/**} 业务接口</li>
     *   <li>{@code controllerEndpointHandlerMapping} —— Spring Boot Actuator 的，
     *       注册 {@code /actuator/**} 端点</li>
     * </ul>
     *
     * <p>不指定名称会启动失败：
     * {@code expected single matching bean but found 2}。
     * 我们要判断的是业务接口是否存在，所以取前者。
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           SecurityContextRepository securityContextRepository,
                                           ObjectMapper objectMapper,
                                           @Qualifier("requestMappingHandlerMapping")
                                           RequestMappingHandlerMapping handlerMapping) throws Exception {
        http
                // ── 不存在的路径先返回 404（必须在授权之前）────────
                // 详见 UnmappedPathFilter 的类注释：不这样做的话，
                // 未登录与已登录访问不存在路径会得到 401 / 404 两种结果，
                // 攻击者可据此枚举出哪些接口真实存在。
                .addFilterBefore(
                        new UnmappedPathFilter(handlerMapping, objectMapper),
                        AuthorizationFilter.class)

                // ── 授权规则 ────────────────────────────────────────
                .authorizeHttpRequests(auth -> auth
                        // 认证接口本身必须免登录，否则无法登录（鸡生蛋问题）
                        .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
                        // 退出也免登录：会话已过期时点「退出」不该报 40101，
                        // 否则前端要多处理一个无意义的边界分支
                        .requestMatchers("/api/auth/logout").permitAll()
                        // 健康检查：运维探针不会带 Cookie
                        .requestMatchers("/actuator/health/**", "/api/health").permitAll()

                        // ── 错误分发路径也必须放行 ───────────────────
                        // 这是「不存在的路径不该泄露其是否存在」的关键。
                        //
                        // 问题：Spring Security 的授权过滤器运行在 DispatcherServlet
                        // <b>之前</b>。所以未登录时请求一个不存在的路径，会先被授权
                        // 检查拦下返回 401，根本走不到「找不到处理器」的逻辑 ——
                        // 于是出现：
                        //     未登录访问不存在路径 → 401
                        //     已登录访问不存在路径 → 404
                        // 两者不同，攻击者据此就能枚举出哪些路径真实存在。
                        //
                        // 放行 /error 后，容器内部转发到错误分发器不再被拦，
                        // 两种情况都能走到 GlobalExceptionHandler 的
                        // NoHandlerFoundException 分支，统一返回 40401。
                        .requestMatchers("/error").permitAll()

                        // 其余一律需要登录。
                        // 「默认拒绝」方向是安全的：新增接口忘记加规则时
                        // 结果是「被拒绝」而不是「意外公开」。
                        .anyRequest().authenticated())

                // ── 异常处理：统一成业务错误码 ───────────────────────
                .exceptionHandling(ex -> ex
                        // 未登录 → 401 + 40101
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(response, objectMapper, ErrorCode.UNAUTHORIZED))
                        // 已登录但无权 → 403 + 40301
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeError(response, objectMapper, ErrorCode.FORBIDDEN)))

                // ── 会话管理 ────────────────────────────────────────
                // IF_REQUIRED：需要时才创建会话。
                // ⚠️ 不能用 STATELESS —— 那会让 Spring Security 完全不使用会话，
                //    我们的 Cookie 会话方案就整体失效了。
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))

                // ── 安全上下文持久化到会话 ──────────────────────────
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository))

                // ── CSRF ────────────────────────────────────────────
                // 暂时关闭，这是有意识的权衡而非遗漏：
                //   SameSite=Lax 已挡住绝大多数跨站请求伪造
                //   （跨站 POST 不会携带会话 Cookie）。
                //   等前端联调完成后，若要更严格的双重防护，
                //   再启用 CSRF Token（需要前端配合读取并回传）。
                .csrf(csrf -> csrf.disable())

                // ── 关闭默认登录方式 ────────────────────────────────
                // 我们是 REST + JSON，不要表单登录的重定向，也不要 HTTP Basic 弹窗
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())

                // ── 退出登录 ────────────────────────────────────────
                // 用自己实现的 /api/auth/logout（见 AuthController），
                // 关掉 Spring Security 默认的 /logout，避免两个退出入口行为不一致
                .logout(logout -> logout.disable())

                // 允许同源 iframe（开发期方便，生产用不到）
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));

        return http.build();
    }

    /**
     * 密码编码器。BCrypt，强度 10（开发文档 §5.2）。
     *
     * <p>强度 10 = 2^10 次内部迭代，单次校验约 50-100ms。
     * 对交互式登录可接受，但让离线暴力破解成本提高约 1000 倍。
     *
     * <p><b>不要调低</b>（强度 4 等于放弃抗爆破），
     * <b>也不要随意调高</b>（强度 14 会让每次登录超过 1 秒，
     * 高并发时 CPU 成为瓶颈）。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /**
     * 把错误码写成统一响应体。
     *
     * <p>为什么需要它：Spring Security 的过滤器链运行在
     * {@code DispatcherServlet} <b>之前</b>，它抛出的异常<b>不会</b>被
     * {@code @RestControllerAdvice} 捕获。所以必须在这里手动构造响应 ——
     * 否则前端会收到 Spring 默认的 HTML 错误页或空响应体，
     * 与「统一响应体」契约不一致。
     */
    private static void writeError(HttpServletResponse response,
                                   ObjectMapper objectMapper,
                                   ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        Result<Void> body = Result.fail(errorCode);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
