package com.sangshen.aidiary.entity;

import java.time.LocalDateTime;

/**
 * 标签实体，对应 {@code tag} 表。
 *
 * <p>标签是<b>用户私有</b>的：两个用户都可以有叫「读书」的标签，
 * 数据库唯一键是 {@code (user_id, name)} 而不是 {@code (name)}。
 * 所以本表的每次读写都必须带 {@code user_id}。
 *
 * <h2>同名标签复用规则</h2>
 *
 * <p>{@code POST /api/tags} 创建标签时，如果当前用户已经有同名标签，
 * <b>直接返回已有的那个</b>（返回码仍是成功），而不是报冲突。
 * 理由：标签是用户随手打的，重复点击「新建」不应该报错。
 * 这条规则的实现依赖 {@link #name} 与 {@code userId} 的组合查询，
 * 唯一键 {@code uk_tag_user_name} 是最后一道防线（并发时兜底）。
 *
 * <p>注意：这里的「同名」比较是<b>大小写敏感</b>的，取决于数据库排序规则
 * {@code utf8mb4_0900_ai_ci}（ai = accent insensitive，ci = case insensitive）。
 * 也就是说 MySQL 层面 {@code "Book"} 和 {@code "book"} 会被唯一键视为同一个 ——
 * 这与应用层的字符串相等判断不一致，Service 查重时必须用 SQL 查询而不是
 * Java 的 {@code equals}，否则会出现「Java 认为不重复、数据库报唯一键冲突」。
 */
public class Tag {

    /** 主键，自增 */
    private Long id;

    /** 所属用户。所有查询/删除都必须带上它做隔离 */
    private Long userId;

    /** 标签名，同一用户内唯一。数据库 VARCHAR(30) */
    private String name;

    /** 创建时间（UTC） */
    private LocalDateTime createdAt;

    // ── getter / setter ────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * 标签没有敏感字段，所以可以安全地重写 toString ——
     * 这点和 {@code User}（含密码哈希）、{@code Diary}（含正文密文）不同。
     *
     * <p>但为了一致性和「少一个需要判断的地方」，这里仍然提供
     * {@code toSafeString()} 别名，日志里统一用它。
     */
    @Override
    public String toString() {
        return toSafeString();
    }

    public String toSafeString() {
        return "Tag{id=" + id
                + ", userId=" + userId
                + ", name='" + name + '\''
                + ", createdAt=" + createdAt
                + '}';
    }
}
