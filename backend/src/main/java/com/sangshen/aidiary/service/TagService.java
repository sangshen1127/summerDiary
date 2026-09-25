package com.sangshen.aidiary.service;

import com.sangshen.aidiary.dto.request.TagCreateRequest;
import com.sangshen.aidiary.dto.response.TagResponse;

import java.util.List;
import java.util.Set;

/**
 * 标签服务。
 *
 * <p>契约见开发文档 §4.6「日记与标签」段落的三个标签接口：
 * <pre>
 * GET    /api/tags        标签列表
 * POST   /api/tags        创建或复用同名标签
 * DELETE /api/tags/{id}   删除未被使用的标签
 * </pre>
 *
 * <h2>本服务的第一原则：标签是用户私有的</h2>
 *
 * <p>两个用户都可以有叫「读书」的标签（唯一键是 {@code (user_id, name)}）。
 * 所以本接口的每个方法都<b>必须</b>接收 {@code userId}，
 * 而且这个值只能来自 {@code SecurityUtils.getCurrentUserId()}，
 * <b>绝不能</b>来自请求参数（开发文档 §4.7 权限铁律）。
 *
 * <h2>越权拒绝策略：一律 40401，不用 40301</h2>
 *
 * <p>「标签不存在」和「标签不属于当前用户」返回<b>完全相同</b>的
 * {@code 40401}。如果前者 404、后者 403，攻击者就能通过错误码差异
 * 枚举出"哪些标签 ID 真实存在"（开发文档 §4.2 安全约定）。
 */
public interface TagService {

    /**
     * 查询当前用户的全部标签，按创建时间正序。
     *
     * <p>不分页：标签是用户自己维护的少量数据（通常十几个），
     * 分页只会让前端多一层不必要的处理。如果将来真的出现
     * "某个用户有 500 个标签"，再考虑加上限。
     *
     * @param userId 当前用户 ID
     * @return 标签列表，无标签时是空列表（不是 null）
     */
    List<TagResponse> list(Long userId);

    /**
     * 创建标签，若同名标签已存在则<b>直接返回已有的那个</b>。
     *
     * <h2>为什么是"创建或复用"而不是纯粹的创建</h2>
     *
     * <p>理由见 {@code TagCreateRequest} 的类注释。一句话：
     * 用户敲一个已存在的名字时，他想要的结果（有这个标签）已经达成了，
     * 返回 40901 冲突只会让前端多一个无意义的分支。
     *
     * <h2>⚠️ 大小写不敏感带来的实现约束</h2>
     *
     * <p>数据库排序规则 {@code utf8mb4_0900_ai_ci} 是<b>大小写不敏感</b>的，
     * 所以 {@code "Book"} 与 {@code "book"} 在唯一键层面是同一个标签。
     * 查重<b>必须</b>走 SQL（{@code selectByNameAndUserId}），
     * <b>不能</b>用 Java 的 {@code equals} —— 后者大小写敏感，
     * 会得出"不重复"的结论然后插入，撞唯一键报 40901，
     * 用户看到莫名其妙的冲突提示。
     *
     * <h2>并发下的兜底</h2>
     *
     * <p>"先查再插"之间存在竞态窗口（两个请求同时查、都查不到、都插入）。
     * 唯一键 {@code uk_tag_user_name} 是最后一道防线：
     * 并发插入时必定有一个失败，本方法捕获 {@code DuplicateKeyException}
     * 后<b>重新查询并返回已存在的那个</b> —— 对调用方来说依然表现为成功。
     *
     * @param request 已通过 {@code @Valid} 校验的创建请求
     * @param userId  当前用户 ID
     * @return 新建的或已存在的标签
     */
    TagResponse createOrReuse(TagCreateRequest request, Long userId);

    /**
     * 删除标签。只允许删除<b>未被任何未删除日记使用</b>的标签。
     *
     * <h2>为什么禁止删除"正在被使用"的标签</h2>
     *
     * <p>如果允许，用户删掉「读书」标签后，那些日记的标签关联会变成悬空引用
     * （虽然 {@code diary_tag} 里还有行，但 {@code tag} 行没了），
     * 表现为"日记少了个标签"，但用户并没有编辑过那些日记 —— 属于静默数据损坏。
     *
     * <p>所以宁可拒绝并提示"该标签正在被使用"，让用户先自行决定
     * 那些日记怎么处理。这是"显式失败优于静默损坏"的应用。
     *
     * <h2>判断"未被使用"的口径</h2>
     *
     * <p>只统计<b>未软删除</b>的日记（{@code d.deleted = 0}）。
     * 也就是说：一篇日记被软删除后，它占用的标签就"释放"了，可以删除。
     * 这个口径的取舍：软删除的日记理论上可以恢复，
     * 但它已经对用户不可见，不该继续挡住标签管理。
     *
     * @param tagId  标签 ID
     * @param userId 当前用户 ID
     * @throws com.sangshen.aidiary.exception.BusinessException
     *         <ul>
     *           <li>标签不存在或不属于当前用户 → {@code 40401}</li>
     *           <li>标签正被日记使用 → {@code 40901}（冲突，附带具体文案）</li>
     *         </ul>
     */
    void delete(Long tagId, Long userId);

    /**
     * 校验一批标签 ID 是否<b>全部</b>属于当前用户，并返回去重后的结果。
     *
     * <h2>为什么需要"去重"</h2>
     *
     * <p>前端可能传来重复的 ID（例如用户连点两次同一个标签）。
     * 如果不去重，{@code diary_tag} 的联合主键 {@code (diary_id, tag_id)}
     * 会直接报重复键错误 —— 而这是一个 500，用户看到"服务器开小差"，
     * 实际原因只是"传了个重复 ID"。这种 4xx 语义的问题不该以 500 呈现。
     *
     * <p>所以去重在 Service 层做，用 {@code LinkedHashSet} 保留传入顺序
     * （便于日志排查，也让 SQL 参数顺序稳定、便于比对执行计划）。
     *
     * <h2>为什么"存在性校验"必须在这里做，而不是在执行插入的 SQL 里</h2>
     *
     * <p>{@code DiaryTagMapper.insertByDiaryIdAndTagIds} 的 SQL 里
     * <b>已经</b>带了 {@code t.user_id = #{userId}} 条件，它会把不属于
     * 当前用户的标签过滤掉（第二道防线）。但那个过滤是<b>静默</b>的：
     * 传入 3 个 ID、其中 1 个是别人的，结果只会插入 2 行、不报任何错。
     *
     * <p>对用户来说这是"我明明选了 3 个标签，保存后只有 2 个"——
     * 没有任何提示的静默失败。所以必须在插入<b>之前</b>显式校验，
     * 数量对不上就报 {@code 40401}。SQL 里的条件只是防止
     * "有人绕过本方法直接调 Mapper"的第二道防线。
     *
     * @param tagIds 标签 ID 列表，可为 null 或空（表示"没有标签"，直接返回空集合）
     * @param userId 当前用户 ID
     * @return 去重后的 ID 集合，保持传入顺序
     * @throws com.sangshen.aidiary.exception.BusinessException
     *         只要有任意一个 ID 不存在或不属于当前用户，抛 {@code 40401}
     */
    Set<Long> requireOwnedTagIds(List<Long> tagIds, Long userId);

    /**
     * 批量查询一批日记各自关联的标签，用于列表页组装。
     *
     * <p>返回 {@code diaryId → 标签列表} 的映射。
     * 权限隔离由 SQL 完成（JOIN diary 带 {@code user_id}），
     * 所以即使传入别人的日记 ID，也只会返回自己那部分。
     *
     * <p>为什么不用 {@code GROUP_CONCAT} 在列表查询里一次取出：
     * MySQL 的 {@code group_concat_max_len} 默认 1024 字节，
     * 中文标签下 3-4 个就会被<b>静默截断</b>。详见
     * {@code DiaryMapper.selectPageByUserId} 的注释。
     *
     * @param diaryIds 日记 ID 列表。为 null 或空时直接返回空 Map
     *                 （不调用 Mapper —— 空集合会生成非法的 {@code IN ()}）
     * @param userId   当前用户 ID
     * @return 日记 ID → 该日记的标签列表（无标签的日记<b>不会</b>出现在 Map 里，
     *         调用方用 {@code getOrDefault(id, List.of())} 取值）
     */
    java.util.Map<Long, List<TagResponse>> mapTagsByDiaryIds(List<Long> diaryIds, Long userId);
}
