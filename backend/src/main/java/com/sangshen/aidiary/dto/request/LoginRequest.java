package com.sangshen.aidiary.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求。
 *
 * <pre>{@code
 * {
 *   "username": "zhangsan",
 *   "password": "abc12345"
 * }
 * }</pre>
 *
 * <h2>⚠️ 为什么这里<b>不</b>复用注册的密码强度校验</h2>
 *
 * <p>登录只校验「非空 + 长度上限」，<b>不</b>校验"必须含字母和数字"。
 * 原因：
 * <ol>
 *   <li>规则可能变过。若将来把密码策略从"8-64 位含字母数字"收紧到
 *       "必须含特殊字符"，老用户的密码不满足新规则 —— 如果登录也套用新规则，
 *       这些用户会被<b>永久锁在门外</b>，连登录去改密码的机会都没有。</li>
 *   <li>校验规则本身就是信息泄露。如果登录接口回"密码强度不足"，
 *       攻击者能推断出"这个密码不在合法集合里"，缩小爆破范围。</li>
 * </ol>
 *
 * <p>所以登录只做最基本的防护：非空、长度不超过上限（防止超长输入
 * 打满 BCrypt 计算资源）。
 */
public record LoginRequest(

        @NotBlank(message = "用户名不能为空")
        @Size(max = 50, message = "用户名长度不能超过 50 位")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(max = 64, message = "密码长度不能超过 64 位")
        String password
) {
}
