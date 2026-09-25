package com.sangshen.aidiary.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 密码强度校验：长度 8-64，且<b>至少包含一个字母和一个数字</b>。
 *
 * <h2>为什么用自定义注解而不是 @Pattern</h2>
 *
 * <p>「至少含字母和数字」用正则表达需要前瞻断言：
 * <pre>{@code ^(?=.*[A-Za-z])(?=.*\d).{8,64}$}</pre>
 *
 * <p>这种写法的问题：
 * <ol>
 *   <li>可读性差 —— 多数人读不出它在表达什么</li>
 *   <li>规则散落 —— 注册、改密码、重置密码三处都要抄一遍，容易抄错</li>
 *   <li>无法单独测试 —— 只能通过接口间接验证</li>
 *   <li>错误信息粗糙 —— 只说「格式不对」，不说是缺字母还是缺数字</li>
 * </ol>
 *
 * <p>自定义注解让规则有名字、集中在一处、可独立测试，
 * 并且能给出精确的失败原因。
 *
 * <h2>为什么上限是 64 而不是更长</h2>
 *
 * <p><b>BCrypt 只取密码的前 72 字节</b>，超出部分被静默忽略。
 * 如果不设上限，用户设置 100 位密码时会以为很安全，实际上
 * 只要输对前 72 字节就能登录 —— 这是个反直觉的安全陷阱。
 * 限制在 64 位（UTF-8 下汉字算 3 字节，64 位字符最多 192 字节，
 * 但密码通常用 ASCII，实际远低于 72）可以避开。
 *
 * <h2>为什么只要求「字母 + 数字」而不要求特殊字符</h2>
 *
 * <p>这是 NIST SP 800-63B 的建议方向：强制特殊字符会促使用户
 * 使用 {@code Password1!} 这类可预测的变体，实际收益有限，
 * 反而增加输入摩擦。真正的防线是 BCrypt 慢哈希 + 登录失败限流（Phase 7）。
 *
 * <p>如果后续要收紧规则，改 {@link StrongPasswordValidator} 一处即可，
 * 所有使用该注解的地方自动生效。
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = StrongPasswordValidator.class)
public @interface StrongPassword {

    String message() default "密码长度需为 8-64 位，且至少包含字母和数字";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
