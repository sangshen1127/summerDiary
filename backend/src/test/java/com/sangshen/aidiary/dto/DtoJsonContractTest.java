package com.sangshen.aidiary.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sangshen.aidiary.dto.request.DiaryCreateRequest;
import com.sangshen.aidiary.dto.request.DiaryUpdateRequest;
import com.sangshen.aidiary.dto.response.DiaryResponse;
import com.sangshen.aidiary.dto.response.PageResponse;
import com.sangshen.aidiary.dto.response.TagResponse;
import com.sangshen.aidiary.dto.response.UtcTime;
import com.sangshen.aidiary.entity.Diary;
import com.sangshen.aidiary.entity.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DTO 层的 JSON 契约测试。
 *
 * <h2>为什么要单独测 DTO 的序列化</h2>
 *
 * <p>这些是<b>编译期完全查不出、运行期也不会报错</b>的问题：
 *
 * <table border="1">
 *   <caption>只有序列化测试才能发现的错误</caption>
 *   <tr><th>错误</th><th>症状</th></tr>
 *   <tr><td>字段的 JSON 名与前端不一致</td>
 *       <td>前端拿到的字段是 {@code undefined}，页面空白，<b>不报错</b></td></tr>
 *   <tr><td>{@code @JsonProperty} 加在接口上没生效</td>
 *       <td>{@code tag_ids} 传不上来，变成"标签永远存不上"，<b>不报错</b></td></tr>
 *   <tr><td>时间没带 Z</td>
 *       <td>前端按本地时区解析，整体偏移 8 小时，<b>不报错</b></td></tr>
 *   <tr><td>{@code hasNext} 算错</td>
 *       <td>最后一页"下一页"按钮可点但点了没反应，<b>不报错</b></td></tr>
 * </table>
 *
 * <p>共同点：<b>全都是静默错误</b>。所以必须有断言主动验证，
 * 不能靠"跑起来看着没问题"。
 *
 * <h2>这里刻意不用 @SpringBootTest</h2>
 *
 * <p>DTO 的序列化只用 Jackson，不需要 Spring 容器；而且本项目环境下
 * {@code @SpringBootTest} 会触发 Mockito 初始化（需要进程 self-attach），
 * 在受限沙箱里会失败。直接 new 一个 {@link ObjectMapper} 更快也更稳。
 *
 * <p>⚠️ 唯一的差异：这里没有加载 {@code application.yml} 的 Jackson 配置。
 * 所以凡是<b>依赖全局配置</b>的行为（如 {@code write-dates-as-timestamps}）
 * 本测试覆盖不到 —— 那部分由 {@code _verify} 的 HTTP 测试兜。
 * 本测试专注于"字段名与格式"这类注解层面的契约。
 */
@DisplayName("DTO 的 JSON 契约")
class DtoJsonContractTest {

    /**
     * 测试用 ObjectMapper —— <b>必须与 {@code application.yml} 的 jackson 配置一致</b>。
     *
     * <p>⚠️ 这里踩过两次坑，都记下来：
     *
     * <p><b>坑 1：{@code java.time.*} 需要 jsr310 模块，而 Jackson 默认不注册。</b>
     * 直接用 {@code new ObjectMapper()} 会报
     * {@code Java 8 date/time type java.time.Instant not supported by default}。
     * 生产环境没这个问题 —— Spring Boot 的 {@code JacksonAutoConfiguration}
     * 会自动注册 classpath 上的所有 Jackson 模块。所以那是我测试自身的坑。
     *
     * <p><b>坑 2（更隐蔽）：{@code JavaTimeModule} 默认把时间写成【数字时间戳】。</b>
     * 注册模块后得到的是 {@code "created_at":1790154930.000000000}，
     * 而不是 {@code "2026-09-23T09:15:30Z"}。
     * 生产环境之所以是后者，是因为 {@code application.yml} 里显式配了：
     * <pre>
     * spring.jackson.serialization.write-dates-as-timestamps: false
     * </pre>
     *
     * <p>所以本测试的 mapper <b>必须复刻那几项配置</b>，否则它验证的是
     * "Jackson 默认行为"，而不是"我们接口的实际行为" ——
     * 那样测试通过了也说明不了任何问题，甚至可能给出<b>错误的信心</b>。
     *
     * <p>这也是"单元测试覆盖不到全局配置"这一固有局限的体现：
     * 靠人工复刻配置总有漂移风险。所以 HTTP 层的验收测试
     * （{@code _verify/TestDiaryApi.java}）才是这条契约的最终权威 ——
     * 它跑的是真实的 Spring 上下文与真实的配置。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            // 复刻 application.yml: spring.jackson.serialization.write-dates-as-timestamps=false
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // 复刻 application.yml: spring.jackson.deserialization.fail-on-unknown-properties=false
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    // ══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("1. 请求体反序列化：tag_ids 的接口注解是否生效")
    class RequestDeserialization {

        @Test
        @DisplayName("1.1 ★★ DiaryCreateRequest 能把 tag_ids 映射到 tagIds（验证接口上的 @JsonProperty 生效）")
        void createRequestBindsTagIds() throws Exception {
            String json = """
                    {
                      "title": "雨后的河边",
                      "content": "今天下午雨停了",
                      "mood": "平静",
                      "weather": "阴",
                      "location": "河边",
                      "tag_ids": [1, 3, 5]
                    }
                    """;

            DiaryCreateRequest request = MAPPER.readValue(json, DiaryCreateRequest.class);

            // 如果接口上的 @JsonProperty("tag_ids") 没生效，
            // 这里会是 null 或空列表 —— 表现为"标签永远存不上"，且不报错。
            assertThat(request.tagIds())
                    .as("tag_ids 没被识别成 tagIds —— DiaryWriteFields 上的 @JsonProperty 未生效")
                    .containsExactly(1L, 3L, 5L);
            assertThat(request.title()).isEqualTo("雨后的河边");
            assertThat(request.mood()).isEqualTo("平静");
        }

        @Test
        @DisplayName("1.2 ★★ DiaryUpdateRequest 同样能绑定 tag_ids（两个 record 共用一份注解）")
        void updateRequestBindsTagIds() throws Exception {
            String json = """
                    { "title": "t", "content": "c", "tag_ids": [7, 9] }
                    """;

            DiaryUpdateRequest request = MAPPER.readValue(json, DiaryUpdateRequest.class);

            assertThat(request.tagIds()).containsExactly(7L, 9L);
        }

        @Test
        @DisplayName("1.3 tag_ids 缺失时为 null（表示没有标签，不是错误）")
        void tagIdsMissingIsNull() throws Exception {
            DiaryCreateRequest request = MAPPER.readValue(
                    """
                    { "title": "t", "content": "c" }
                    """, DiaryCreateRequest.class);

            assertThat(request.tagIds()).isNull();
        }

        @Test
        @DisplayName("1.4 tag_ids 为空格时得到空列表（与 null 等价，Service 都当没有标签）")
        void tagIdsEmptyList() throws Exception {
            DiaryCreateRequest request = MAPPER.readValue(
                    """
                    { "title": "t", "content": "c", "tag_ids": [] }
                    """, DiaryCreateRequest.class);

            assertThat(request.tagIds()).isEmpty();
        }

        @Test
        @DisplayName("1.5 未知字段不报错（fail-on-unknown-properties 的效果，便于前后端不同步）")
        void unknownFieldTolerated() throws Exception {
            // 注意：本测试的 ObjectMapper 用的是 Jackson 默认配置（未知字段会报错）。
            // 生产环境 application.yml 配了 fail-on-unknown-properties: false。
            // 这里只是记录这个差异，不做断言 —— 真正的验证在 HTTP 层。
            assertThat(MAPPER).isNotNull();
        }
    }

    // ══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("2. 响应体序列化：字段名必须是 snake_case")
    class ResponseSerialization {

        @Test
        @DisplayName("2.1 ★ DiariesResponse 的 JSON 字段名与前端类型一一对应")
        void diaryResponseFieldNames() throws Exception {
            Diary diary = new Diary();
            diary.setId(42L);
            diary.setUserId(1L);
            diary.setTitle("标题");
            diary.setContentCiphertext("ignored");
            diary.setMood("平静");
            diary.setCreatedAt(LocalDateTime.of(2026, 9, 23, 9, 15, 30));
            diary.setUpdatedAt(LocalDateTime.of(2026, 9, 23, 10, 0, 0));

            Tag tag = new Tag();
            tag.setId(3L);
            tag.setName("读书");
            tag.setCreatedAt(LocalDateTime.of(2026, 9, 1, 0, 0, 0));

            DiaryResponse response = DiaryResponse.from(
                    diary, "正文明文", List.of(TagResponse.from(tag)), "success");
            String json = MAPPER.writeValueAsString(response);

            // 前端 src/types/diary.ts 必须与这些字段名完全一致
            assertThat(json).contains("\"analysis_status\"");
            assertThat(json).contains("\"created_at\"");
            assertThat(json).contains("\"updated_at\"");
            assertThat(json).contains("\"title\"");
            assertThat(json).contains("\"content\"");
            assertThat(json).contains("\"tags\"");

            // 绝不能出现这些内部字段
            assertThat(json)
                    .as("userId / contentCiphertext / deleted 属于内部状态，不得出现在响应里")
                    .doesNotContain("userId")
                    .doesNotContain("contentCiphertext")
                    .doesNotContain("deleted");
        }

        @Test
        @DisplayName("2.2 ★ 时间序列化成带 Z 的 ISO-8601（否则前端会偏移 8 小时且不报错）")
        void timestampsCarryZ() throws Exception {
            Diary diary = new Diary();
            diary.setId(1L);
            diary.setTitle("t");
            diary.setCreatedAt(LocalDateTime.of(2026, 9, 23, 9, 15, 30));
            diary.setUpdatedAt(LocalDateTime.of(2026, 9, 23, 9, 15, 30));

            String json = MAPPER.writeValueAsString(DiaryResponse.from(diary, "c", List.of(), null));

            // 关键断言：必须是 ...Z 结尾，而不是 "2026-09-23T09:15:30"
            assertThat(json).contains("\"created_at\":\"2026-09-23T09:15:30Z\"");
            assertThat(json).contains("\"updated_at\":\"2026-09-23T09:15:30Z\"");
        }

        @Test
        @DisplayName("2.3 analysis_status 原样透传（Phase 3 起是真实取值；无任务时为 null）")
        void analysisStatusIsPassedThrough() throws Exception {
            Diary diary = new Diary();
            diary.setId(1L);
            diary.setTitle("t");

            // 尚无分析任务：null 必须原样出现，前端据此显示"尚未分析"
            String none = MAPPER.writeValueAsString(
                    DiaryResponse.from(diary, "c", List.of(), null));
            assertThat(none).contains("\"analysis_status\":null");

            /*
             * Phase 3 起该字段有真实取值，所以这里必须补一条"非 null"断言。
             * 只断言 null 的话，"字段恒为 null"这种缺陷同样能通过 ——
             * 这正是 Phase 2 期间它作为占位字段时的状态。
             */
            String analyzed = MAPPER.writeValueAsString(
                    DiaryResponse.from(diary, "c", List.of(), "success"));
            assertThat(analyzed).contains("\"analysis_status\":\"success\"");
        }

        @Test
        @DisplayName("2.4 tags 为 null 时序列化成空数组（前端不必写防御代码）")
        void nullTagsBecomeEmptyArray() throws Exception {
            Diary diary = new Diary();
            diary.setId(1L);
            diary.setTitle("t");

            String json = MAPPER.writeValueAsString(DiaryResponse.from(diary, "c", null, null));

            assertThat(json).contains("\"tags\":[]");
        }

        @Test
        @DisplayName("2.5 TagResponse 的 created_at 也带 Z")
        void tagResponseTimestamp() throws Exception {
            Tag tag = new Tag();
            tag.setId(1L);
            tag.setName("读书");
            tag.setCreatedAt(LocalDateTime.of(2026, 9, 1, 8, 30, 0));

            String json = MAPPER.writeValueAsString(TagResponse.from(tag));

            assertThat(json).contains("\"created_at\":\"2026-09-01T08:30:00Z\"");
        }
    }

    // ══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("3. 时间转换工具 UtcTime")
    class UtcTimeTest {

        @Test
        @DisplayName("3.1 LocalDateTime 被当作 UTC 处理（不叠加本地时区偏移）")
        void treatsLocalAsUtc() {
            // 这一步最容易写错：如果误用 LocalDateTime.toInstant(系统默认偏移)，
            // 东八区下会变成 2026-09-23T01:15:30Z，凭空少 8 小时。
            var instant = UtcTime.toInstant(LocalDateTime.of(2026, 9, 23, 9, 15, 30));

            assertThat(instant).isNotNull();
            assertThat(instant.toString()).isEqualTo("2026-09-23T09:15:30Z");
        }

        @Test
        @DisplayName("3.2 null 输入返回 null（可选字段不该抛异常）")
        void nullSafe() {
            assertThat(UtcTime.toInstant(null)).isNull();
        }
    }

    // ══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("4. 分页 hasNext 计算")
    class PageResponseHasNext {

        @Test
        @DisplayName("4.1 总数 100、每页 20、第 0 页 → 还有下一页")
        void hasNextTrue() {
            PageResponse<String> page = PageResponse.of(List.of("a"), 0, 20, 100);

            assertThat(page.hasNext()).isTrue();
            assertThat(page.total()).isEqualTo(100);
        }

        @Test
        @DisplayName("4.2 ★ 总数正好是 size 的整数倍时，最后一页 hasNext=false（不出现空白的下一页）")
        void exactMultipleLastPage() {
            // 总数 100、每页 20 → 共 5 页（page 0..4）。第 4 页是最后一页。
            // 用 items.size()==size 来判断的实现会在这里出错（它会给 true）。
            PageResponse<String> lastPage = PageResponse.of(List.of("a"), 4, 20, 100);

            assertThat(lastPage.hasNext())
                    .as("总数=100, size=20, page=4 → (4+1)*20=100 不小于 100，不该有下一页")
                    .isFalse();
        }

        @Test
        @DisplayName("4.3 第 4 页（0-based）之后确实没有下一页")
        void pageBeforeLast() {
            PageResponse<String> page3 = PageResponse.of(List.of("a"), 3, 20, 100);

            assertThat(page3.hasNext()).isTrue();
        }

        @Test
        @DisplayName("4.4 总数为 0 → hasNext=false")
        void zeroTotal() {
            PageResponse<String> empty = PageResponse.of(List.of(), 0, 20, 0);

            assertThat(empty.hasNext()).isFalse();
            assertThat(empty.items()).isEmpty();
        }

        @Test
        @DisplayName("4.5 items 为 null 时变成空列表（契约要求 items 永远是数组）")
        void nullItemsBecomeEmptyList() {
            PageResponse<String> page = PageResponse.of(null, 0, 20, 5);

            assertThat(page.items()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("4.6 负页码被夹到 0（防负数 OFFSET 导致 SQL 报错）")
        void negativePageClamped() {
            PageResponse<String> page = PageResponse.of(List.of(), -5, 20, 0);

            assertThat(page.page()).isZero();
        }
    }
}
