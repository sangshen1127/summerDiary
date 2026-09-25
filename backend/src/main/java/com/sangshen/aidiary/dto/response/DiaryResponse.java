package com.sangshen.aidiary.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sangshen.aidiary.entity.Diary;

import java.time.Instant;
import java.util.List;

/**
 * 日记响应 —— Controller 唯一允许返回的日记类型。
 *
 * <p>不直接返回 {@link Diary} 实体的两个理由：
 * <ol>
 *   <li>实体里 {@code contentCiphertext} 是<b>密文</b>，绝不能发给客户端；</li>
 *   <li>{@code userId}、{@code deleted} 是内部状态，前端不需要也不该看到。</li>
 * </ol>
 *
 * <h2>解密发生在哪一步</h2>
 *
 * <p>本 DTO 的 {@code content} 是<b>明文</b>。转换链条：
 * <pre>
 * Mapper 查出 Diary（contentCiphertext 是密文）
 *   → DiaryServiceImpl 调 AesGcmUtil.decrypt() 得到明文
 *   → 放进本 DTO 的 content
 * </pre>
 * 也就是说：<b>能构造出本对象的地方，只有 DiaryServiceImpl</b>。
 *
 * <h2>列表与详情共用本 DTO，但 content 的取舍不同</h2>
 *
 * <p>列表页只需要标题和摘要，不需要正文全文明文。这不只是性能考虑 ——
 * <b>列表页每页 20~100 条，全部解密并传输正文，等于成倍扩大了明文暴露面</b>
 * （网络传输、浏览器内存、日志）。
 *
 * <p>所以约定：
 * <ul>
 *   <li><b>详情接口</b> {@code GET /api/diaries/{id}} → {@code content} 是明文全文</li>
 *   <li><b>列表接口</b> {@code GET /api/diaries} → {@code content} 为 {@code null}</li>
 * </ul>
 * 前端列表页用 {@code content} 时应判空。这一点在
 * {@code frontend/src/types/diary.ts} 里会标注为可选字段。
 *
 * <h2>关于 analysis_status（Phase 3 起有真实取值）</h2>
 *
 * <p>Phase 2 时它恒为 {@code null}（占位字段）。Phase 3 接入 {@code ai_task}
 * 后由 {@code DiaryServiceImpl} 查库填入。取值与
 * {@link AiTaskResponse#status()} 完全一致：
 * {@code pending / running / success / failed / cancelled}，
 * <b>以及"尚无任务"时的 {@code null}</b>。
 *
 * <p>⚠️ 本字段只表达"分析任务的进度"，<b>不表达"AI 功能是否可用"</b>，
 * 也<b>不含</b>分析结果。要完整渲染 AI 区块请调
 * {@code GET /api/diaries/{id}/analysis}（见 {@link DiaryAnalysisDetailResponse}）。
 *
 * @param id             日记 ID
 * @param title          标题
 * @param content        正文<b>明文</b>。列表接口为 null，详情接口为全文
 * @param mood           心情，可为 null
 * @param weather        天气，可为 null
 * @param location       地点，可为 null
 * @param tags           标签列表，**永远不是 null**（无标签时是空数组）
 * @param analysisStatus AI 分析任务状态；尚无任务时为 null
 * @param createdAt      创建时间（ISO-8601 UTC，带 Z）
 * @param updatedAt      更新时间（ISO-8601 UTC，带 Z）
 */
public record DiaryResponse(

        Long id,

        String title,

        String content,

        String mood,

        String weather,

        String location,

        List<TagResponse> tags,

        /**
         * AI 分析任务状态。
         *
         * <h2>取值（Phase 3 起）</h2>
         * <pre>
         *   null       这篇日记没有分析任务（AI 开关关闭时创建的，或 Phase 3 之前的老数据）
         *   "pending"  排队中或等待重试（后端把 PENDING / RETRYING 都归到这里）
         *   "running"  正在分析
         *   "success"  分析完成（结果在 GET /api/diaries/{id}/analysis）
         *   "failed"   分析失败（错误原因与能否重试见同上接口）
         *   "cancelled" 已取消（日记被删除后任务失去意义）
         * </pre>
         *
         * <h2>⚠️ 为什么 RETRYING 归到 "pending"</h2>
         *
         * <p>从用户视角，"等待重试"和"排队中"是同一件事 —— 都是"还没轮到我"。
         * 多暴露一个前端用不到的状态，只会让前端多一个必须处理的分支。
         * 映射在 {@link AiTaskResponse#toExternalStatus} 里统一做。
         *
         * <h2>⚠️ 前端使用约定（重要）</h2>
         *
         * <p>前端类型必须写成 {@code string | null}，
         * <b>不要写死枚举值</b>（如 {@code 'PENDING' | 'SUCCESS'}）——
         * 取值集合将来只增不减，写死会让"新增一个状态"变成前端崩溃。
         *
         * <p>另外注意：<b>本字段不承担「AI 是否启用」的语义</b>。
         * {@code null} 只说明"这篇日记没有任务"，可能因为
         * ① 全局开关关着、② 用户自己关了 AI、③ 日记建于 Phase 3 之前。
         * 要区分这几种情况，用 {@code CurrentUser.ai_enabled} 配合
         * {@link DiaryAnalysisDetailResponse#enabled()}。
         *
         * <h2>⚠️ 列表接口里本字段恒为 null</h2>
         *
         * <p>不是遗漏：列表每页最多 100 条，要填这个字段就得对每条日记
         * 再查一次 {@code ai_task}（N+1）。而列表页并不展示分析状态 ——
         * 那属于详情页。所以 {@code list()} 传 null，并在方法注释里标注。
         */
        @JsonProperty("analysis_status")
        String analysisStatus,

        @JsonProperty("created_at")
        Instant createdAt,

        @JsonProperty("updated_at")
        Instant updatedAt
) {

    /**
     * 从实体转换。
     *
     * <p>⚠️ <b>本方法不做解密</b> —— 明文的解密责任在调用方（{@code DiaryServiceImpl}），
     * 它以参数形式传入。这样做的理由：如果在这里解密，就需要把
     * {@code AesGcmUtil} 注入到一个 DTO 里，而开发文档 §5.3 明确要求
     * 「只有 DiaryService 的读写路径接触加解密」。
     *
     * <p>同理<b>也不查分析状态</b>：本方法没有 Mapper，也不该有。
     * 状态由调用方查好后以字符串传入。
     *
     * @param diary          非空的日记实体
     * @param content        已解密的正文明文；列表场景传 null
     * @param tags           该日记的标签；传 null 时输出空数组（保证契约里 tags 不为 null）
     * @param analysisStatus 已映射成对外取值的分析状态；尚无任务传 null
     * @return 可安全返回给前端的响应对象
     * @throws IllegalArgumentException diary 为 null 时抛出（属于编码错误）
     */
    public static DiaryResponse from(Diary diary, String content,
                                     List<TagResponse> tags, String analysisStatus) {
        if (diary == null) {
            throw new IllegalArgumentException("Diary 不能为 null");
        }
        return new DiaryResponse(
                diary.getId(),
                diary.getTitle(),
                content,
                diary.getMood(),
                diary.getWeather(),
                diary.getLocation(),
                tags == null ? List.of() : tags,
                analysisStatus,
                UtcTime.toInstant(diary.getCreatedAt()),
                UtcTime.toInstant(diary.getUpdatedAt()));
    }
}
