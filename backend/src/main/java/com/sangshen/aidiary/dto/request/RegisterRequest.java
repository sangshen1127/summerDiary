package com.sangshen.aidiary.dto.request;

import com.sangshen.aidiary.validation.StrongPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 *
 * <h2>字段与 JSON 的对应关系</h2>
 *
 * <p>本项目不配全局 SNAKE_CASE 命名策略（见 application.yml 的说明），
 * 所以 JSON 字段名 = Java 属性名。这里的字段名都是单个单词，
 * 因此没有 {@code @JsonProperty} —— 请求体长这样：
 *
 * <pre>{@code
 * {
 *   "username": "zhangsan",
 *   "password": "abc12345",
 *   "nickname": "张三"
 * }
 * }</pre>
 *
 * <h2>为什么这里是 record 而不是带 setter 的类</h2>
 *
 * <p>入参 DTO 天然不可变、无行为，record 更贴切；Jackson 从 2.12 起
 * 支持 record 反序列化（通过构造器）。实体的可变需求与 DTO 不同。
 *
 * <h2>校验失败的返回值</h2>
 *
 * <p>{@code @Valid} 校验不通过时抛 {@code MethodArgumentNotValidException}，
 * 由 {@code GlobalExceptionHandler} 统一转成业务码 {@code 40001}，
 * message 形如 {@code "username: 用户名长度需为 3-50 位"}。
 *
 * <p><b>安全提示</b>：错误信息只回显字段名和注解里的文案，
 * <b>不会回显用户输入的值</b> —— 否则密码可能被写进响应体和日志。
 */
public record RegisterRequest(

        /*
         * 用户名规则：
         *   - 3-50 位（数据库 VARCHAR(50)）
         *   - 只允许 字母、数字、下划线、汉字
         *
         * 用正则限制字符集，而不是只在长度上校验，原因是：
         * 允许任意字符会带来「用户名显示混淆」问题 ——
         * 例如用全角空格、零宽字符、控制字符注册，界面上看起来
         * 和别人一模一样，难以区分，也可能干扰日志检索。
         */
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 50, message = "用户名长度需为 3-50 位")
        @Pattern(
                regexp = "^[A-Za-z0-9_\\u4e00-\\u9fa5]+$",
                message = "用户名只能包含字母、数字、下划线或汉字"
        )
        String username,

        /*
         * 密码规则见 @StrongPassword（自定义注解）。
         *
         * ⚠️ 为什么不用 @Pattern 直接写正则：
         *   「至少含字母和数字」用正则表达要写成
         *   ^(?=.*[A-Za-z])(?=.*\d).{8,64}$ 这种前瞻断言，
         *   可读性差且容易写错。自定义注解让规则有名字、可复用、可单独测试。
         *
         * ⚠️ 密码上限 64 位是刻意的：BCrypt 只取前 72 字节，
         *   超长密码的后半部分会被静默忽略，造成「我明明设了很长的密码
         *   但只输前 72 字节就能登录」的困惑。限制在 64 位可避开这个问题。
         */
        @NotBlank(message = "密码不能为空")
        @StrongPassword
        String password,

        /*
         * 昵称可选。为 null 或空串时 Service 会落库为 null。
         * 不允许只由空白字符组成。
         */
        @Size(max = 50, message = "昵称最长 50 位")
        @Pattern(
                regexp = "^$|^\\S(.*\\S)?$",
                message = "昵称不能以空白字符开头或结尾"
        )
        String nickname
) {
}
