package com.sangshen.aidiary.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 更新日记请求（{@code PUT /api/diaries/{id}}）。
 *
 * <p>请求体结构与 {@link DiaryCreateRequest} <b>完全一致</b> ——
 * 因为 PUT 的语义是<b>整体替换</b>，不是局部修改。
 *
 * <h2>⚠️ PUT 语义：没传的可选字段会被清空</h2>
 *
 * <p>这是 REST 中 PUT 的标准语义，也是本接口的行为：
 * <pre>
 * PUT /api/diaries/42  { "title": "新标题", "content": "新正文" }
 *   → mood / weather / location 被置为 null，tag_ids 变为空
 * </pre>
 *
 * <p><b>前端必须提交全部字段</b>（编辑器本来就是整表单提交，符合直觉）。
 * 如果确实需要"只改一个字段"，正确做法是新增 PATCH 接口，
 * <b>不要</b>把 PUT 的校验放松成可选 —— 那会让"前端漏传字段"
 * 从明确报错变成静默清空用户数据。
 *
 * <h2>标签是"先清后插"</h2>
 *
 * <p>更新标签关联采用「删除该日记的全部关联，再插入新的」，
 * 而不是计算差集。理由：
 * <ul>
 *   <li>标签数量很小（上限 20），全量替换的代价可以忽略</li>
 *   <li>"先清后插"的语义与 PUT 的整体替换完全一致，
 *       不会出现"差集算错导致旧标签残留"这类隐蔽 bug</li>
 *   <li>它天然支持"清空所有标签"（传空列表或 null）</li>
 * </ul>
 *
 * <h2>不能改的字段</h2>
 *
 * <p>{@code created_at}（日记时间）不在本请求里 —— 修改日记时间
 * 会让"按时间排序"和"那天的记忆"产生歧义，属于 Phase 7 的导出/迁移场景，
 * 需要一个专门的接口（并明确它会影响哪些下游数据）。
 *
 * <p>{@code user_id} 更不可能在这里 —— 归属由服务器从会话取，
 * 见开发文档 §4.7 权限铁律。
 *
 * @param title    标题，必填，1-200 字符
 * @param content  正文<b>明文</b>，字段必传但可为空串，最长 8000 字符
 * @param mood     心情，可选；不传即清空
 * @param weather  天气，可选；不传即清空
 * @param location 地点，可选；不传即清空
 * @param tagIds   标签 ID 列表，可选；不传或传空列表即清空标签
 */
public record DiaryUpdateRequest(

        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题最长 200 字")
        String title,

        @NotNull(message = "正文不能为空（不想写可以不传内容，但不能不传这个字段）")
        @Size(max = 8000, message = "正文最长 8000 字")
        String content,

        @Size(max = 20, message = "心情最长 20 字")
        String mood,

        @Size(max = 20, message = "天气最长 20 字")
        String weather,

        @Size(max = 100, message = "地点最长 100 字")
        String location,

        List<Long> tagIds
) implements DiaryWriteFields {
}
