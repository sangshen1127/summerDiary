package com.sangshen.aidiary.controller;

import com.sangshen.aidiary.common.Result;
import com.sangshen.aidiary.dto.query.DiaryQuery;
import com.sangshen.aidiary.dto.request.DiaryCreateRequest;
import com.sangshen.aidiary.dto.request.DiaryUpdateRequest;
import com.sangshen.aidiary.dto.response.DiaryResponse;
import com.sangshen.aidiary.dto.response.PageResponse;
import com.sangshen.aidiary.security.SecurityUtils;
import com.sangshen.aidiary.service.DiaryService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 日记接口。
 *
 * <p>契约见开发文档 §4.6「日记与标签」段落：
 * <pre>
 * POST   /api/diaries         创建          201
 * GET    /api/diaries         分页列表       200
 * GET    /api/diaries/{id}    详情           200
 * PUT    /api/diaries/{id}    修改           200
 * DELETE /api/diaries/{id}    删除（软删除）  200
 * </pre>
 *
 * <h2>Controller 的职责边界（开发文档 §3.1）</h2>
 *
 * <p>只做三件事：接参数、调 Service、包成 {@link Result}。
 *
 * <p><b>禁止</b>：写 SQL、碰 Prompt、直接调模型、做业务规则、开事务。
 * 本类里没有一行 if 判断业务条件 —— 那些都在 {@code DiaryServiceImpl}。
 *
 * <h2>⚠️ owner 条件是怎么保证的</h2>
 *
 * <p>每个方法都调 {@link SecurityUtils#getCurrentUserId()} 从<b>安全上下文</b>
 * 取当前用户，然后作为参数传给 Service。
 *
 * <p>本类<b>没有任何</b> {@code userId} 请求参数或请求体字段 ——
 * 这是开发文档 §4.7 权限铁律的体现。请求里传什么 {@code userId} 都不会被读取，
 * 所以无法通过改请求来伪造身份。
 *
 * <h2>为什么路径变量用 @PathVariable 而查询参数用 @ModelAttribute</h2>
 *
 * <p>{@code @ModelAttribute DiaryQuery} 让 Spring 自动把
 * {@code ?page=0&size=20&keyword=xx&from=...&to=...&mood=xx&tag_id=1}
 * 绑定到 DTO 上。这样：
 * <ul>
 *   <li>新增筛选条件时只改 DTO 一个地方，不用改方法签名</li>
 *   <li>字段类型转换（字符串 → {@code LocalDateTime}）由 Spring 处理，
 *       {@code ?from=2026-09-01T00:00:00} 会正确解析</li>
 *   <li>参数名与 DTO 字段名一一对应，读代码就能知道支持哪些查询参数</li>
 * </ul>
 *
 * <p>⚠️ 注意 {@code DiaryQuery} 里<b>没有</b> {@code userId} 字段，
 * 而且永远不会有 —— 那样会导致 {@code ?userId=2} 就能读别人的日记。
 */
@RestController
@RequestMapping("/api/diaries")
public class DiaryController {

    private final DiaryService diaryService;

    public DiaryController(DiaryService diaryService) {
        this.diaryService = diaryService;
    }

    /**
     * 创建日记。
     *
     * <pre>
     * POST /api/diaries
     * {
     *   "title": "雨后的河边",
     *   "content": "今天下午雨停了……",
     *   "mood": "平静",
     *   "weather": "阴",
     *   "location": "河边",
     *   "tag_ids": [1, 3]
     * }
     * </pre>
     *
     * <p>成功返回 <b>201 Created</b>，{@code data} 是完整的日记（含明文正文与标签）。
     *
     * <p>失败：
     * <ul>
     *   <li>参数不合法（标题为空、超长、tag_ids 超过 20 个）→ 400 / {@code 40001}</li>
     *   <li>{@code tag_ids} 中有不存在或不属于当前用户的 → 404 / {@code 40401}</li>
     *   <li>未登录 → 401 / {@code 40101}（由 SecurityConfig 拦下，走不到本方法）</li>
     * </ul>
     *
     * @param request 创建请求，字段规则见 {@code DiaryCreateRequest}
     * @return 新建的日记
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<DiaryResponse> create(@Valid @RequestBody DiaryCreateRequest request) {
        return Result.ok(diaryService.create(request, SecurityUtils.getCurrentUserId()));
    }

    /**
     * 分页查询日记列表。
     *
     * <pre>
     * GET /api/diaries?page=0&amp;size=20
     * GET /api/diaries?keyword=河边&amp;mood=平静
     * GET /api/diaries?from=2026-09-01T00:00:00&amp;to=2026-09-30T23:59:59
     * GET /api/diaries?tag_id=3
     * </pre>
     *
     * <p>成功返回 200，{@code data} 结构见 {@code PageResponse}。
     *
     * <p><b>⚠️ items 里的 {@code content} 恒为 null</b> ——
     * 列表不返回正文（安全与性能考虑，见 {@code DiaryServiceImpl} 类注释）。
     * 需要正文请调详情接口。
     *
     * <p><b>⚠️ keyword 只搜标题</b>，搜不到正文内容。
     * 这是 AES-GCM 随机 IV 决定的：同一段文字每次密文都不同，
     * SQL 无法对密文做 {@code LIKE}。详见 {@code DiaryQuery} 的类注释。
     *
     * <h2>⚠️ 为什么这里用 7 个 @RequestParam，而不是 @ModelAttribute DiaryQuery</h2>
     *
     * <p>因为查询参数名是 <b>snake_case</b>（{@code tag_id}，见开发文档 §4.6），
     * 而 Java 属性名是 camelCase（{@code tagId}）。这两者<b>绑不上</b>。
     *
     * <p>这是一个真实踩过的 bug：原本写的是
     * {@code list(@ModelAttribute DiaryQuery query)}，看起来更简洁，
     * 但 {@code ?tag_id=14} 会被<b>静默忽略</b> ——
     * {@code tagId} 始终是 null，标签筛选完全不生效，
     * 而且<b>不报任何错</b>，返回的是"没筛选"的完整列表。
     * 是 {@code _verify/TestDiaryApi} 里那条
     * 「A 用 B 的 tagId 筛选应得到空结果」的断言抓出来的。
     *
     * <p><b>为什么不能靠 {@code @JsonProperty("tag_id")} 解决</b>：
     * {@code @JsonProperty} 是 Jackson 的注解，只影响 JSON 序列化/反序列化。
     * 查询参数绑定走的是 Spring 的 {@code WebDataBinder}（内部用
     * {@code BeanWrapperImpl}），它解析的是<b>JavaBean 属性名</b>，
     * 完全不看 Jackson 注解。所以那条路是走不通的。
     *
     * <p>因此这里显式写参数名：{@code @RequestParam(name = "tag_id")}。
     * 代价是方法签名长了一点，收益是<b>"接口接受哪些参数"一眼可见</b>，
     * 不会再有"改了 DTO 字段名但忘了改对外的参数名"这类静默失效。
     * 这也符合本项目的一贯取舍：宁可显式冗长，不要隐式魔法。
     *
     * <p>失败：
     * <ul>
     *   <li>{@code size > 100} 或 {@code size < 1} 或 {@code page < 0}
     *       → 400 / {@code 40001}</li>
     *   <li>{@code from} / {@code to} / {@code page} / {@code size} / {@code tag_id}
     *       类型不合法（如 {@code from=not-a-date}、{@code tag_id=abc}）
     *       → 400 / {@code 40001}</li>
     * </ul>
     *
     * @param page    页码，从 0 开始，默认 0
     * @param size    每页条数，默认 20，上限 100
     * @param keyword 标题关键词（模糊匹配）
     * @param from    时间范围起点（闭区间，ISO-8601）
     * @param to      时间范围终点（闭区间，ISO-8601）
     * @param mood    心情精确匹配
     * @param tagId   标签 ID（注意对外参数名是 {@code tag_id}）
     * @return 分页结果
     */
    @GetMapping
    public Result<PageResponse<DiaryResponse>> list(
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(name = "mood", required = false) String mood,
            @RequestParam(name = "tag_id", required = false) Long tagId) {

        DiaryQuery query = new DiaryQuery();
        // 只在参数真的传了的时候覆盖默认值，否则保留 DTO 里的默认（page=0 / size=20）
        if (page != null) {
            query.setPage(page);
        }
        if (size != null) {
            query.setSize(size);
        }
        query.setKeyword(keyword);
        query.setFrom(from);
        query.setTo(to);
        query.setMood(mood);
        query.setTagId(tagId);

        return Result.ok(diaryService.list(query, SecurityUtils.getCurrentUserId()));
    }

    /**
     * 查询日记详情。
     *
     * <pre>GET /api/diaries/42</pre>
     *
     * <p>成功返回 200，{@code data.content} 是<b>已解密的明文全文</b>。
     *
     * <p>⚠️ {@code data.analysis_status} 在 Phase 2 <b>恒为 null</b>。
     * 那是有意保留的占位字段（AI 分析任务状态属于 Phase 3），
     * 前端应先按 {@code user.ai_enabled} 区分"AI 已关闭"与"尚未分析"两种情况，
     * 详见 {@code DiaryResponse.analysisStatus} 的说明。
     *
     * <p>失败：日记不存在<b>或不属于当前用户</b> → 404 / {@code 40401}。
     * 两者返回<b>完全相同</b>的响应，不泄露"这个 ID 是否存在"。
     *
     * @param id 日记 ID
     * @return 日记详情
     */
    @GetMapping("/{id}")
    public Result<DiaryResponse> detail(@PathVariable("id") Long id) {
        return Result.ok(diaryService.get(id, SecurityUtils.getCurrentUserId()));
    }

    /**
     * 修改日记。
     *
     * <pre>
     * PUT /api/diaries/42
     * { "title": "新标题", "content": "新正文", "mood": "开心", "tag_ids": [2] }
     * </pre>
     *
     * <p><b>⚠️ PUT 是整体替换语义</b>：请求里没带的可选字段会被清空。
     * 例如只传 title 和 content，则 mood / weather / location 变成 null、
     * 标签全部移除。<b>前端必须提交完整表单</b>。
     *
     * <p>正文会用<b>新的随机 IV</b> 重新加密 —— 这是 GCM 的要求，
     * 不是多余的写操作（见 {@code AesGcmUtil} 类注释）。
     *
     * <p>失败：
     * <ul>
     *   <li>日记不存在或不属于当前用户 → 404 / {@code 40401}</li>
     *   <li>{@code tag_ids} 不合法 → 404 / {@code 40401}</li>
     *   <li>参数格式错误 → 400 / {@code 40001}</li>
     * </ul>
     *
     * @param id      日记 ID
     * @param request 更新请求
     * @return 更新后的日记（含明文正文）
     */
    @PutMapping("/{id}")
    public Result<DiaryResponse> update(@PathVariable("id") Long id,
                                        @Valid @RequestBody DiaryUpdateRequest request) {
        return Result.ok(diaryService.update(id, request, SecurityUtils.getCurrentUserId()));
    }

    /**
     * 删除日记（软删除）。
     *
     * <pre>DELETE /api/diaries/42</pre>
     *
     * <p>成功返回 200 + {@code code=0}，无返回数据。
     *
     * <p><b>⚠️ 是软删除</b>（{@code deleted = 1}），不是物理删除。
     * 理由：Phase 3 的补偿任务需要知道"哪篇日记被删了"以便清理
     * 对应的记忆与向量（见 {@code DiaryService.delete}）。
     *
     * <p>删除后该日记对所有查询都不可见（所有 SQL 都带 {@code deleted = 0}），
     * 效果等同于删除；关联的标签会自动"释放"（可以再被删除）。
     *
     * <p>失败：
     * <ul>
     *   <li>日记不存在、不属于当前用户、<b>或已经是删除状态</b>
     *       → 404 / {@code 40401}</li>
     * </ul>
     * 重复删除返回 40401 而不是"成功"：前者诚实地说明"它现在已经不存在了"。
     *
     * @param id 日记 ID
     * @return 空响应体
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        diaryService.delete(id, SecurityUtils.getCurrentUserId());
        return Result.ok();
    }
}
