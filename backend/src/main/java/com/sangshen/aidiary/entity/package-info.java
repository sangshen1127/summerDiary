/**
 * 数据库实体层，与 MySQL 表一一对应。
 *
 * <h2>⚠️ 最重要的一条：Entity 绝不能直接返回给前端</h2>
 *
 * <p>Entity 的字段就是表的字段，包括<b>不该外泄的那些</b>：
 * <ul>
 *   <li>{@code User.passwordHash} —— 密码哈希</li>
 *   <li>{@code Diary.contentCiphertext} —— 密文（前端要的是解密后的明文）</li>
 *   <li>{@code *Deleted} / {@code status} 等内部状态字段</li>
 * </ul>
 *
 * <p><b>❌ 错误</b>：
 * <pre>{@code
 * @GetMapping("/{id}")
 * public Result<Diary> detail(@PathVariable Long id) {
 *     return Result.ok(diaryMapper.selectByIdAndUserId(id, currentUserId));
 *     // 把 content_ciphertext 和 user_id 一起吐给前端了
 * }
 * }</pre>
 *
 * <p><b>✅ 正确</b>：Controller 只返回 {@code dto/response} 下的 DTO，
 * 在 Service 层做显式映射（解密、字段挑选）。
 *
 * <h2>约定</h2>
 * <ul>
 *   <li>字段用驼峰，靠 {@code map-underscore-to-camel-case} 自动映射
 *       （{@code created_at} → {@code createdAt}）</li>
 *   <li>需要筛选/排序的字段必须是实体里的独立字段，不能藏在 JSON 字段里</li>
 *   <li>不使用 MyBatis-Plus 的注解，本项目是纯 MyBatis XML（开发文档 §0 决策 4）</li>
 *   <li>时间字段用 {@code LocalDateTime}，库里统一存 UTC（开发文档 §4.4）</li>
 * </ul>
 *
 * @see com.sangshen.aidiary.dto.response
 */
package com.sangshen.aidiary.entity;
