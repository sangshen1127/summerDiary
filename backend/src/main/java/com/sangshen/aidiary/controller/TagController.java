package com.sangshen.aidiary.controller;

import com.sangshen.aidiary.common.Result;
import com.sangshen.aidiary.dto.request.TagCreateRequest;
import com.sangshen.aidiary.dto.response.TagResponse;
import com.sangshen.aidiary.security.SecurityUtils;
import com.sangshen.aidiary.service.TagService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标签接口。
 *
 * <p>契约见开发文档 §4.6「日记与标签」段落：
 * <pre>
 * GET    /api/tags        标签列表        200
 * POST   /api/tags        创建或复用同名   201
 * DELETE /api/tags/{id}   删除未被使用的   200
 * </pre>
 *
 * <h2>Controller 的职责边界</h2>
 *
 * <p>与 {@code DiaryController} 相同：接参数 → 调 Service → 包 {@link Result}。
 * 本类不含任何业务判断。
 *
 * <h2>⚠️ 权限的取法</h2>
 *
 * <p>三个方法都从 {@link SecurityUtils#getCurrentUserId()} 取当前用户，
 * <b>没有</b>任何 {@code userId} 参数。标签是用户私有资源
 * （两个用户都可以有叫「读书」的标签），所以这个取法是安全的关键。
 */
@RestController
@RequestMapping("/api/tags")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    /**
     * 查询当前用户的全部标签。
     *
     * <pre>GET /api/tags</pre>
     *
     * <p>成功返回 200，{@code data} 是标签数组（不是分页结构）——
     * 标签是用户自己维护的少量数据，分页只会给前端增加无意义的处理。
     * 无标签时返回<b>空数组</b>（不是 null）。
     *
     * <p>排序：按创建时间正序（稳定的顺序，便于用户建立位置记忆）。
     *
     * @return 标签列表
     */
    @GetMapping
    public Result<List<TagResponse>> list() {
        return Result.ok(tagService.list(SecurityUtils.getCurrentUserId()));
    }

    /**
     * 创建标签，若同名已存在则直接返回已有的那个。
     *
     * <pre>POST /api/tags   { "name": "读书" }</pre>
     *
     * <h2>⚠️ 这是「创建或复用」，不是纯粹的创建</h2>
     *
     * <p>同名标签已存在时，本接口<b>返回已存在的那个</b>且状态码仍是成功，
     * <b>不会</b>返回 40901 冲突。理由：用户敲一个已存在的名字时，
     * 他想要的结果（有这个标签）已经达成了，报冲突只会让前端多一个
     * 无意义的分支。详见 {@code TagCreateRequest} 的类注释。
     *
     * <p>所以本接口是<b>幂等</b>的：同一个名字调多少次，返回的都是同一个 tagId。
     * 前端不需要"先查再决定创建还是选择"，直接调它拿 id 即可 ——
     * 少一次往返，也少一处竞态。
     *
     * <h2>关于 201 状态码</h2>
     *
     * <p>严格来说"复用了已有标签"时返回 201 不够精确（并没有创建资源）。
     * 但要做到精确就得让 Service 返回"是否新建"的标志，
     * 而前端的处理<b>完全一样</b>（都是拿 data.id 用）。
     * 本项目统一响应体里的业务码 {@code code=0} 才是前端分支的依据，
     * HTTP 状态码仅作参考，所以这里保持 201 简单一致。
     *
     * <p>失败：标签名为空、超长（>30）、含非法字符 → 400 / {@code 40001}。
     *
     * @param request 创建请求
     * @return 新建的或已存在的标签
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<TagResponse> create(@Valid @RequestBody TagCreateRequest request) {
        return Result.ok(tagService.createOrReuse(request, SecurityUtils.getCurrentUserId()));
    }

    /**
     * 删除标签。只允许删除未被任何未删除日记使用的标签。
     *
     * <pre>DELETE /api/tags/3</pre>
     *
     * <p>成功返回 200 + {@code code=0}，无返回数据。
     *
     * <h2>为什么正在被使用的标签不能删</h2>
     *
     * <p>如果允许删除，那些日记的标签关联会变成悬空引用，
     * 表现为"日记莫名其妙少了个标签"而用户并没有编辑过它们 ——
     * 属于静默数据损坏。所以宁可拒绝，让用户先决定那些日记怎么处理。
     *
     * <p>判断口径：只统计<b>未软删除</b>的日记。一篇日记被软删除后，
     * 它占用的标签就"释放"了，可以删除。
     *
     * <p>失败：
     * <ul>
     *   <li>标签不存在<b>或不属于当前用户</b> → 404 / {@code 40401}
     *       （两者响应完全相同，不泄露"这个 ID 是否存在"）</li>
     *   <li>标签正在被 N 篇日记使用 → 409 / {@code 40901}，
     *       message 里带具体篇数，例如
     *       「该标签正在被 3 篇日记使用，无法删除。请先移除这些日记中的该标签。」</li>
     * </ul>
     *
     * <p>⚠️ 40901 是<b>已存在</b>的错误码，没有为"标签被占用"新增错误码 ——
     * 错误码字典是全项目（含前端与 AI 侧）共享的契约，只增不改；
     * 而"资源正在被使用导致操作冲突"本身就是 40901 的定义，
     * 具体原因由 message 表达。
     *
     * @param id 标签 ID
     * @return 空响应体
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        tagService.delete(id, SecurityUtils.getCurrentUserId());
        return Result.ok();
    }
}
