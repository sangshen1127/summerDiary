package com.sangshen.aidiary.service.impl;

import com.sangshen.aidiary.common.ErrorCode;
import com.sangshen.aidiary.dto.request.TagCreateRequest;
import com.sangshen.aidiary.dto.response.TagResponse;
import com.sangshen.aidiary.entity.DiaryTag;
import com.sangshen.aidiary.entity.Tag;
import com.sangshen.aidiary.exception.BusinessException;
import com.sangshen.aidiary.mapper.DiaryTagMapper;
import com.sangshen.aidiary.mapper.TagMapper;
import com.sangshen.aidiary.service.TagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 标签服务实现。
 *
 * <h2>本类的三条纪律</h2>
 * <ol>
 *   <li><b>所有查询/删除都带 userId</b> —— 标签是用户私有资源</li>
 *   <li><b>不返回 HTTP 状态细节</b> —— 只抛 {@link BusinessException}，
 *       由 {@code GlobalExceptionHandler} 翻译成状态码（开发文档 §3.1）</li>
 *   <li><b>不做日志脱敏之外的日志</b> —— 标签名不是敏感数据，
 *       但也不值得每次都记，只记关键动作</li>
 * </ol>
 */
@Service
public class TagServiceImpl implements TagService {

    private static final Logger log = LoggerFactory.getLogger(TagServiceImpl.class);

    private final TagMapper tagMapper;
    private final DiaryTagMapper diaryTagMapper;

    public TagServiceImpl(TagMapper tagMapper, DiaryTagMapper diaryTagMapper) {
        this.tagMapper = tagMapper;
        this.diaryTagMapper = diaryTagMapper;
    }

    // ══════════════════════════════════════════════════════════
    // 1. 列表
    // ══════════════════════════════════════════════════════════

    @Override
    public List<TagResponse> list(Long userId) {
        return tagMapper.selectByUserId(userId).stream()
                .map(TagResponse::from)
                .toList();
    }

    // ══════════════════════════════════════════════════════════
    // 2. 创建或复用
    // ══════════════════════════════════════════════════════════

    @Override
    @Transactional
    public TagResponse createOrReuse(TagCreateRequest request, Long userId) {
        // 标签名首尾的空白在这里统一裁掉。
        // 为什么不在 DTO 层裁：DTO 的 @Pattern 已经禁止了首尾空格，
        // 但那只覆盖"用户输入"这一条路径；本方法还可能被其他内部调用复用，
        // 在 Service 层再裁一次能保证落库的值一定规范。
        String name = request.name().trim();

        // ── 先查：同名则直接复用 ────────────────────────────────
        // ⚠️ 必须走 SQL 查询而不是 Java 的 equals：
        //    数据库排序规则 utf8mb4_0900_ai_ci 大小写不敏感，
        //    "Book" 与 "book" 在唯一键层面是同一个标签。
        //    用 Java 比较会得出"不重复"然后插入，撞唯一键报 40901。
        //    （这就是本方法的查重注释必须写在 SQL 侧的原因 ——
        //      "查重规则由数据库决定"，不是由 Java 的字符串比较决定）
        Tag existing = tagMapper.selectByNameAndUserId(name, userId);
        if (existing != null) {
            log.debug("标签已存在，直接复用: userId={} tagId={}", userId, existing.getId());
            return TagResponse.from(existing);
        }

        // ── 再插 ────────────────────────────────────────────────
        Tag tag = new Tag();
        tag.setUserId(userId);
        tag.setName(name);

        try {
            tagMapper.insert(tag);
        } catch (DuplicateKeyException ex) {
            // ── 并发兜底 ────────────────────────────────────────
            // 走到这里说明：本请求"查不到"之后、插入之前，
            // 另一个并发请求抢先插入了同名标签。
            //
            // 对调用方来说这不是错误 —— 他想要的结果（有这个标签）达成了。
            // 所以重新查询并返回已存在的那条，而不是把 500/409 抛出去。
            //
            // 这也让本方法整体表现为【幂等】：同名标签无论调用多少次，
            // 结果都是同一个 tagId。
            Tag concurrent = tagMapper.selectByNameAndUserId(name, userId);
            if (concurrent == null) {
                // 理论上不可能：唯一键冲突了却查不到那一行。
                // 说明数据库状态异常（例如事务隔离级别读到旧快照），
                // 这时不该假装成功，原样抛出让人看到。
                throw ex;
            }
            log.debug("并发创建同名标签，复用已存在的那条: userId={} tagId={}",
                    userId, concurrent.getId());
            return TagResponse.from(concurrent);
        }

        // ── 回读一次，补全数据库生成的字段 ──────────────────────
        // ⚠️ 这一步不能省，是【实测抓出来的 bug】：
        //
        //   insert 之后 tag 对象里只有 Java 侧设置的值（id 由 useGeneratedKeys
        //   回填、userId、name），而 created_at 是数据库的
        //   DEFAULT CURRENT_TIMESTAMP 填的 —— Java 侧【没有】这个值。
        //
        //   直接 `TagResponse.from(tag)` 会让创建标签的响应里出现
        //   `"created_at": null`。而"同名复用"路径（上面那个分支）
        //   返回的是从数据库查出来的对象，created_at 是有的。
        //
        //   于是同一个接口出现【不一致】：
        //     第一次创建 "ZZ_x"  → created_at: null      ❌
        //     第二次同名         → created_at: "2026-...Z" ✅
        //
        //   这个缺陷逃过了当时所有测试 —— 因为它们只断言了 id / name，
        //   没有任何一条检查过 created_at。是跑 DemoDiaryApi 打印真实响应时
        //   肉眼发现的。教训：**断言要覆盖响应里的每一个字段**，
        //   否则未覆盖的字段就是"没人看的荒地"。
        //
        // 回读的代价是一次主键/唯一键查询，可以接受；
        // 收益是"时间只有一个来源"（数据库时钟）这条约定得以保持 ——
        // 另一种写法是用 LocalDateTime.now(ZoneOffset.UTC) 在 Java 侧补，
        // 但那会引入应用与数据库时钟不一致的隐患，且两处都能写时间。
        Tag saved = tagMapper.selectByNameAndUserId(name, userId);
        if (saved == null) {
            // 刚插入就查不到，说明有并发删除。这不该静默返回半个对象，
            // 也不该假装成功 —— 让调用方看到明确失败。
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        log.debug("新建标签成功: userId={} tagId={}", userId, saved.getId());
        return TagResponse.from(saved);
    }

    // ══════════════════════════════════════════════════════════
    // 3. 删除
    // ══════════════════════════════════════════════════════════

    @Override
    @Transactional
    public void delete(Long tagId, Long userId) {
        // ── 第一步：确认标签属于当前用户 ────────────────────────
        // 为什么不能只靠后面 delete 的影响行数来判断：
        // 因为要区分两种语义完全不同的失败：
        //   "标签不存在 / 不属于我" → 40401
        //   "标签正在被日记使用"     → 40901
        // 只有先把标签查出来，才能在删之前做"是否被使用"的检查。
        Tag tag = tagMapper.selectByIdsAndUserId(List.of(tagId), userId).stream()
                .findFirst()
                .orElse(null);

        if (tag == null) {
            // 不存在 与 不属于当前用户 都走这里，对外都表现为 40401，
            // 不泄露"这个 ID 真实存在"（开发文档 §4.2）
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        // ── 第二步：确认没有被任何未删除日记使用 ────────────────
        long usedCount = tagMapper.countUsedByDiaries(tagId, userId);
        if (usedCount > 0) {
            // 用 40901（冲突）+ 具体文案。
            //
            // ⚠️ 这里刻意【不新增错误码】：错误码字典是"只增不改"的全项目共享契约
            //    （开发文档 §4.2），新增一个码要同步前端 request.ts 和 AI 侧。
            //    "资源正在被使用导致操作冲突"本身就是 40901 的定义，
            //    具体原因放在 message 里表达，前端按业务码分支处理即可。
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该标签正在被 " + usedCount + " 篇日记使用，无法删除。"
                            + "请先移除这些日记中的该标签。");
        }

        // ── 第三步：删除 ────────────────────────────────────────
        int affected = tagMapper.deleteByIdAndUserId(tagId, userId);
        if (affected == 0) {
            // 走到这里说明"第一步查到了、第三步没删掉"，
            // 唯一的解释是并发：另一个请求刚把这个标签删了。
            // 对调用方来说标签已经不存在了，返回 40401 是诚实的语义
            // （比返回成功更好：成功会让前端以为是自己删的）。
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        log.debug("删除标签成功: userId={} tagId={}", userId, tagId);
    }

    // ══════════════════════════════════════════════════════════
    // 4. 批量查标签归属
    // ══════════════════════════════════════════════════════════

    @Override
    public Set<Long> requireOwnedTagIds(List<Long> tagIds, Long userId) {
        // null 与空列表等价，都表示"没有标签"。
        // 返回空集合而不是 null，让调用方可以直接迭代 —— 见 DiaryService 的用法。
        if (tagIds == null || tagIds.isEmpty()) {
            return Set.of();
        }

        // ── 去重（同时保留传入顺序）────────────────────────────
        // LinkedHashSet 而不是 HashSet：保留顺序让 SQL 参数顺序稳定，
        // 便于排查和比对执行计划；也便于日志里复现问题。
        Set<Long> deduplicated = new LinkedHashSet<>(tagIds);

        // ── 校验：实际查到的数量必须与请求的数量一致 ────────────
        List<Tag> found = tagMapper.selectByIdsAndUserId(new ArrayList<>(deduplicated), userId);

        if (found.size() != deduplicated.size()) {
            // ── 为什么统一返回 40401 而不区分原因 ────────────────
            // 数量对不上有两种可能：
            //   1. 某个 ID 根本不存在
            //   2. 某个 ID 存在但属于别的用户
            // 区分它们等于告诉攻击者"这个标签 ID 真实存在"
            // （开发文档 §4.2 安全约定）。所以统一 40401。
            //
            // 日志里也只记数量，不记具体哪个 ID —— 记了就等于把
            // "哪些 ID 存在"写进了日志，同样的问题。
            throw new BusinessException(ErrorCode.NOT_FOUND,
                    "部分标签不存在或无权使用");
        }

        return deduplicated;
    }

    // ══════════════════════════════════════════════════════════
    // 5. 批量取日记的标签（列表页组装用）
    // ══════════════════════════════════════════════════════════

    @Override
    public Map<Long, List<TagResponse>> mapTagsByDiaryIds(List<Long> diaryIds, Long userId) {
        // ⚠️ 空集合必须提前返回：Mapper 的 <foreach> 会生成非法的 IN ()。
        //    这个判断是"调用方的责任"（见 DiaryTagMapper 的接口注释），
        //    在这里拦掉比在 XML 里加 <if> 兜底更好 —— 后者会把
        //    "传了空集合"这个逻辑漏洞藏起来。
        if (diaryIds == null || diaryIds.isEmpty()) {
            return Map.of();
        }

        // ── 两次查询：关联行 + 标签详情 ──────────────────────────
        List<DiaryTag> relations = diaryTagMapper.selectTagsByDiaryIdsAndUserId(diaryIds, userId);
        if (relations.isEmpty()) {
            return Map.of();
        }

        // 这一批日记涉及到的标签（可能远少于该用户的全部标签）
        Set<Long> tagIdsInUse = relations.stream()
                .map(DiaryTag::getTagId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // 用 selectByIdsAndUserId 批量取，避免逐条查（N+1）
        Map<Long, TagResponse> tagById = tagMapper
                .selectByIdsAndUserId(new ArrayList<>(tagIdsInUse), userId).stream()
                .collect(Collectors.toMap(Tag::getId, TagResponse::from));

        // ── 按 diaryId 分组 ─────────────────────────────────────
        // 用 LinkedHashMap 保证输出顺序稳定（同一批日记每次返回的标签顺序一致），
        // 内部的标签列表按 tagId 升序 —— 让前端渲染结果可复现，
        // 也便于测试断言。
        Map<Long, List<TagResponse>> result = new LinkedHashMap<>();
        relations.stream()
                .sorted(Comparator
                        .comparing(DiaryTag::getDiaryId)
                        .thenComparing(DiaryTag::getTagId))
                .forEach(relation -> {
                    TagResponse tag = tagById.get(relation.getTagId());
                    if (tag == null) {
                        // 关联指向的标签已不存在（例如被并发删除）。
                        // 跳过而不是抛异常：列表页不该因为一条历史脏数据而整体失败。
                        log.warn("日记关联了不存在的标签，已跳过: diaryId={} tagId={}",
                                relation.getDiaryId(), relation.getTagId());
                        return;
                    }
                    result.computeIfAbsent(relation.getDiaryId(), k -> new ArrayList<>()).add(tag);
                });

        return result;
    }
}
