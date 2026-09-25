package com.sangshen.aidiary.entity;

import java.time.LocalDateTime;

/**
 * 日记实体，对应 {@code diary} 表。
 *
 * <h2>⚠️ 这个类绝不能直接返回给前端</h2>
 *
 * <p>两个理由：
 * <ol>
 *   <li>字段 {@link #contentCiphertext} 是<b>密文</b>。密文虽然不可直接阅读，
 *       但把它发给客户端等于把「待破解的数据」交出去，而且客户端本来也不该
 *       知道我们用了什么加密方案。</li>
 *   <li>字段 {@link #deleted}、{@link #userId} 属于内部状态，暴露出去只会
 *       让前端困惑（前端要的是「正文明文」，不是「user_id」）。</li>
 * </ol>
 *
 * <p>Controller 必须返回 {@code dto.response.DiaryResponse}。
 * Phase 8 会加 ArchUnit 断言「Controller 不得依赖 entity 包」，把这条规则
 * 变成构建期检查。
 *
 * <h2>⚠️ 正文在这里是「密文」，不是明文</h2>
 *
 * <p>加解密<b>只发生在 {@code DiaryServiceImpl}</b>（开发文档 §5.3）：
 * <pre>
 * 读：Mapper 查出密文 → Service 调 AesGcmUtil.decrypt() → 变成明文放进 DTO
 * 写：Service 把明文 AesGcmUtil.encrypt() → 变成密文交给 Mapper
 * </pre>
 *
 * <p>所以本类的 {@code contentCiphertext} 字段<b>永远只装密文</b>，
 * 除了 Service 之外任何地方拿到的都应该是明文 DTO。
 *
 * <h2>为什么字段名叫 contentCiphertext 而不是 content</h2>
 *
 * <p>刻意的「名字即警告」：调用方看到这个字段就知道手里是密文，
 * 不会顺手把它当明文塞进响应体。如果叫 {@code content}，
 * 很容易出现「我以为这里是明文」的误用。
 */
public class Diary {

    /** 主键，自增 */
    private Long id;

    /** 所属用户。所有查询/更新/删除都必须带上它做隔离 */
    private Long userId;

    /** 标题。明文存储 —— 列表页的关键词搜索只搜这里（见开发文档 §4.6） */
    private String title;

    /** ⚠️ 正文密文，格式 {@code Base64(IV(12) || ciphertext || authTag(16))}。读写必须走 AesGcmUtil */
    private String contentCiphertext;

    /** 用户填写的心情，可为空 */
    private String mood;

    /** 用户填写的天气，可为空 */
    private String weather;

    /** 地点文本，可为空。第一版不存坐标 */
    private String location;

    /**
     * 软删除标记。{@code false} 正常 / {@code true} 已删除。
     *
     * <p>为什么用软删除：删除日记需要触发「来源记忆 + 向量」的补偿任务
     * （Phase 3 起），立即物理删除会让补偿任务失去依据。
     */
    private boolean deleted;

    /** 创建时间（UTC），即日记时间 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC） */
    private LocalDateTime updatedAt;

    // ── getter / setter ────────────────────────────────────────
    // 手写而不用 Lombok：与 User 保持一致，且显式 getter/setter 让 IDE 跳转更直接。

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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContentCiphertext() {
        return contentCiphertext;
    }

    public void setContentCiphertext(String contentCiphertext) {
        this.contentCiphertext = contentCiphertext;
    }

    public String getMood() {
        return mood;
    }

    public void setMood(String mood) {
        this.mood = mood;
    }

    public String getWeather() {
        return weather;
    }

    public void setWeather(String weather) {
        this.weather = weather;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
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
     * ⚠️ 刻意不重写 {@code toString()}。
     *
     * <p>因为字段里有 {@link #contentCiphertext} —— 日记正文的密文。
     * 一旦有人写 {@code log.info("diary={}", diary)}，密文就会进日志。
     * 而开发文档 §5.4 把「日记正文（明文与密文）」列为<b>红线</b>。
     *
     * <p>不重写 toString 时打印的是默认的「类名 + 哈希码」，
     * 虽然对调试不友好，但绝不会泄露。
     *
     * <p>需要调试输出时用本方法，<b>不要</b>把它改成 toString。
     */
    public String toSafeString() {
        return "Diary{id=" + id
                + ", userId=" + userId
                + ", title='" + title + '\''
                + ", contentCiphertext=" + (contentCiphertext == null
                        ? "null"
                        : "(长度 " + contentCiphertext.length() + "，内容不打印)")
                + ", mood='" + mood + '\''
                + ", weather='" + weather + '\''
                + ", location='" + location + '\''
                + ", deleted=" + deleted
                + ", createdAt=" + createdAt
                + ", updatedAt=" + updatedAt
                + '}';
    }
}
