/**
 * 数据传输对象。分为三个子包，<b>用途严格区分，不要混用</b>。
 *
 * <table border="1">
 *   <caption>三个子包的边界</caption>
 *   <tr><th>子包</th><th>用途</th><th>能否直接返回给前端</th></tr>
 *   <tr>
 *     <td>{@code request}</td>
 *     <td>入参。带 Jakarta Validation 注解（{@code @NotBlank}、
 *         {@code @Size} 等）</td>
 *     <td>不适用</td>
 *   </tr>
 *   <tr>
 *     <td>{@code response}</td>
 *     <td>出参。Controller 的<b>唯一</b>允许返回类型</td>
 *     <td>✅ 可以</td>
 *   </tr>
 *   <tr>
 *     <td>{@code ai}</td>
 *     <td>AI 模块内部结构（模型原始输出、向量元数据等）</td>
 *     <td>❌ <b>绝对不行</b></td>
 *   </tr>
 * </table>
 *
 * <h2>为什么 ai 子包不能透给前端</h2>
 *
 * <p>里面会有 {@code used_memory_ids}、内部 Prompt 痕迹、模型原始响应等。
 * 直接暴露等于泄露实现细节，也违反开发文档 §11「日志与响应不含内部 Prompt」的意图。
 *
 * <p>正确做法是显式映射：{@code ai} DTO → 前端契约 DTO。
 *
 * <h2>字段命名</h2>
 *
 * <p>Java 侧用驼峰，靠全局 Jackson 的 {@code SNAKE_CASE} 策略自动转成
 * {@code snake_case} 输出（开发文档 §4.5）。<b>不要在每个字段上加
 * {@code @JsonProperty}</b> —— 那是重复劳动且容易漏。
 */
package com.sangshen.aidiary.dto;
