package com.sangshen.aidiary.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 「写入日记」的字段契约 —— {@link DiaryCreateRequest} 与 {@link DiaryUpdateRequest} 共用。
 *
 * <h2>为什么需要这个接口</h2>
 *
 * <p>创建和更新两个请求体的字段<b>完全一样</b>。如果各写一份，就会出现：
 * <ul>
 *   <li>{@code tag_ids} 这个 JSON 名要在两个 record 上各写一遍
 *       {@code @JsonProperty} —— 漏改一处就出现"创建能用、更新报字段找不到"</li>
 *   <li>长度限制、校验文案也要各写一份，两边漂移后行为不一致</li>
 * </ul>
 *
 * <h2>这个接口是怎么强制字段名统一的</h2>
 *
 * <p>关键机制：<b>Jackson 会把接口上的 {@code @JsonProperty} 也计入属性名解析</b>，
 * 所以两个 record 只要都 implements 本接口且保留同名同类型的属性，
 * {@code tag_ids} 的映射规则就只有<span>一处</span>定义。
 *
 * <p>另外，接口里的方法（{@code title()} / {@code content()} …）由 record 的
 * 访问器自动实现，不需要在 record 里再写一遍 —— 编译器会检查
 * 「两个 record 是否都提供了这些字段」，<b>缺字段直接编译失败</b>。
 * 这就是把"靠人记住同步"变成"编译期检查"。
 *
 * <h2>⚠️ 为什么是 interface 而不是抽象类</h2>
 *
 * <p>record 不能继承类（它已经继承 {@code java.lang.Record}），
 * 但可以实现接口。所以共用字段只能用接口表达。
 *
 * <h2>PUT 的语义：整体替换</h2>
 *
 * <p>更新接口用的是 PUT 而不是 PATCH，语义是<b>整体替换</b>：
 * 前端必须提交全部字段，没提交的可选字段会被置为 null。
 *
 * <p>所以两个 record 的字段校验规则完全一致（都必填 title 和 content）。
 * 如果将来要支持"只改标题"，应该新增 PATCH 接口，
 * 而不是把 PUT 的校验放松 —— 那会让"漏传字段"从报错变成静默清空数据。
 */
public interface DiaryWriteFields {

    /** 标题，必填，1-200 字符（对应 {@code VARCHAR(200)}）。 */
    String title();

    /** 正文<b>明文</b>，字段必传但可为空串，最长 8000 字符。 */
    String content();

    /** 心情，可选，最长 20 字符（对应 {@code VARCHAR(20)}）。 */
    String mood();

    /** 天气，可选，最长 20 字符（对应 {@code VARCHAR(20)}）。 */
    String weather();

    /** 地点，可选，最长 100 字符（对应 {@code VARCHAR(100)}）。 */
    String location();

    /**
     * 标签 ID 列表。
     *
     * <p>JSON 名是 {@code tag_ids}（Java 属性名是 {@code tagIds}）。
     * 这个映射<b>只在本接口标注一次</b>，两个 record 自动继承。
     *
     * <p>含义上的约定：
     * <ul>
     *   <li>{@code null} 与空列表等价，都表示"没有标签"</li>
     *   <li>去重由 Service 负责（重复 ID 会撞 {@code diary_tag} 联合主键）</li>
     *   <li>"这些 ID 是否都属于当前用户"由 Service 查库校验，
     *       不匹配一律 {@code 40401}（不区分"不存在"和"不是我的"）</li>
     * </ul>
     */
    @JsonProperty("tag_ids")
    @Size(max = 20, message = "一次最多关联 20 个标签")
    List<@Positive(message = "标签 ID 必须是正整数") Long> tagIds();
}
