package com.sangshen.aidiary.service;

import com.sangshen.aidiary.dto.request.LoginRequest;
import com.sangshen.aidiary.dto.request.RegisterRequest;
import com.sangshen.aidiary.dto.response.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 认证服务。
 *
 * <p>契约见开发文档 §4.6「认证」段落：
 * <pre>
 * POST /api/auth/register   注册      （模块 1-2）
 * POST /api/auth/login      登录      （模块 1-3）
 * POST /api/auth/logout     退出      （模块 1-3）
 * GET  /api/auth/me         当前用户  （模块 1-3）
 * </pre>
 *
 * <h2>为什么 login / logout 需要 HttpServletRequest / Response 参数</h2>
 *
 * <p>会话是 Servlet 容器层面的东西（{@code HttpSession} 存在服务端，
 * 会话 ID 通过 {@code Set-Cookie} 响应头下发）。创建会话必须拿到 request，
 * 清 Cookie 必须拿到 response。所以这两个参数必须从 Controller 透传下来，
 * 不能靠 {@code RequestContextHolder} 隐式获取 —— 隐式获取在异步线程里会失效，
 * 显式传参能保证调用方清楚"这个方法依赖 Servlet 环境"。
 */
public interface AuthService {

    /**
     * 注册新用户。
     *
     * <p><b>不自动登录</b>：注册成功后前端跳转登录页，
     * 由 {@link #login} 统一下发会话。这样「会话创建」只有一条代码路径，便于审计。
     *
     * @param request 已通过 {@code @Valid} 校验的注册请求
     * @return 新用户的可公开信息（不含密码哈希）
     * @throws com.sangshen.aidiary.exception.BusinessException
     *         用户名已存在时抛 {@code CONFLICT}
     */
    UserResponse register(RegisterRequest request);

    /**
     * 登录并建立会话。
     *
     * <h2>成功时做的事（顺序很重要）</h2>
     * <ol>
     *   <li>查用户；不存在则失败</li>
     *   <li>用 BCrypt {@code matches()} 校验密码</li>
     *   <li>检查账号状态（DISABLED 拒绝登录）</li>
     *   <li><b>轮换 Session ID</b> —— 防 Session Fixation 攻击</li>
     *   <li>把认证信息写入会话并保存</li>
     * </ol>
     *
     * <h2>失败时的行为</h2>
     *
     * <p>无论"用户不存在"还是"密码错误"，对外<b>统一</b>返回
     * {@code 40101} + 文案「用户名或密码错误」，
     * 不区分二者 —— 否则攻击者能靠错误差异枚举出哪些用户名已注册。
     * 内部分支会记 DEBUG 日志便于排查。
     *
     * @param request  登录请求
     * @param servletRequest  Servlet 请求，用于获取/轮换会话
     * @param servletResponse Servlet 响应，用于保存安全上下文
     * @return 登录用户的可公开信息
     * @throws com.sangshen.aidiary.exception.BusinessException
     *         凭据错误抛 {@code 40101}；账号被禁用抛 {@code 40301}
     */
    UserResponse login(LoginRequest request,
                       HttpServletRequest servletRequest,
                       HttpServletResponse servletResponse);

    /**
     * 退出登录：使会话失效并清除 Cookie。
     *
     * <p>幂等 —— 未登录状态下调用也返回成功，不报错。
     * 理由：退出是个"让状态归零"的操作，本来就没登录时调用它，
     * 期望的结果（已退出）已经达成，报错反而让前端要处理无意义的分支。
     *
     * @param servletRequest  Servlet 请求，用于获取当前会话
     * @param servletResponse Servlet 响应，用于清除 Cookie
     */
    void logout(HttpServletRequest servletRequest, HttpServletResponse servletResponse);

    /**
     * 获取当前登录用户信息。
     *
     * <p>⚠️ 每次都用会话里的 {@code userId} <b>现查数据库</b>，
     * 而不是把用户信息快照存在会话里。原因：
     * <ul>
     *   <li>用户改了昵称、关闭了 AI 开关后，前端刷新页面能立刻看到新值</li>
     *   <li>账号被禁用（DISABLED）时，下次请求就会被拒绝，
     *       而不是等会话自然过期</li>
     * </ul>
     *
     * @param currentUserId 当前登录用户 ID（由 Controller 从安全上下文取）
     * @return 当前用户的可公开信息
     * @throws com.sangshen.aidiary.exception.BusinessException
     *         用户已被删除时抛 {@code 40101}（会话应视为失效）
     */
    UserResponse currentUser(Long currentUserId);
}
