package com.sangshen.aidiary.mapper;

import com.sangshen.aidiary.entity.Tag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 标签表 Mapper。
 *
 * <p>SQL 写在 {@code resources/mapper/TagMapper.xml}。
 *
 * <h2>⚠️ 标签是用户私有的</h2>
 *
 * <p>两个用户都可以有叫「读书」的标签 —— 数据库唯一键是
 * {@code (user_id, name)}，不是 {@code (name)}。所以：
 * <ul>
 *   <li>查重必须带 {@code user_id}，否则会把别人的同名标签当成自己的</li>
 *   <li>删除必须带 {@code user_id}，否则能删掉别人的标签</li>
 * </ul>
 *
 * <h2>方法命名约定</h2>
 *
 * <p>与 {@link DiaryMapper} 相同：涉及用户私有资源的方法名里带
 * {@code AndUserId} / {@code ByUserId}，让权限过滤在调用处可见。
 */
@Mapper
public interface TagMapper {

    /**
     * 新增标签。
     *
     * <p>{@code created_at} 交给数据库默认值。
     *
     * @param tag 待插入的标签（name + userId 必填）
     * @return 影响行数，正常为 1
     */
    int insert(Tag tag);

    /**
     * 查询某用户的全部标签，按创建时间正序。
     *
     * <p>正序（而不是倒序）的理由：标签列表通常用于筛选，顺序稳定比"最新的在前"
     * 更重要 —— 每新建一个标签就跳到列表最前面会让人找不到原来的位置。
     *
     * @param userId 当前用户 ID
     * @return 标签列表，可能为空列表（不会是 null）
     */
    List<Tag> selectByUserId(@Param("userId") Long userId);

    /**
     * 按 ID 集合批量查当前用户的标签，用于校验「前端传来的 tagIds 是否都属于我」。
     *
     * <p>Service 用它的返回值数量与请求的 ID 数量对比：
     * 少了就说明有 ID 不存在、或属于别人 → 抛 {@code 40401}（不区分两者，
     * 避免泄露「这个标签 ID 真实存在」）。
     *
     * <p>⚠️ 调用方必须保证 {@code ids} 非空。空集合会让 XML 里的
     * {@code <foreach>} 生成 {@code IN ()}，在 MySQL 下是<b>语法错误</b>。
     * 这个判断放在 Service 层做（见 {@code TagServiceImpl}），
     * 而不是在 Mapper 里加 {@code <if>} 兜底 —— 因为"传空集合进来"本身
     * 就是调用方的逻辑漏洞，静默返回空列表会把它藏起来。
     *
     * @param ids    标签 ID 集合，<b>必须非空</b>
     * @param userId 当前用户 ID
     * @return 属于该用户的标签列表（可能少于 ids 的数量）
     */
    List<Tag> selectByIdsAndUserId(@Param("ids") List<Long> ids, @Param("userId") Long userId);

    /**
     * 统计某用户有多少篇<b>未删除</b>的日记使用了这个标签。
     *
     * <p>用于 {@code DELETE /api/tags/{id}} 的前置检查：只允许删除
     * 「未被使用的标签」（开发文档 §4.6）。
     *
     * <p>⚠️ 注意 JOIN {@code diary} 并带 {@code d.user_id} 和 {@code d.deleted = 0}
     * 两个条件，三个理由缺一不可：
     * <ol>
     *   <li>不带 {@code t.user_id}：可能统计到别人标签的使用数</li>
     *   <li>不带 {@code d.user_id}：{@code diary_tag} 没有 user_id 列，
     *       只凭 diary_id 关联会把别人的日记算进来</li>
     *   <li>不带 {@code d.deleted = 0}：一篇已删除的日记会让标签永远删不掉</li>
     * </ol>
     *
     * @param tagId  标签 ID
     * @param userId 当前用户 ID
     * @return 使用该标签的未删除日记数量
     */
    long countUsedByDiaries(@Param("tagId") Long tagId, @Param("userId") Long userId);

    /**
     * 按名称 + 归属查标签，用于「同名复用」。
     *
     * <p>⚠️ 必须用 SQL 查询而不是 Java 的 {@code equals} 判断重名：
     * 数据库排序规则 {@code utf8mb4_0900_ai_ci} 是<b>大小写不敏感</b>的，
     * 所以 {@code "Book"} 和 {@code "book"} 在唯一键层面是同一个。
     * 如果 Service 用 Java 比较（大小写敏感）认为"不重复"就插入，
     * 会直接撞唯一键报 40901，用户看到莫名其妙的冲突提示。
     *
     * @param name   标签名
     * @param userId 当前用户 ID
     * @return 已存在的标签，不存在时返回 {@code null}
     */
    Tag selectByNameAndUserId(@Param("name") String name, @Param("userId") Long userId);

    /**
     * 删除标签（物理删除）。
     *
     * <p>为什么标签用物理删除而日记用软删除：标签没有需要触发的补偿任务
     * （不产生记忆、不进向量库），而且删除前已经检查过「未被任何日记使用」，
     * 所以删掉不会留下悬空引用。
     *
     * <p>{@code WHERE} 带 {@code user_id} 做隔离。返回 0 表示不存在或不属于该用户。
     *
     * @param id     标签 ID
     * @param userId 当前用户 ID
     * @return 影响行数：1 表示成功，0 表示不存在或不属于该用户
     */
    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}
