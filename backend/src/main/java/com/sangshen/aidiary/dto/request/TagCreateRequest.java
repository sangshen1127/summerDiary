package com.sangshen.aidiary.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建标签请求（{@code POST /api/tags}）。
 *
 * <pre>{@code
 * { "name": "读书" }
 * }</pre>
 *
 * <h2>⚠️ 本接口是"创建或复用"，不是纯粹的创建</h2>
 *
 * <p>如果当前用户已经有同名标签，接口<b>直接返回已有标签</b>，
 * 且返回码仍是成功（{@code code=0}）。这个行为是刻意的：
 *
 * <p>标签是用户随手打的，前端在"新建标签"输入框里敲一个已经存在的名字
 * 是很自然的操作。如果返回 {@code 40901} 冲突，用户会看到
 * "标签已存在"这种像是出错了的提示，实际上他想要的结果（有这个标签）
 * 已经达成了。所以正确做法是<b>幂等地返回已存在的那个</b>。
 *
 * <p>这也意味着<b>前端不需要先查一遍再决定创建还是选择</b> ——
 * 直接调这个接口，拿返回的 id 用即可。少一次往返，也少一处竞态。
 *
 * <h2>大小写敏感性（一个容易踩的坑）</h2>
 *
 * <p>数据库排序规则是 {@code utf8mb4_0900_ai_ci}，其中 {@code ci} 表示
 * <b>大小写不敏感</b>。也就是说 {@code "Book"} 和 {@code "book"} 在唯一键
 * {@code uk_tag_user_name} 层面<b>是同一个标签</b>。
 *
 * <p>所以查重必须走 SQL（{@code selectByNameAndUserId}），
 * <b>不能</b>用 Java 的 {@code String.equals}（那是大小写敏感的）。
 * 否则会出现"Java 认为不重复 → 插入 → 撞唯一键 → 报 40901"，
 * 而用户看到的提示是莫名的冲突错误。
 *
 * @param name 标签名，必填，1-30 字符（对应 {@code VARCHAR(30)}）
 */
public record TagCreateRequest(

        @NotBlank(message = "标签名不能为空")
        @Size(max = 30, message = "标签名最长 30 字")
        /*
         * 为什么限制字符集：
         * 只校验长度的话，用户可以创建纯空格、纯控制字符、或全角/零宽字符的标签，
         * 这些标签在界面上显示为一片空白或与别的标签看起来一样，
         * 既难以辨认也难以删除。
         *
         * 允许的字符：
         *   \u4e00-\u9fa5  中日韩统一表意文字（汉字）
         *   a-zA-Z0-9      字母数字
         *   下划线、连字符、空格（用来写"读书 笔记"这类多词标签）
         *
         * 用 ^...$ 完整匹配，并禁止首尾空格（中间允许），
         * 避免创建出" 读书"和"读书"两个看起来一样的标签。
         */
        @Pattern(
                regexp = "^[\\u4e00-\\u9fa5a-zA-Z0-9_\\-]([\\u4e00-\\u9fa5a-zA-Z0-9_\\- ]*[\\u4e00-\\u9fa5a-zA-Z0-9_\\-])?$",
                message = "标签名只能包含汉字、字母、数字、下划线、连字符和空格，且不能以空格开头或结尾"
        )
        String name
) {
}
