package com.sangshen.aidiary.service;

import com.sangshen.aidiary.dto.response.DiaryAnalysisDetailResponse;

/**
 * 日记 AI 分析查询服务。
 *
 * <p>契约见开发文档 §4.6：
 * <pre>GET /api/diaries/{id}/analysis   查看 AI 摘要和结构化分析</pre>
 *
 * <h2>⚠️ 本服务只读，不写</h2>
 *
 * <p>分析结果的写入只有一条路径：{@code AiTaskWorker} 调完模型后
 * {@code upsert}。Controller 侧永远不改分析结果 ——
 * 否则用户就能自己编造"AI 分析结论"。
 *
 * <h2>⚠️ 归属校验的顺序不能反</h2>
 *
 * <p>必须先 {@code diaryService.requireOwned}（不是我的日记 → 40401），
 * 再查分析结果。如果反过来（先查结果、有结果就返回），
 * 会出现一个信息泄露：<b>用别人的 diaryId 请求，虽然没有结果但也不报 404</b> ——
 * 攻击者能借此判断"这个 diaryId 存在"，甚至从
 * "存在但没分析结果"与"根本不存在"的差异里套出更多信息。
 */
public interface DiaryAnalysisService {

    /**
     * 查某篇日记的 AI 分析状态与结果。
     *
     * <h2>返回的三种基本情况</h2>
     * <table border="1">
     *   <caption>status 取值与含义</caption>
     *   <tr><th>status</th><th>含义</th><th>前端应显示</th></tr>
     *   <tr><td>{@code null}</td><td>没有分析任务</td>
     *       <td>「尚未分析」+ 开始分析按钮（若 {@code enabled}）</td></tr>
     *   <tr><td>{@code pending} / {@code running}</td><td>在排队或执行中</td>
     *       <td>「分析中…」+ <b>轮询</b></td></tr>
     *   <tr><td>{@code success}</td><td>有结果</td><td>渲染摘要/情绪/主题</td></tr>
     *   <tr><td>{@code failed} / {@code cancelled}</td><td>没成功</td>
     *       <td>错误提示 + 重试按钮（若 {@code can_retry}）</td></tr>
     * </table>
     *
     * <p>⚠️ 本接口<b>不</b>因为"还没分析完"而报错 —— 那会让前端
     * 把正常的等待过程当成故障。永远返回 200 + 当前状态，
     * 由前端根据 {@code status} 决定界面。
     *
     * @param diaryId 日记 ID
     * @param userId  当前用户 ID
     * @return 分析状态 + 结果
     * @throws com.sangshen.aidiary.exception.BusinessException
     *         日记不存在、不属于当前用户、或已被软删除 → {@code 40401}
     */
    DiaryAnalysisDetailResponse getAnalysis(Long diaryId, Long userId);
}
