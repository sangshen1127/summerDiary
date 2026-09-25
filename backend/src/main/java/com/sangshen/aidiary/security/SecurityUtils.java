package com.sangshen.aidiary.security;

import com.sangshen.aidiary.common.ErrorCode;
import com.sangshen.aidiary.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 从安全上下文读取当前登录用户。
 *
 * <h2>为什么必须用这个工具类，而不是 Controller 接参数</h2>
 *
 * <p>开发文档 §4.7 的权限铁律：
 * <pre>{@code
 * // ❌ 绝对禁止：接受前端传入的 userId 作为权限依据
 * @GetMapping("/api/diaries")
 * public Result<?> list(@RequestParam Long userId) { ... }
 * }</pre>
 *
 * <p>前端传什么都可以伪造，所以用户身份<b>只能</b>从服务端维护的会话里取。
 * 这个类就是唯一入口。
 *
 * <h2>为什么未登录时抛 40101 而不是返回 null</h2>
 *
 * <p>返回 null 会让每个调用方都要判空，漏判就变成 NPE（50001，
 * 提示"服务器开小差"，掩盖了真实的"没登录"）。
 * 抛 {@link BusinessException}(40101) 让语义明确：
 * 这是认证问题，前端应跳登录页。
 *
 * <p>正常情况下未登录请求根本到不了 Service ——
 * {@code SecurityConfig} 的授权规则会先拦下并返回 40101。
 * 这里是<b>防御性兜底</b>：万一将来某个接口被误配成 permitAll 却调用了本方法，
 * 也能得到正确语义而不是 NPE。
 */
public final class SecurityUtils {

    private SecurityUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 获取当前登录用户 ID。
     *
     * @return 当前用户 ID
     * @throws BusinessException 未登录时抛 {@code 40101}
     */
    public static Long getCurrentUserId() {
        return getCurrentUser().getUserId();
    }

    /**
     * 获取当前登录用户主体。
     *
     * @return 认证主体
     * @throws BusinessException 未登录时抛 {@code 40101}
     */
    public static AuthUserDetails getCurrentUser() {
        // 从安全上下文获取认证信息
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // 三种"未认证"的情况都要覆盖：
        //   1. authentication 为 null —— 上下文是空的
        //   2. principal 是字符串 "anonymousUser" —— 匿名认证
        //   3. 未通过认证 —— 认证对象存在但 authenticated=false
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthUserDetails user)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    /**
     * 获取当前登录用户 ID，未登录时返回 {@code null}。
     *
     * <p>用于「登录与否都允许访问，但登录后行为不同」的接口，
     * 例如首页的"今日是否已写日记"。
     *
     * <p>⚠️ 拿到的值<b>不能</b>用于权限判断 —— 权限判断必须用
     * {@link #getCurrentUserId()}，让未登录直接失败。
     */
    public static Long getCurrentUserIdOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthUserDetails user)) {
            return null;
        }
        return user.getUserId();
    }
}
