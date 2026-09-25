package com.sangshen.aidiary.entity;

import java.time.LocalDateTime;

/**
 * 用户实体，对应 {@code user} 表。
 *
 * <h2>⚠️ 这个类绝不能直接返回给前端</h2>
 *
 * <p>它包含 {@link #passwordHash} —— 密码哈希。直接序列化返回等于把
 * 密码哈希泄露给客户端。Controller 必须返回
 * {@link com.sangshen.aidiary.dto.response.UserResponse}。
 *
 * <p>Phase 8 会加 ArchUnit 测试断言「Controller 不得依赖 entity 包」，
 * 让这条规则变成构建期检查而不是口头约定。
 *
 * <h2>为什么用普通类而不是 record</h2>
 *
 * <p>实体需要可变（MyBatis 通过 setter 注入查询结果，Service 可能修改字段后
 * 再 update）。record 是不可变的，不适合做实体。DTO 则适合用 record。
 */
public class User {

    /** 主键，自增 */
    private Long id;

    /** 登录名，全站唯一 */
    private String username;

    /** BCrypt 哈希，形如 {@code $2a$10$...}，60 字符 */
    private String passwordHash;

    /** 显示名，可为空 */
    private String nickname;

    /** 是否开启 AI 分析 */
    private boolean aiEnabled;

    /** ACTIVE / DISABLED / DELETED */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // ── getter / setter ────────────────────────────────────────
    // 手写而不用 Lombok：实体字段少，且显式的 getter/setter 让 IDE 跳转更直接。
    // Lombok 留给字段多的类（如后续的 Diary）。

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public boolean isAiEnabled() {
        return aiEnabled;
    }

    public void setAiEnabled(boolean aiEnabled) {
        this.aiEnabled = aiEnabled;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * ⚠️ 刻意不重写 toString()。
     *
     * <p>因为字段里有 passwordHash，一旦有人 {@code log.info("user={}", user)}
     * 就会把密码哈希写进日志。不重写 toString 会打印默认的类名+哈希码，
     * 虽然不友好，但不会泄露。
     *
     * <p>如果确实需要调试输出，用下面这个安全的方法，不要改成 toString。
     */
    public String toSafeString() {
        return "User{id=" + id
                + ", username='" + username + '\''
                + ", nickname='" + nickname + '\''
                + ", aiEnabled=" + aiEnabled
                + ", status='" + status + '\''
                + ", createdAt=" + createdAt
                + '}';   // 不含 passwordHash
    }
}
