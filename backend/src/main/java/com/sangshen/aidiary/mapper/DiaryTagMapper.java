package com.sangshen.aidiary.mapper;

import com.sangshen.aidiary.entity.DiaryTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 日记-标签关联表 Mapper。
 *
 * <h2>⚠️ 这是本项目权限处理最需要小心的一张表</h2>
 *
 * <p>{@code diary_tag} <b>没有 {@code user_id} 列</b>，只有
 * {@code (diary_id, tag_id)} 联合主键（开发文档 §7.1 / §4.7）。
 *
 * <p>设计上说得通 —— 归属由 {@code diary} 决定，关联关系本身不产生新的归属信息。
 * 但它带来一个直接后果：
 *
 * <blockquote>
 * <b>不能只凭 diary_id 或 tag_id 就认为有权访问这一行。</b>
 * </blockquote>
 *
 * <p>所以本接口的<b>每一个方法都必须 JOIN {@code diary} 并带
 * {@code d.user_id = #{userId}}</b>，把权限校验放进 SQL，而不是靠调用方自觉。
 *
 * <h2>为什么不用「先查日记归属、再操作关联表」的两步写法</h2>
 *
 * <p>那样也能work，但有两个问题：
 * <ol>
 *   <li>两次数据库往返，且中间存在竞态窗口（查完之后日记可能被删）</li>
 *   <li>「有没有先查归属」变成调用方的责任，漏一处就是越权漏洞；
 *       写在 SQL 里时，漏掉条件会直接查不到/改不到数据，问题当场暴露</li>
 * </ol>
 *
 * <p>这也是开发文档 §4.7 推荐 {@code INSERT ... SELECT ... WHERE d.user_id = ...}
 * 这种写法的原因：<b>把权限判断变成 SQL 的一部分，而不是流程的一部分。</b>
 *
 * @see com.sangshen.aidiary.entity.DiaryTag
 */
@Mapper
public interface DiaryTagMapper {

    /**
     * 给日记批量打标签 —— 用 {@code INSERT ... SELECT} 把归属校验放进 SQL。
     *
     * <p>生成的 SQL 形如：
     * <pre>{@code
     * INSERT INTO diary_tag (diary_id, tag_id)
     * SELECT #{diaryId}, v.tag_id FROM (
     *     SELECT #{id1} AS tag_id UNION ALL SELECT #{id2} ...
     * ) v
     * JOIN diary d ON d.id = #{diaryId}
     * JOIN tag   t ON t.id = v.tag_id
     * WHERE d.user_id = #{userId}          -- ← 日记必须是我的
     *   AND d.deleted = 0                  -- ← 已删除的日记不能再改
     *   AND t.user_id = #{userId}          -- ← 标签也必须是我的
     * }</pre>
     *
     * <p><b>为什么这样写就能防范越权</b>：如果 {@code diaryId} 是别人的，
     * {@code JOIN diary ... WHERE d.user_id = 我} 直接匹配不到任何行，
     * {@code SELECT} 返回空集，于是<b>一行都不会插入</b>、也不报错。
     * 同理，若某个 {@code tagId} 属于别人，那一行会被 {@code JOIN tag} 过滤掉。
     *
     * <p>⚠️ 这里<b>不能</b>用返回行数倒推"是不是全都插进去了" ——
     * 被过滤掉的非法 ID 和"本来就不需要插"无法区分。
     * 「请求的 tagIds 是否都属于我」必须在调用本方法<b>之前</b>用
     * {@link TagMapper#selectByIdsAndUserId} 校验，不匹配就抛 40401。
     * 本方法的 SQL 条件是<b>第二道防线</b>，不是第一道。
     *
     * <p>⚠️ 调用方必须保证 {@code tagIds}：<b>非空</b>（空集合会生成空的
     * {@code UNION ALL} 片段，SQL 语法错误）且<b>已去重</b>（重复 ID 会撞
     * {@code diary_tag} 的联合主键）。这两点由 Service 层负责。
     *
     * @param diaryId 日记 ID
     * @param tagIds  标签 ID 列表，<b>必须非空、去重</b>
     * @param userId  当前用户 ID
     * @return 实际插入的行数（可能少于 tagIds 的数量）
     */
    int insertByDiaryIdAndTagIds(@Param("diaryId") Long diaryId,
                                 @Param("tagIds") List<Long> tagIds,
                                 @Param("userId") Long userId);

    /**
     * 批量查询一批日记各自的标签，用于列表页组装标签。
     *
     * <p>为什么需要「批量」版本：日记列表每页最多 100 条，
     * 如果逐条查标签就是 100 次额外查询（经典 N+1 问题）。
     * 本方法一次查完，Service 再按 {@code diaryId} 分组。
     *
     * <p>权限同样在 SQL 里完成 —— JOIN {@code diary} 并带
     * {@code d.user_id = #{userId}}，所以即使传入别人的 {@code diaryIds}，
     * 也只会返回自己那部分。
     *
     * <p>⚠️ 调用方必须保证 {@code diaryIds} 非空（空集合 → {@code IN ()} 语法错误）。
     * 列表为空时 Service 直接跳过调用，不要传空集合进来。
     *
     * @param diaryIds 日记 ID 列表，<b>必须非空</b>
     * @param userId   当前用户 ID
     * @return 关联行列表（每行含 diaryId + tagId + 标签名），可能为空列表
     */
    List<DiaryTag> selectTagsByDiaryIdsAndUserId(@Param("diaryIds") List<Long> diaryIds,
                                                 @Param("userId") Long userId);

    /**
     * 清空某篇日记的全部标签关联（修改日记标签时先清后插）。
     *
     * <p>⚠️ 这里的 {@code DELETE ... JOIN} 写法要特别注意：
     * <pre>{@code
     * DELETE dt FROM diary_tag dt
     * JOIN diary d ON d.id = dt.diary_id
     * WHERE dt.diary_id = #{diaryId} AND d.user_id = #{userId}
     * }</pre>
     * {@code WHERE dt.diary_id = ...} 这一条<b>绝不能漏</b>。
     * 如果只写 `DELETE dt FROM diary_tag dt JOIN diary d ON ... WHERE d.user_id = ?`，
     * 会删掉这个用户<b>所有日记</b>的全部标签关联 —— 一个条件写漏就是数据灾难。
     *
     * <p>调用方必须保证日记归属已经确认过（先 {@code selectByIdAndUserId} 拿到日记，
     * 或先 {@code softDeleteByIdAndUserId} 返回 1），本方法只做「清空」，
     * 不负责判断"该不该清"。
     *
     * @param diaryId 日记 ID
     * @param userId  当前用户 ID
     * @return 被删除的关联行数（可能是 0，表示这篇日记本来就没标签）
     */
    int deleteByDiaryIdAndUserId(@Param("diaryId") Long diaryId, @Param("userId") Long userId);
}
