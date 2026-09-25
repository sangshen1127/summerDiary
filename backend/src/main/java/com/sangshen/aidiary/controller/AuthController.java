package com.sangshen.aidiary.controller;

import com.sangshen.aidiary.common.Result;
import com.sangshen.aidiary.dto.request.LoginRequest;
import com.sangshen.aidiary.dto.request.RegisterRequest;
import com.sangshen.aidiary.dto.response.UserResponse;
import com.sangshen.aidiary.security.SecurityUtils;
import com.sangshen.aidiary.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 *
 * <p>契约见开发文档 §4.6「认证」段落，四个接口全部实现：
 * <pre>
 * POST /api/auth/register   注册      201 + code=0，不下发 Cookie
 * POST /api/auth/login      登录      200 + code=0，下发 Set-Cookie
 * POST /api/auth/logout     退出      200 + code=0，清除 Cookie
 * GET  /api/auth/me         当前用户  200 + code=0
 * </pre>
 *
 * <h2>Controller 的职责边界（开发文档 §3.1）</h2>
 *
 * <p>只做三件事：接参数（{@code @Valid} 触发校验）、调 Service、
 * 包成统一响应 {@link Result}。<b>不做</b>业务规则、事务、SQL。
 *
 * <h2>关于 <code>/api/auth/me</code> 如何取当前用户</h2>
 *
 * <p>用 {@link SecurityUtils#getCurrentUserId()} 从安全上下文读，
 * <b>绝不</b>接受前端传入的 userId —— 见开发文档 §4.7 权限铁律。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 注册新用户。
     *
     * <pre>
     * POST /api/auth/register
     * { "username": "zhangsan", "password": "abc12345", "nickname": "张三" }
     * </pre>
     *
     * <p>成功返回 <b>201 Created</b> + {@code code=0}，响应体与登录、/me 结构一致。
     *
     * <p><b>不自动登录</b>：本接口<b>不</b>下发会话 Cookie（响应头里没有
     * {@code Set-Cookie}）。注册成功后前端跳转登录页，由 {@code /login}
     * 统一下发会话 —— 让「会话创建」只有一条代码路径。
     *
     * @param request 注册请求，字段规则见 {@link RegisterRequest}
     * @return 新用户的可公开信息
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.ok(authService.register(request));
    }

    /**
     * 登录并建立会话。
     *
     * <pre>
     * POST /api/auth/login
     * { "username": "zhangsan", "password": "abc12345" }
     * </pre>
     *
     * <h2>成功响应（HTTP 200）</h2>
     * <pre>
     * HTTP/1.1 200 OK
     * Set-Cookie: AI_DIARY_SESSION=xxxxx; Path=/; HttpOnly; SameSite=Lax
     *
     * {
     *   "code": 0,
     *   "message": "success",
     *   "data": { "id": 1, "username": "zhangsan", "nickname": "张三",
     *             "ai_enabled": true, "created_at": "..." },
     *   "timestamp": 1789987717427
     * }
     * </pre>
     *
     * <p><b>关键的验收点</b>：响应头里必须有 {@code Set-Cookie}，
     * 且 Cookie 带 {@code HttpOnly}（JS 读不到）。
     *
     * <h2>失败响应</h2>
     *
     * <p>凭据错误返回 <b>401</b> + {@code code=40101} + 文案「用户名或密码错误」。
     * 「用户不存在」和「密码错误」<b>刻意返回相同结果</b>，防止用户名枚举。
     *
     * @param request  登录请求
     * @param servletRequest  Servlet 请求，透传给 Service 用于会话轮换
     * @param servletResponse Servlet 响应，透传给 Service 用于保存安全上下文
     * @return 登录用户的可公开信息
     */
    @PostMapping("/login")
    public Result<UserResponse> login(@Valid @RequestBody LoginRequest request,
                                      HttpServletRequest servletRequest,
                                      HttpServletResponse servletResponse) {
        return Result.ok(authService.login(request, servletRequest, servletResponse));
    }

    /**
     * 退出登录。
     *
     * <pre>
     * POST /api/auth/logout
     * </pre>
     *
     * <p>成功返回 200 + {@code code=0}，响应头带清除 Cookie 的 {@code Set-Cookie}。
     *
     * <p><b>幂等</b>：未登录状态下调用也返回成功。
     * 理由：退出是「让状态归零」的操作，本来就没登录时调用它，
     * 期望结果（已退出）已经达成，报错只会让前端多一个无意义分支。
     *
     * <p>⚠️ 本接口<b>不</b>要求登录（{@code permitAll}）——
     * 否则会话已过期时用户点"退出"会得到 40101，前端反而要处理这种边界。
     *
     * @return 空响应体
     */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest servletRequest,
                               HttpServletResponse servletResponse) {
        authService.logout(servletRequest, servletResponse);
        return Result.ok();
    }

    /**
     * 获取当前登录用户。
     *
     * <pre>
     * GET /api/auth/me
     * Cookie: AI_DIARY_SESSION=xxxxx
     * </pre>
     *
     * <p>这是<b>路由守卫必须调用的接口</b>（开发文档 §8.2）：
     * 前端首次打开受保护页面时要向后端确认登录态，
     * 不能只信任本地缓存 —— Cookie 可能已在服务端失效。
     *
     * <p>未登录（无 Cookie / Cookie 无效）返回 401 + {@code code=40101}，
     * 由 {@code SecurityConfig} 的 {@code AuthenticationEntryPoint} 统一处理。
     *
     * @return 当前用户的可公开信息
     */
    @GetMapping("/me")
    public Result<UserResponse> me() {
        // 从安全上下文取，不接受前端传入 —— 权限铁律
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return Result.ok(authService.currentUser(currentUserId));
    }
}
