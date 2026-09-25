package com.sangshen.aidiary.security;

import com.sangshen.aidiary.entity.User;
import com.sangshen.aidiary.mapper.UserMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 从数据库加载用户，供 Spring Security 使用。
 *
 * <h2>注意：本项目的登录流程没有走 {@code AuthenticationManager}</h2>
 *
 * <p>原因是需要区分"用户不存在"和"密码错误"以便记日志（对外仍返回统一文案），
 * 而 {@code DaoAuthenticationProvider} 会把两者都归一化成
 * {@code BadCredentialsException}。所以登录校验在
 * {@link com.sangshen.aidiary.service.impl.AuthServiceImpl#login} 里手写。
 *
 * <p>那为什么还要这个类？两个用途：
 * <ol>
 *   <li>Spring Security 在没有 {@code UserDetailsService} Bean 时会生成一个
 *       随机的内存用户并打印 "Using generated security password: ..." ——
 *       那段日志很误导人，本类把它消除掉。</li>
 *   <li>将来切换到 {@code AuthenticationManager} 或加权限注解时可直接复用。</li>
 * </ol>
 *
 * <h2>安全约定：不要把"用户不存在"和"密码错误"暴露给调用方</h2>
 *
 * <p>{@link UsernameNotFoundException} 的 message 里<b>不含</b>用户输入的用户名，
 * 避免异常被日志采集系统索引后形成"哪些用户名存在"的侧信道。
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserMapper userMapper;

    public UserDetailsServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userMapper.selectByUsername(username);
        if (user == null) {
            // 不把 username 拼进 message —— 防止日志/异常聚合泄露"某个用户名是否存在"
            throw new UsernameNotFoundException("用户不存在或不可用");
        }
        return AuthUserDetails.from(user);
    }
}
