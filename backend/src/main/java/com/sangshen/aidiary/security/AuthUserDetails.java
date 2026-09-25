package com.sangshen.aidiary.security;

import com.sangshen.aidiary.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Spring Security 的认证主体。
 *
 * <h2>为什么不让 {@code User} 实体直接实现 UserDetails</h2>
 *
 * <p>把持久化实体塞进安全上下文有两个问题：
 * <ol>
 *   <li><b>耦合</b> —— 实体字段随表结构变化，而安全上下文需要稳定；
 *       表加字段不该影响认证。</li>
 *   <li><b>泄露风险</b> —— 实体带着 {@code passwordHash}，一旦有人
 *       {@code log.info("{}", authentication)} 就会把哈希写进日志。
 *       本类<b>不持有</b>密码哈希。</li>
 * </ol>
 *
 * <h2>为什么只缓存 userId 和 username</h2>
 *
 * <p>Session 里的对象会被序列化（若开启持久化 Session）或在内存中长期驻留。
 * 只放最小必要信息：需要昵称等字段时按 userId 现查，保证取到的是最新值。
 * 如果把整个 User 快照放进去，用户改了昵称后要等到下次登录才生效。
 */
public class AuthUserDetails implements UserDetails {

    private final Long userId;
    private final String username;
    private final String passwordHash;
    private final boolean enabled;

    public AuthUserDetails(Long userId, String username, String passwordHash, boolean enabled) {
        this.userId = userId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.enabled = enabled;
    }

    /**
     * 从实体构造。
     *
     * <p>会读取 {@code passwordHash} —— 登录校验必须用到它。
     * 但本类不对外暴露 getter，所以它不会经由 toString / 日志泄露。
     */
    public static AuthUserDetails from(User user) {
        return new AuthUserDetails(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                "ACTIVE".equals(user.getStatus())
        );
    }

    /** 业务主键。Controller / Service 用它做数据归属过滤。 */
    public Long getUserId() {
        return userId;
    }

    // ── UserDetails 接口 ──────────────────────────────────────

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // 第一版没有角色概念（所有用户等价）。
        // 返回空集合而非 null —— Security 内部会对返回值做遍历，null 会 NPE。
        // 将来加管理员时在这里返回 ROLE_ADMIN。
        return List.of();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;   // 第一版不做账号有效期
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;   // 第一版不做锁定（登录限流见 Phase 7）
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;   // 第一版不做密码有效期
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * ⚠️ 刻意不输出 passwordHash 和 username。
     *
     * <p>Spring Security 在 DEBUG 级别会打印认证对象。若这里带上密码哈希，
     * DEBUG 日志就成了泄露渠道。只留 userId 对排查足够。
     */
    @Override
    public String toString() {
        return "AuthUserDetails{userId=" + userId + ", enabled=" + enabled + '}';
    }
}
