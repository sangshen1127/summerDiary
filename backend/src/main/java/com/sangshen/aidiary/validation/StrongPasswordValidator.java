package com.sangshen.aidiary.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link StrongPassword} 的校验实现。
 *
 * <h2>校验规则</h2>
 * <ol>
 *   <li>不为 {@code null}（是否允许空值由 {@code @NotBlank} 负责，这里选择
 *       对 null 返回 true，避免两个注解说重复的错误信息）</li>
 *   <li>长度 8-64</li>
 *   <li>至少含 1 个字母（大小写均可）</li>
 *   <li>至少含 1 个数字</li>
 * </ol>
 *
 * <h2>长度为什么按「字符数」而不是「字节数」</h2>
 *
 * <p>用户感知的是字符数，不是字节数。BCrypt 的 72 <b>字节</b>限制
 * 由上限 64 字符间接规避（详见 {@link StrongPassword} 的说明）。
 * 如果按字节数校验，中文密码会被莫名判为超长，体验很差。
 *
 * <h2>刻意不做的事</h2>
 *
 * <p><b>不记录密码内容</b>。校验失败时只说明「缺什么」，
 * 绝不把密码写进日志或异常信息 —— 见开发文档 §5.4 的日志红线。
 * 所以本类里没有 {@code log} 调用。
 */
public class StrongPasswordValidator implements ConstraintValidator<StrongPassword, String> {

    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 64;

    /** 只 ASCII 字母数字，避免 Unicode 数字/字母带来的歧义（如全角、罗马数字） */
    private static final java.util.regex.Pattern LETTER = java.util.regex.Pattern.compile("[A-Za-z]");
    private static final java.util.regex.Pattern DIGIT = java.util.regex.Pattern.compile("[0-9]");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // null 交给 @NotBlank 处理，这里不重复报错
        if (value == null) {
            return true;
        }

        int length = value.length();
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            return false;
        }

        // 用 ASCII 正则而不是 Character.isLetter/isDigit：
        // 后者对全角数字（１２３）、罗马数字（Ⅻ）等也返回 true，
        // 会让「必须含数字」这条规则被意料之外的字符满足。
        return LETTER.matcher(value).find() && DIGIT.matcher(value).find();
    }
}
