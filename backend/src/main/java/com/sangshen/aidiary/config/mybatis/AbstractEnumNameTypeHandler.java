package com.sangshen.aidiary.config.mybatis;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 枚举 ↔ 数据库字符串的通用 TypeHandler 基类。
 *
 * <h2>⚠️ 为什么必须自己写 TypeHandler</h2>
 *
 * <p>MyBatis 对枚举有多个内置处理器，行为完全不同：
 * <table border="1">
 *   <caption>内置枚举处理器对比</caption>
 *   <tr><th>处理器</th><th>数据库里存什么</th></tr>
 *   <tr><td>{@code EnumTypeHandler}（MyBatis 3.4.5+ 的默认）</td>
 *       <td>{@code name()}，如 {@code "DIARY"}</td></tr>
 *   <tr><td>{@code EnumOrdinalTypeHandler}</td>
 *       <td>{@code ordinal()}，如 {@code 0}</td></tr>
 * </table>
 *
 * <p>默认是 name，看起来"不写也行"。但**依赖默认值是危险的**：
 * <ul>
 *   <li>默认值会随 MyBatis 版本变化（3.4.5 之前是 ordinal）</li>
 *   <li>一旦某处显式配置了 {@code defaultEnumTypeHandler}，
 *       全局行为就被改掉，而这个改动在 XML 里看不出来</li>
 *   <li>存成 ordinal 的后果是**灾难性的且难以发现**：
 *       数据库里是 0/1/2，可读性全毁；更糟的是
 *       <b>往枚举中间插入一个新值，所有历史数据的语义就整体错位</b>
 *       （原本 2=SUCCESS 变成了新加的那个值）</li>
 * </ul>
 *
 * <p>所以本项目<b>显式</b>为每个枚举写 TypeHandler，并在 XML 里
 * 用全限定名指定。这样"存的是 name"是可验证的事实，不是假设。
 *
 * <h2>⚠️ 一个真实踩过的坑：typeHandler 不能指向枚举类本身</h2>
 *
 * <p>我第一版在 XML 里写的是：
 * <pre>{@code
 * #{status, typeHandler=com.sangshen.aidiary.entity.enums.AiTaskStatus}
 * }</pre>
 * 以为"告诉 MyBatis 这个参数是 AiTaskStatus 枚举"就够了。
 * <b>错。</b>运行时解析 XML 时直接报：
 * <pre>
 * Type ...AiTaskStatus is not a valid TypeHandler
 * because it does not implement TypeHandler interface
 * </pre>
 * {@code typeHandler} 属性要的是**处理器实现类**，不是被处理的类型。
 *
 * <p>而这个错误**编译期发现不了**（XML 是字符串），
 * 只有在 MyBatis 真正解析映射文件时才暴露 ——
 * 所以数据层必须有"用真实 MyBatis 跑一遍"的测试，
 * 光靠 JDBC 手抄 SQL 是测不出这类问题的。
 *
 * <h2>关于未知值</h2>
 *
 * <p>读到数据库里不存在的枚举名时，{@link #parse} 会抛
 * {@link IllegalArgumentException}，而不是返回 null。
 * 这是刻意的：状态列出现未知值是**数据损坏或版本不匹配**，
 * 静默返回 null 会让上层把它当成"没有状态"，问题被藏起来。
 */
public abstract class AbstractEnumNameTypeHandler<E extends Enum<E>>
        extends BaseTypeHandler<E> {

    private final Class<E> enumType;

    protected AbstractEnumNameTypeHandler(Class<E> enumType) {
        if (enumType == null) {
            throw new IllegalArgumentException("enumType 不能为 null");
        }
        this.enumType = enumType;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, E parameter,
                                    JdbcType jdbcType) throws SQLException {
        // 存 name()，不存 ordinal()
        ps.setString(i, parameter.name());
    }

    @Override
    public E getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public E getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public E getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private E parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "数据库中的枚举值 \"" + value + "\" 不是合法的 " + enumType.getSimpleName()
                            + "（可能是数据损坏，或应用与数据库版本不一致）", e);
        }
    }
}
