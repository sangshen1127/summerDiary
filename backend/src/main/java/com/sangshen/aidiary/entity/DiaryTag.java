package com.sangshen.aidiary.entity;

/**
 * 日记-标签关联行，对应 {@code diary_tag} 表。
 *
 * <p>只用于「按日记 ID 批量查标签」时的结果承接，见
 * {@code DiaryTagMapper.selectTagsByDiaryIdsAndUserId}。
 *
 * <h2>⚠️ 本表没有 user_id 列 —— 这是刻意的设计，但也是个陷阱</h2>
 *
 * <p>{@code diary_tag} 只有 {@code (diary_id, tag_id)} 两列（开发文档 §7.1）。
 * 设计上说得通（关联关系本身不产生新的归属信息，归属由 {@code diary} 决定），
 * 但<b>它意味着不能只凭 diary_id 就认为有权访问</b>。
 *
 * <p>所有涉及本表的查询都必须 <b>JOIN diary 并带 diary.user_id 条件</b>：
 * <pre>{@code
 * -- ✅ 正确：权限在 SQL 层完成
 * SELECT dt.diary_id, t.id, t.name
 * FROM diary_tag dt
 * JOIN diary d ON d.id = dt.diary_id
 * JOIN tag   t ON t.id = dt.tag_id
 * WHERE d.user_id = #{userId} AND d.deleted = 0
 *   AND dt.diary_id IN (...)
 *
 * -- ❌ 错误：只凭 diary_id，别人的日记标签也能查出来
 * SELECT tag_id FROM diary_tag WHERE diary_id = #{diaryId}
 * }</pre>
 *
 * <p>写入同理，用 {@code INSERT ... SELECT ... JOIN diary} 把归属校验放进 SQL
 * （见 {@code DiaryTagMapper.insertByDiaryIdAndTagIds}）。
 */
public class DiaryTag {

    /** 日记 ID */
    private Long diaryId;

    /** 标签 ID */
    private Long tagId;

    public Long getDiaryId() {
        return diaryId;
    }

    public void setDiaryId(Long diaryId) {
        this.diaryId = diaryId;
    }

    public Long getTagId() {
        return tagId;
    }

    public void setTagId(Long tagId) {
        this.tagId = tagId;
    }

    @Override
    public String toString() {
        return "DiaryTag{diaryId=" + diaryId + ", tagId=" + tagId + '}';
    }
}
