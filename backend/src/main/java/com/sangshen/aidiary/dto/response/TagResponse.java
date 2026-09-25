package com.sangshen.aidiary.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sangshen.aidiary.entity.Tag;

import java.time.Instant;

/**
 * 标签响应 —— Controller 唯一允许返回的标签类型。
 *
 * <p>不直接返回 {@link Tag} 实体的理由与 {@code UserResponse} 相同：
 * 实体里有 {@code userId}，属于内部字段，暴露出去等于泄露"这个标签属于第几号用户"。
 * 标签对前端有意义的信息只有两个：id（提交时要用）和 name（显示要用）。
 *
 * @param id        标签 ID
 * @param name      标签名
 * @param createdAt 创建时间（ISO-8601 UTC，带 Z）
 */
public record TagResponse(

        Long id,

        String name,

        /*
         * createdAt → created_at：名字不一致，显式标注
         * （本项目不配全局 SNAKE_CASE，见开发文档 §4.5）。
         *
         * ⚠️ 类型是 Instant 而不是 LocalDateTime —— 这是刻意的，
         *    见 UtcTime.toInstant 的说明。
         */
        @JsonProperty("created_at")
        Instant createdAt
) {

    /**
     * 从实体转换。
     *
     * @param tag 非空的标签实体
     * @return 可安全返回给前端的响应对象
     * @throws IllegalArgumentException tag 为 null 时抛出（属于编码错误，不是业务失败）
     */
    public static TagResponse from(Tag tag) {
        if (tag == null) {
            throw new IllegalArgumentException("Tag 不能为 null");
        }
        return new TagResponse(
                tag.getId(),
                tag.getName(),
                UtcTime.toInstant(tag.getCreatedAt()));
    }
}
