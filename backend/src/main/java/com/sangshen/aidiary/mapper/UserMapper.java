package com.sangshen.aidiary.mapper;

import com.sangshen.aidiary.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户表 Mapper。
 *
 * <p>接口只声明业务需要的方法，SQL 写在
 * {@code resources/mapper/UserMapper.xml}（开发文档 §6.3）。
 *
 * <h2>方法命名约定</h2>
 * <ul>
 *   <li>{@code selectByXxx} —— 查询</li>
 *   <li>{@code insert} / {@code updateById} / {@code deleteById}</li>
 *   <li>涉及用户私有资源的方法，名字里必须体现 {@code AndUserId}，
 *       让「有没有做权限过滤」在调用处一眼可见</li>
 * </ul>
 *
 * <h2>⚠️ 关于 user 表本身</h2>
 *
 * <p>user 表按 {@code id} 查询是安全的 —— 因为「查询用户」这个动作本身就是
 * 为了拿到这个用户，不存在「A 查 B 的 user 行」这种越权语义（登录时要按
 * username 查，改密码时要按 id 查自己）。
 *
 * <p>但 {@code diary} / {@code tag} / {@code memory} 这些<b>用户私有资源</b>
 * 的表就不一样了 —— 它们的方法<b>必须</b>带 {@code userId} 参数，
 * 由 SQL 层的 {@code WHERE user_id = #{userId}} 完成隔离。
 */
@Mapper
public interface UserMapper {

    /**
     * 按用户名查询（含密码哈希），登录时用。
     *
     * <p>注册查重、登录验证都走这个方法。
     *
     * @param username 登录名
     * @return 用户，不存在时返回 {@code null}
     */
    User selectByUsername(@Param("username") String username);

    /**
     * 按主键查询（不含密码哈希的字段也会带出来，但由调用方决定是否外泄）。
     *
     * @param id 用户主键
     * @return 用户，不存在时返回 {@code null}
     */
    User selectById(@Param("id") Long id);

    /**
     * 统计某用户名是否已存在。
     *
     * <p>相比 {@code selectByUsername != null} 的好处：只回一个数字，
     * 不把整行（含密码哈希）读进内存。注册查重用它更合适。
     *
     * @param username 登录名
     * @return 存在返回 1，否则 0
     */
    int countByUsername(@Param("username") String username);

    /**
     * 插入新用户。
     *
     * <p>插入后 {@code user.id} 会被 MyBatis 回填（XML 里配了
     * {@code useGeneratedKeys} + {@code keyProperty}），Service 无需再查一次。
     *
     * @param user 待插入的用户，id 由数据库生成
     * @return 影响行数，正常为 1
     */
    int insert(User user);
}
