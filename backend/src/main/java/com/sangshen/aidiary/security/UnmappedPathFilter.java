package com.sangshen.aidiary.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sangshen.aidiary.common.ErrorCode;
import com.sangshen.aidiary.common.Result;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

/**
 * 不存在的路径一律返回 404，<b>与登录状态和请求方法都无关</b>。
 *
 * <h2>它解决什么问题</h2>
 *
 * <p>Spring Security 的授权过滤器运行在 {@code DispatcherServlet} <b>之前</b>。
 * 未登录时请求一个不存在的路径，会先被授权检查拦下返回 401，
 * 走不到"找不到处理器"的逻辑。于是出现：
 *
 * <pre>
 * 未登录访问 /api/not-exist  →  401  (请先登录)
 * 已登录访问 /api/not-exist  →  404  (请求的内容不存在)
 * </pre>
 *
 * <p>两者不同，攻击者据此就能<b>枚举出哪些路径真实存在</b>：
 * 401 说明"路径存在但要登录"，404 说明"路径不存在"。
 * 这是路径枚举漏洞 —— 不泄露数据，但泄露了接口拓扑。
 *
 * <h2>为什么放行 /error 不够</h2>
 *
 * <p>试过在授权规则里 {@code permitAll("/error")}，<b>无效</b> ——
 * 401 是授权阶段直接产生的，容器根本没走到"转发到错误分发器"这一步。
 * 必须在授权<b>之前</b>就判断路径是否存在。
 *
 * <h2>⚠️ 判断的是「路径」而不是「路径+方法」</h2>
 *
 * <p>这一点很容易写错，第一版就踩了：
 *
 * <pre>
 * 用 mapping.getHandler(request) 判断  →  GET /api/auth/login 也被判为 404
 * 结果：本该是 405 Method Not Allowed 的响应变成了 404，语义错乱
 * </pre>
 *
 * <p>正确做法是把请求的「方法」和「路径」分开看：
 * <ul>
 *   <li><b>路径不存在</b> → 404（本过滤器负责）</li>
 *   <li><b>路径存在但方法不对</b> → 放行，交给后面的 MVC 返回 405
 *       （由 {@code GlobalExceptionHandler} 转成 {@code 40501}）</li>
 * </ul>
 *
 * <h2>为什么用请求映射注册表而不是维护路径清单</h2>
 *
 * <p>维护清单意味着"新增接口时要记得同步改清单"，漏改就会出现
 * 真实接口被误判成 404。直接问 {@code RequestMappingHandlerMapping}
 * 已注册了哪些路径，是唯一真源，不会不同步。
 *
 * <h2>覆盖范围</h2>
 *
 * <p>只检查 {@code /api/} 开头的路径：
 * <ul>
 *   <li>actuator 端点由独立机制注册，不走 {@code RequestMappingHandlerMapping}，
 *       对它检查会误判成 404</li>
 *   <li>静态资源、错误页等也不该被拦截</li>
 * </ul>
 *
 * <p>而信息泄露只发生在业务接口上，限定 {@code /api/} 既够用又安全。
 */
public class UnmappedPathFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UnmappedPathFilter.class);

    /** 只对业务接口做检查，避免误伤 actuator 与静态资源 */
    private static final String API_PREFIX = "/api/";

    private final RequestMappingHandlerMapping handlerMapping;
    private final ObjectMapper objectMapper;

    /**
     * 已注册的路径模板缓存。
     *
     * <p>{@code volatile} 保证多线程下的可见性。
     * 用 {@link TreeSet} 只是为了日志输出有序，便于排查。
     */
    private volatile Set<String> registeredPatterns;

    public UnmappedPathFilter(RequestMappingHandlerMapping handlerMapping, ObjectMapper objectMapper) {
        this.handlerMapping = handlerMapping;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();

        // 非 /api/ 路径直接放行 —— actuator、静态资源、错误页都不归这里管
        if (!path.startsWith(API_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!pathExists(path)) {
            // 只记 DEBUG：正常用户不会请求不存在的路径。
            // 若这行高频出现，说明有人在扫描接口。
            log.debug("路径不存在 path={}", path);
            writeNotFound(response);
            return;
        }

        // 路径存在 → 放行，交给授权过滤器判断是否需要登录，
        // 再由 MVC 处理（方法不匹配时返回 405）
        filterChain.doFilter(request, response);
    }

    /**
     * 判断该路径是否在已注册的请求映射中。
     *
     * <p>只匹配路径，<b>不</b>考虑 HTTP 方法 —— 方法不匹配应返回 405 而非 404。
     */
    private boolean pathExists(String path) {
        Set<String> patterns = registeredPatterns;
        if (patterns == null) {
            patterns = collectPatterns();
            registeredPatterns = patterns;
        }

        for (String pattern : patterns) {
            if (matches(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 收集所有已注册的路径模板。
     *
     * <p>用 {@code PathPatternsRequestCondition} 的 {@code getPatternValues()}：
     * Spring 6 起 {@code PatternsRequestCondition} 已被 PathPattern 取代，
     * 这里取到的是形如 {@code /api/auth/{id}} 的模板字符串。
     */
    private Set<String> collectPatterns() {
        Set<String> patterns = new TreeSet<>();

        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            PathPatternsRequestCondition condition = info.getPathPatternsCondition();
            if (condition != null) {
                patterns.addAll(condition.getPatternValues());
            }
        }

        log.info("已注册 {} 个请求路径模板用于 404 判定", patterns.size());
        return patterns;
    }

    /**
     * 路径模板匹配。
     *
     * <p>把模板里的占位符（{@code {id}}、{@code {*path}}）转成正则再比对。
     * 支持 Spring 的 {@code {name:regex}} 语法 —— 正则部分原样保留。
     *
     * <p>之所以不直接用 {@code PathPattern.matches()}：那需要一个
     * {@code PathContainer}，构造它比写这几行转换更绕。
     */
    private boolean matches(String pattern, String path) {
        // 把 {xxx} 或 {xxx:正则} 转成捕获组
        StringBuilder regex = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '{') {
                int end = pattern.indexOf('}', i);
                if (end < 0) {
                    // 模板不合法，退化为字面量比较
                    return pattern.equals(path);
                }
                String inner = pattern.substring(i + 1, end);
                int colon = inner.indexOf(':');
                // {name:regex} 用自定义正则；{name} 用默认的「不含斜杠」
                regex.append(colon >= 0 ? "(" + inner.substring(colon + 1) + ")" : "([^/]+)");
                i = end + 1;
            } else if (c == '*') {
                // {*path} 已被上面的 {} 分支处理；裸 * 视为匹配任意字符
                regex.append(".*");
                i++;
            } else {
                regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        return path.matches(regex.toString());
    }

    private void writeNotFound(HttpServletResponse response) throws IOException {
        ErrorCode errorCode = ErrorCode.NOT_FOUND;
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        Result<Void> body = Result.fail(errorCode);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
