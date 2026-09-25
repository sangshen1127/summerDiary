package com.sangshen.aidiary.service.impl;

import com.sangshen.aidiary.common.ErrorCode;
import com.sangshen.aidiary.dto.request.LoginRequest;
import com.sangshen.aidiary.dto.request.RegisterRequest;
import com.sangshen.aidiary.dto.response.UserResponse;
import com.sangshen.aidiary.entity.User;
import com.sangshen.aidiary.exception.BusinessException;
import com.sangshen.aidiary.mapper.UserMapper;
import com.sangshen.aidiary.security.AuthUserDetails;
import com.sangshen.aidiary.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证服务实现。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    /** 用户状态常量 */
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    /** Cookie 名称与 secure 标志 —— 与 SessionConfig 共用同一份配置，避免两处字面量不一致 */
    private final String cookieName;
    private final boolean cookieSecure;

    /**
     * 安全上下文仓库 —— 决定认证信息"存哪里"。
     *
     * <p>用 {@link HttpSessionSecurityContextRepository}：认证信息存进
     * Servlet 会话，浏览器只拿一个不含信息的会话 ID。
     *
     * <p>⚠️ 与 {@code SecurityConfig} 里配置的必须是<b>同一个实例语义</b>。
     * 那边配置的是同一个类，这样"写入"和"读取"走同一套机制。
     */
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public AuthServiceImpl(UserMapper userMapper,
                           PasswordEncoder passwordEncoder,
                           @Value("${auth.cookie.name:AI_DIARY_SESSION}") String cookieName,
                           @Value("${auth.cookie.secure:false}") boolean cookieSecure) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.cookieName = cookieName;
        this.cookieSecure = cookieSecure;
    }

    // ══════════════════════════════════════════════════════════
    // 注册
    // ══════════════════════════════════════════════════════════

    /**
     * {@inheritDoc}
     *
     * <h2>为什么先查重、又依赖数据库唯一约束</h2>
     *
     * <p>只靠"先 COUNT 再 INSERT"有并发漏洞：两个请求同时通过 COUNT=0，
     * 然后都执行 INSERT。所以这里有<b>两层</b>：
     * <ol>
     *   <li>COUNT 查重 —— 处理绝大多数情况，给出友好提示，且不浪费一次 BCrypt 计算</li>
     *   <li>捕获 {@link DuplicateKeyException} —— 兜住并发窗口</li>
     * </ol>
     */
    @Override
    @Transactional  // 只读事务：注册不改业务数据。加只读标记能让数据库做优化，
    public UserResponse register(RegisterRequest request) {
        String username = request.username().trim();

        if (userMapper.countByUsername(username) > 0) {
            log.debug("注册失败：用户名已存在 username={}", username);
            throw new BusinessException(ErrorCode.CONFLICT, "该用户名已被占用");
        }

        String passwordHash = passwordEncoder.encode(request.password());

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordHash);
        user.setNickname(normalizeNickname(request.nickname()));
        user.setAiEnabled(true);
        user.setStatus(STATUS_ACTIVE);

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            log.warn("注册并发冲突被唯一索引拦下 username={}", username);
            throw new BusinessException(ErrorCode.CONFLICT, "该用户名已被占用");
        }

        User created = userMapper.selectById(user.getId());
        if (created == null) {
            throw new IllegalStateException(
                    "插入用户后回查失败，id=" + user.getId() + "，请检查事务与数据源配置");
        }

        log.info("注册成功 userId={} username={}", created.getId(), created.getUsername());
        return UserResponse.from(created);
    }

    // ══════════════════════════════════════════════════════════
    // 登录
    // ══════════════════════════════════════════════════════════

    /**
     * {@inheritDoc}
     *
     * <h2>关键实现说明见方法内注释，尤其是 Session 轮换那一段</h2>
     */
    @Override
    // 只读事务：登录不改业务数据。加只读标记能让数据库做优化，
    // 也能防止后续有人不小心在这里写入。
    @Transactional(readOnly = true)
    public UserResponse login(LoginRequest request,
                              HttpServletRequest servletRequest,
                              HttpServletResponse servletResponse) {

        String username = request.username().trim();
        User user = userMapper.selectByUsername(username);

        // ── 统一失败文案 ────────────────────────────────────────
        // 无论"用户不存在"还是"密码错误"，对外都是同一句话。
        // 内部用 DEBUG 日志区分，便于排查，但不外泄。
        if (user == null) {
            log.debug("登录失败：用户不存在 username={}", username);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // ⚠️ 日志只记用户名和 userId，绝不记密码
            log.debug("登录失败：密码错误 userId={} username={}", user.getId(), username);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        // ── 账号状态检查 ────────────────────────────────────────
        // 放在密码校验之后：否则"输入任意密码就能知道该账号被禁用"，
        // 又是一条信息泄露。先验密码，再告诉他账号状态。
        if (!STATUS_ACTIVE.equals(user.getStatus())) {
            log.warn("登录被拒：账号状态非 ACTIVE userId={} status={}", user.getId(), user.getStatus());
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已被禁用，请联系管理员");
        }

        // ══════════════════════════════════════════════════════
        // 🔴 Session 轮换 —— 防 Session Fixation 攻击
        // ══════════════════════════════════════════════════════
        // 攻击方式：攻击者先拿到一个会话 ID，诱导受害者在"这个已存在的会话"
        // 上登录；登录后攻击者手里的 ID 就成了已登录会话，账号被盗。
        //
        // 防御：登录成功时丢弃旧会话、换一个新的会话 ID。
        //
        // ⚠️ 这一步必须手动做！因为我们手写登录（没有走 AuthenticationManager），
        //    Spring Security 内置的 sessionManagement().sessionFixation() 防护
        //    不会自动触发。
        //
        // ⚠️ 顺序很重要：必须先 invalidate 再 getSession(true)。
        //    反过来会拿到同一个会话，等于没轮换。
        HttpSession oldSession = servletRequest.getSession(false);
        if (oldSession != null) {
            oldSession.invalidate();
        }
        HttpSession session = servletRequest.getSession(true);

        // ── 建立认证对象 ────────────────────────────────────────
        AuthUserDetails principal = AuthUserDetails.from(user);
        // 第三个参数是权限列表，第一版没有角色概念，传空列表
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());

        // ── 写入 SecurityContext 并保存到会话 ────────────────────
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);

        // 同时更新两处：
        //   1. SecurityContextHolder —— 本次请求内立即可用
        //   2. securityContextRepository —— 存入会话，后续请求可用
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, servletRequest, servletResponse);

        log.info("登录成功 userId={} username={} sessionId={}",
                user.getId(), user.getUsername(), session.getId());

        return UserResponse.from(user);
    }

    // ══════════════════════════════════════════════════════════
    // 退出
    // ══════════════════════════════════════════════════════════

    /**
     * {@inheritDoc}
     *
     * <h2>三步都要做，缺一不可</h2>
     * <ol>
     *   <li>{@code session.invalidate()} —— 服务端会话销毁。
     *       这是<b>真正让会话失效</b>的一步。</li>
     *   <li>清空 {@code SecurityContextHolder} —— 本次请求内立即失效</li>
     *   <li>下发清除 Cookie 的响应头 —— 让浏览器不再保留已失效的 ID</li>
     * </ol>
     *
     * <h2>为什么第 3 步不是"安全必须"，但仍要补上</h2>
     *
     * <p>实测确认：只做前两步时，用退出前的旧 Cookie 访问受保护接口
     * 会返回 {@code 401/40101}（服务端会话已销毁，旧 ID 无法恢复），
     * 所以<b>安全性上没有问题</b>。
     *
     * <p>但浏览器会一直保留这个无用的会话 ID，导致：
     * <ul>
     *   <li>每个请求都白带一个无效 Cookie，浪费带宽</li>
     *   <li>排查问题时看到残留 Cookie，容易误判为"还登录着"</li>
     * </ul>
     *
     * <p>所以补上第 3 步，让客户端状态与服务端一致。
     *
     * <p>⚠️ 清除时 <b>Path / Name / Domain 必须与下发时完全一致</b>，
     * 否则浏览器不会删除它。这就是取值统一从 {@code auth.cookie.*}
     * 配置读取的原因 —— 避免两处写字面量后不一致。
     */
    @Override
    public void logout(HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        HttpSession session = servletRequest.getSession(false);
        if (session != null) {
            log.info("退出登录 sessionId={}", session.getId());
            session.invalidate();
        } else {
            // 未登录时调用退出 —— 幂等处理，不报错。
            // 理由：退出是"让状态归零"的操作，本来就没登录时调用它，
            // 期望结果（已退出）已经达成。
            log.debug("退出登录：当前无会话，忽略");
        }
        SecurityContextHolder.clearContext();

        // 清除浏览器里的会话 Cookie。
        // maxAge(0) 表示立即过期，等价于删除。
        ResponseCookie cleared = ResponseCookie.from(cookieName, "")
                .path("/")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .maxAge(0)
                .build();
        servletResponse.addHeader(HttpHeaders.SET_COOKIE, cleared.toString());
    }

    // ══════════════════════════════════════════════════════════
    // 当前用户
    // ══════════════════════════════════════════════════════════

    /**
     * {@inheritDoc}
     *
     * <p>每次都现查数据库，好处见接口注释。代价是每次请求多一次主键查询 ——
     * 走主键索引，成本极低，换来的是一致性，值得。
     */
    @Override
    @Transactional(readOnly = true)
    public UserResponse currentUser(Long currentUserId) {
        User user = userMapper.selectById(currentUserId);
        if (user == null) {
            // 会话还在但用户已被删除（软删除后 selectById 返回 null）。
            // 视为认证失效，让前端跳登录页 —— 而不是返回 404 让前端困惑。
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return UserResponse.from(user);
    }

    // ══════════════════════════════════════════════════════════
    // 私有工具
    // ══════════════════════════════════════════════════════════

    /**
     * 昵称归一化：把"空串""只有空白"统一成 {@code null}。
     *
     * <p>为什么不让空串入库：数据库中 {@code ''} 和 {@code NULL} 语义不同，
     * 混用会让后续查询（{@code WHERE nickname IS NOT NULL}）产生意外结果。
     * 统一成 NULL 只有一种"没有昵称"的表示。
     */
    private String normalizeNickname(String nickname) {
        if (nickname == null) {
            return null;
        }
        String trimmed = nickname.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
