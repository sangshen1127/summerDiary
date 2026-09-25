package com.sangshen.aidiary.mapper;

import com.sangshen.aidiary.dto.query.DiaryQuery;
import com.sangshen.aidiary.entity.Diary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 日记表 Mapper。
 *
 * <p>SQL 全部写在 {@code resources/mapper/DiaryMapper.xml}。
 *
 * <h2>⚠️ 本接口的每一个方法都必须带 userId</h2>
 *
 * <p>{@code diary} 是用户私有资源，方法名里一律体现 {@code AndUserId}，
 * 让「有没有做权限过滤」在<b>调用处一眼可见</b>。
 *
 * <p><b>绝对禁止</b>的写法（跨用户泄露的根源，见开发文档 §4.7）：
 * <pre>{@code
 * // ❌ 先按 id 查出来，再在 Java 里判断归属
 * Diary diary = diaryMapper.selectById(id);
 * if (diary.getUserId().equals(currentUserId)) { ... }
 * }</pre>
 * 这种写法的两个问题：
 * <ol>
 *   <li>别人的日记<b>已经被读进内存</b>了，日志、异常堆栈、调试器都可能带出来</li>
 *   <li>「有没有判断归属」变成每个调用点自己的责任，漏一个就是漏洞；
 *       而写在 SQL 里时，漏掉条件会直接查不到数据，问题立刻暴露</li>
 * </ol>
 *
 * <h2>软删除的约定</h2>
 *
 * <p>所有查询都带 {@code deleted = 0}；删除是 {@code UPDATE ... SET deleted = 1}，
 * 不是物理 DELETE。理由见 {@link Diary#isDeleted()}。
 */
@Mapper
public interface DiaryMapper {

    /**
     * 新增日记。
     *
     * <p>插入后 {@code diary.id} 会被回填（XML 配了 {@code useGeneratedKeys}），
     * Service 可以直接拿它去写 {@code diary_tag}，不用再查一次。
     *
     * <p>{@code created_at} / {@code updated_at} 交给数据库默认值，不在这里赋值 ——
     * 保证时间来源唯一（数据库时钟），避免应用与数据库时钟不一致导致排序错乱。
     *
     * @param diary 待插入的日记。{@code contentCiphertext} 必须是密文
     * @return 影响行数，正常为 1
     */
    int insert(Diary diary);

    /**
     * 按 ID + 归属查日记详情（不含已删除）。
     *
     * <p>「查不到」和「不属于当前用户」都返回 {@code null}，
     * Service 一律转成 {@code 40401}，不区分 —— 防止攻击者通过
     * 「404 还是 403」的差异枚举出哪些 ID 真实存在（开发文档 §4.2）。
     *
     * @param id     日记 ID
     * @param userId 当前用户 ID，由安全上下文取得，<b>不接受前端传入</b>
     * @return 日记（含密文正文），不存在或不属于该用户时返回 {@code null}
     */
    Diary selectByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 按条件统计总数，用于分页。
     *
     * <p>筛选条件必须与 {@link #selectPageByUserId} <b>完全一致</b>，
     * 否则会出现「总数说有 50 条，翻到第 3 页却是空的」这类问题。
     * 两个方法共用 XML 里的同一个 {@code <sql>} 条件片段来保证一致。
     *
     * @param query  筛选条件（不含 userId）
     * @param userId 当前用户 ID
     * @return 符合条件的总条数
     */
    long countByUserId(@Param("query") DiaryQuery query, @Param("userId") Long userId);

    /**
     * 分页查询日记列表（不含已删除），按 {@code created_at} 倒序。
     *
     * <p><b>刻意不返回标签</b>：标签是另一张表，用 {@code GROUP_CONCAT} 拼进本查询
     * 会踩 MySQL 的 {@code group_concat_max_len} 默认 1024 字节截断（标签名
     * VARCHAR(30)，中文情况下 3-4 个标签就够触发），而且是<b>静默截断</b>——
     * 数据看起来对，实际少了几个标签。
     *
     * <p>正确做法是两次查询：本方法拿分页数据，再用
     * {@link DiaryTagMapper#selectTagsByDiaryIdsAndUserId} 一次批量取回这批日记的标签，
     * 由 Service 组装。代价是多一次 SQL，换来的是不会有静默截断。
     *
     * @param query  筛选条件（含分页参数）
     * @param userId 当前用户 ID
     * @return 当前页的日记列表，可能为空列表（不会是 null）
     */
    List<Diary> selectPageByUserId(@Param("query") DiaryQuery query, @Param("userId") Long userId);

    /**
     * 按 ID + 归属更新日记（标题 / 正文密文 / 心情 / 天气 / 地点）。
     *
     * <p>{@code WHERE} 里同时有 {@code id} 和 {@code user_id} —— 这是权限隔离的关键。
     * 返回值是「实际改了几行」：返回 0 说明日记不存在或不属于该用户，
     * Service 据此抛 {@code 40401}。
     *
     * <p>用「影响行数」而不是「先查后改」来判断权限：
     * 后者是两次数据库往返，且中间存在竞态窗口。
     *
     * <p>{@code updated_at} 不需要在这里赋值：表定义里是
     * {@code ON UPDATE CURRENT_TIMESTAMP}，数据库会自动维护。
     *
     * @param diary  要更新的字段。必须已设置 {@code id} 与 {@code userId}
     * @return 影响行数：1 表示成功，0 表示不存在或不属于该用户
     */
    int updateByIdAndUserId(Diary diary);

    /**
     * 软删除日记（{@code deleted = 1}）。
     *
     * <p>不用物理 DELETE 的理由：删除日记要触发「来源记忆 + 向量」的补偿任务
     * （Phase 3 起），立即物理删除会让补偿任务失去依据。
     *
     * <p>注意：这里<b>不</b>清理 {@code diary_tag} 关联。软删除的日记本身已经
     * 查不出来（所有查询都带 {@code deleted = 0}），关联行留着不影响正确性；
     * 等账户清除（Phase 7）时再统一物理清理。
     *
     * @param id     日记 ID
     * @param userId 当前用户 ID
     * @return 影响行数：1 表示成功，0 表示不存在、不属于该用户、或已经是删除状态
     */
    int softDeleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}
