package com.sangshen.aidiary.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建日记请求（{@code POST /api/diaries}）。
 *
 * <pre>{@code
 * {
 *   "title": "雨后的河边",
 *   "content": "今天下午雨停了……",
 *   "mood": "平静",
 *   "weather": "阴",
 *   "location": "河边",
 *   "tag_ids": [1, 3]
 * }
 * }</pre>
 *
 * <h2>字段定义在哪</h2>
 *
 * <p>JSON 字段名、长度限制、以及 {@code tag_ids} 的映射规则，
 * 全部定义在 {@link DiaryWriteFields}（创建与更新共用的契约），
 * 本 record 只提供字段本身，不重复写一遍注解。
 * 想改字段规则请改那个接口 —— 改一处，两个接口同时生效。
 *
 * <h2>长度限制是怎么定的 —— 全部对齐数据库列宽</h2>
 *
 * <p>每一条 {@code @Size} 都对应 V1 迁移脚本里的列定义。
 * 校验必须比数据库更严或相等，<b>绝不能更松</b>，否则会出现
 * 「接口说参数没问题，但插入时数据库报 Data too long」——
 * 那是一个 500 而不是 400，用户看到"服务器开小差"，
 * 而我们会在日志里看到一个本可以避免的异常。
 *
 * <table border="1">
 *   <caption>DTO 校验 vs 数据库列</caption>
 *   <tr><th>字段</th><th>校验</th><th>列定义</th></tr>
 *   <tr><td>title</td><td>1-200</td><td>VARCHAR(200) NOT NULL</td></tr>
 *   <tr><td>content</td><td>0-8000</td><td>TEXT NOT NULL</td></tr>
 *   <tr><td>mood / weather</td><td>≤20</td><td>VARCHAR(20) NULL</td></tr>
 *   <tr><td>location</td><td>≤100</td><td>VARCHAR(100) NULL</td></tr>
 * </table>
 *
 * <h2>为什么 content 的长度上限是 8000 而不是 TEXT 的 65535</h2>
 *
 * <p>两个理由：
 * <ol>
 *   <li><b>TEXT 列的 65535 是字节数，不是字符数。</b>
 *       中文按 utf8mb4 一字符最多 4 字节，65535 字节理论上只放得下
 *       约 16000 个汉字。用字符数做限制时必须留足余量，
 *       才不会在"正文全是 emoji"时翻车。</li>
 *   <li>8000 字对日记已经很充裕（一篇长文约 3000-5000 字），
 *       限制紧一些能防住"误粘贴一整本书"。正文要经过 AES-GCM 加密
 *       再 Base64（体积 ×1.33）才入库，超长正文会明显拖慢写入。</li>
 * </ol>
 *
 * <p>若将来确实要支持超长日记，正确做法是加 {@code V2__} 迁移把列改成
 * {@code MEDIUMTEXT} <b>并同步</b>调大 {@link DiaryWriteFields} 里的上限，
 * 而不是只改一边。
 *
 * @param title    标题，必填，1-200 字符
 * @param content  正文<b>明文</b>，字段必传但可为空串，最长 8000 字符。
 *                 Service 会加密后再落库
 * @param mood     心情，可选，最长 20 字符
 * @param weather  天气，可选，最长 20 字符
 * @param location 地点，可选，最长 100 字符
 * @param tagIds   标签 ID 列表，可选。必须全部属于当前用户，
 *                 否则整个请求按 {@code 40401} 失败
 */
public record DiaryCreateRequest(

        /*
         * 注意：这里重复标注了校验注解，而不是只在接口上写。
         *
         * 为什么：@NotBlank / @Size 这类约束在接口方法上【不会】被
         * Hibernate Validator 自动继承到 record 组件上 ——
         * 约束继承只对"类继承"生效，接口方法上的约束需要显式声明
         * （@JsonProperty 是 Jackson 的机制，它的行为不同，那个确实能继承）。
         *
         * 所以职责划分是：
         *   - DiaryWriteFields 负责【JSON 字段名】的唯一真源（tag_ids）
         *   - 各 record 负责【自己的校验规则】
         * 两边的字段名一致性由编译器保证（record 必须实现接口的访问器）。
         */
        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题最长 200 字")
        String title,

        /*
         * 刻意用 @NotNull 而不是 @NotBlank：
         * 「只写标题、正文留空」是合法场景（比如只记录了心情）。
         * 但 null（字段完全没传）要拒绝 —— 分不清是"故意留空"
         * 还是"前端漏传了"，而后者更应该暴露出来。
         */
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
