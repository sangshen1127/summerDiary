package com.sangshen.aidiary.mapper;

import com.sangshen.aidiary.entity.DiaryAnalysis;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 日记分析结果 Mapper。
 *
 * <p>SQL 写在 {@code resources/mapper/DiaryAnalysisMapper.xml}。
 *
 * <h2>两个方法就够了</h2>
 *
 * <p>因为 {@code diary_id} 唯一（一篇日记一条结果），
 * 所以不需要"列表查询""分页查询"—— 查询永远是"查某篇日记的分析"。
 * 写入永远是"覆盖这篇日记的分析"。
 *
 * <h2>⚠️ 所有方法都必须带 userId</h2>
 *
 * <p>本表虽然有 {@code diary_id} 唯一键，但<b>不能</b>只凭 diaryId 查询：
 * 那样用别人的 diaryId 就可能读到别人的分析结果。
 * {@code user_id} 是冗余列，存在的意义就是让权限条件能直接写在这里。
 */
@Mapper
public interface DiaryAnalysisMapper {

    /**
     * 写入或覆盖分析结果。
     *
     * <p>用 {@code ON DUPLICATE KEY UPDATE} 而不是"先查再决定 insert/update"：
     * <ul>
     *   <li>一条 SQL 完成，没有"查不到→插入"之间的竞态窗口</li>
     *   <li>重新分析时天然覆盖旧结果，不需要先删</li>
     * </ul>
     *
     * <p>⚠️ {@code user_id} 也会被更新 —— 它虽然是冗余列，
     * 但更新时保持与 {@code diary} 一致是必要的（否则归属会漂移）。
     *
     * <p>⚠️ <b>不做</b> {@code user_id} 的归属校验！校验由调用方（Worker）
     * 在此之前完成 —— Worker 是先按 {@code userId + sourceId} 取到任务，
     * 再从任务里拿 userId 写入的，来源本身就带归属。
     * 在这里再校验一次既查不到东西（没有 diary 表可 JOIN），
     * 也会让 SQL 变复杂。
     *
     * @param analysis 分析结果（userId / diaryId 必填）
     * @return 影响行数：1 = 新插入，2 = 更新了已有行（MySQL 的语义）
     */
    int upsert(DiaryAnalysis analysis);

    /**
     * 按日记 ID + 归属查询分析结果。
     *
     * <p>「不存在」与「不属于当前用户」都返回 {@code null}，
     * 由 Service 转成 40401 或"尚未分析"。</p>
     *
     * <p>注意：这里的"不存在"是<b>正常情况</b>（日记还没分析），
     * 与 diary 查询的"不存在即越权"语义不同 ——
     * Service 层要区分对待，不能一律 40401。
     *
     * @param diaryId 日记 ID
     * @param userId  当前用户 ID
     * @return 分析结果，没有时返回 null
     */
    DiaryAnalysis selectByDiaryIdAndUserId(@Param("diaryId") Long diaryId,
                                           @Param("userId") Long userId);

    /**
     * 删除某篇日记的分析结果（日记被删除时清理）。
     *
     * <p>带 userId 是双保险：即使调用方传错了 diaryId，
     * 也不会删掉别人的数据。
     *
     * @param diaryId 日记 ID
     * @param userId  当前用户 ID
     * @return 影响行数
     */
    int deleteByDiaryIdAndUserId(@Param("diaryId") Long diaryId,
                                 @Param("userId") Long userId);
}
