package com.sangshen.aidiary.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sangshen.aidiary.entity.User;

import java.time.Instant;

/**
 * 用户信息响应 —— Controller 唯一允许返回的用户类型。
 *
 * <h2>⚠️ 为什么不直接返回 User 实体</h2>
 *
 * <p>{@code User} 里有 {@code passwordHash}。直接返回实体等于把密码哈希
 * 发给客户端，攻击者可以离线暴力破解。这个类<b>只挑选可公开的字段</b>。
 *
 * <p>本项目不配全局 SNAKE_CASE 命名策略（见 application.yml），
 * 所以名字不一致的字段用 {@code @JsonProperty} 显式标注 ——
 * 好处是「接口返回什么」在 DTO 里一眼可见，不需要心算转换规则。
 *
 * <h2>字段与前端类型的对应</h2>
 *
 * <p>必须与 {@code frontend/src/types/user.ts} 的 {@code CurrentUser} 一致：
 * <pre>{@code
 * export interface CurrentUser {
 *   id: number
 *   username: string
 *   nickname: string | null
 *   ai_enabled: boolean
 *   created_at: string
 * }
 * }</pre>
 *
 * <p>改这里就必须同步改那里 —— Phase 1 的前端模块会一起验证。
 *
 * @param id        用户 ID
 * @param username  登录名
 * @param nickname  显示名，可为 null
 * @param aiEnabled 是否开启 AI 分析
 * @param createdAt 注册时间（ISO-8601 UTC）
 */
public record UserResponse(

        Long id,

        String username,

        String nickname,

        // JSON 里是 ai_enabled，Java 属性是 aiEnabled，名字不一致 → 显式标注
        @JsonProperty("ai_enabled")
        boolean aiEnabled,

        /*
         * createdAt → created_at：名字不一致，显式标注。
         *
         * ⚠️ 类型是 Instant 而不是 LocalDateTime —— 模块 2-3 修正。
         *
         * 原因：开发文档 §4.4 的契约要求接口返回【带时区】的 ISO-8601。
         * LocalDateTime 没有时区，Jackson 会输出 "2026-09-23T10:58:36"
         * （末尾没有 Z）；而 Instant 输出 "2026-09-23T10:58:36Z"。
         *
         * 这个不一致是跑 DemoDiaryApi 打印真实响应时发现的：
         *   POST /api/auth/register → "created_at":"2026-09-23T10:58:36"    ← 少 Z
         *   POST /api/diaries       → "created_at":"2026-09-23T10:58:36Z"   ← 有 Z
         * 同一份 API 里两种格式，前端必须为前者写额外的容错。
         *
         * 为什么以前没暴露：前端 utils/formatTime.ts 里有
         * 「没有时区标识就补一个 Z」的防御，把这个问题盖住了。
         * 但那是在替后端擦屁股 —— 换成 Apifox、第三方客户端或移动端，
         * 就会把无时区的时间当本地时间解析，整体偏移 8 小时且不报错。
         *
         * 转换由 UtcTime.toInstant 完成：数据库存的就是 UTC，
         * 所以显式声明它是 UTC，而不是"假装它没有时区"。
         */
        @JsonProperty("created_at")
        Instant createdAt
) {

    /**
     * 从实体转换。
     *
     * <p><b>刻意不提供</b>「传 null 就返回 null」的宽松行为 ——
     * 调用方必须先判断实体存在。这样「实体为 null 却没处理」
     * 会在调用处暴露成编译期可选警告或明确的空指针，而不是返回
     * 一个 {@code {id: null, username: null}} 让前端困惑。
     *
     * @param user 非空的用户实体
     * @return 可安全返回给前端的响应对象
     * @throws IllegalArgumentException user 为 null 时抛出
     */
    public static UserResponse from(User user) {
        if (user == null) {
            // 这属于编码错误（本应先查空），用 IllegalArgumentException 而不是
            // BusinessException —— 它不是「用户操作导致的业务失败」，
            // 会被 GlobalExceptionHandler 兜底成 50001，提示我们要改代码。
            throw new IllegalArgumentException("User 不能为 null");
        }
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.isAiEnabled(),
                UtcTime.toInstant(user.getCreatedAt())
        );
    }
}
