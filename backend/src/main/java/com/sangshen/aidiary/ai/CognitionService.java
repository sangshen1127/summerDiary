package com.sangshen.aidiary.ai;

/**
 * 日记认知分析服务 —— <b>契约由我定义，实现归 AI 模块负责人</b>。
 *
 * <p>签名见开发文档 §10.1（已锁定，只增不改；要改就加新方法并
 * 把旧方法标 {@code @Deprecated}）。
 *
 * <h2>⚠️ 分工边界（《我的职责与任务清单》§7）</h2>
 *
 * <table border="1">
 *   <caption>谁负责什么</caption>
 *   <tr><th>部分</th><th>负责人</th></tr>
 *   <tr><td>本接口的签名与生命周期</td><td>前后端负责人（我）</td></tr>
 *   <tr><td>数据表 {@code diary_analysis} 的迁移脚本</td><td>我（§7.2 规定）</td></tr>
 *   <tr><td>任务入队、Worker、重试、状态查询</td><td>我（框架）</td></tr>
 *   <tr><td><b>本接口的实现</b>（Prompt、LangChain4j 调用、JSON 解析）</td>
 *       <td><b>AI 模块负责人</b></td></tr>
 * </table>
 *
 * <h2>⚠️ 实现方必须遵守的四条约定</h2>
 *
 * <ol>
 *   <li><b>不要自己写数据库</b>。返回值由框架落库（Worker 调 {@code upsert}）。
 *       实现方只管"日记文本 → 结构化结果"这一件事。</li>
 *   <li><b>不要碰权限</b>。传进来的 {@code userId} 已由框架校验过归属，
 *       实现方不需要（也不应该）再查 diary 表判断归属。</li>
 *   <li><b>失败就抛异常，不要返回 null 或空对象</b>。
 *       框架靠异常来判定"这次执行失败了"，进而走重试逻辑。
 *       返回一个 {@code summary=null} 的结果会被当成<b>成功</b>，
 *       于是任务标 SUCCESS 但前端什么都看不到 —— 静默失败。</li>
 *   <li><b>日志里不要出现日记正文与完整 Prompt</b>（开发文档 §5.4 红线）。
 *       允许记录：provider、model、耗时、token 用量、错误类别。</li>
 * </ol>
 *
 * <h2>⚠️ 关于"给 AI 的正文"</h2>
 *
 * <p>调用方（Worker）会先把密文解密成明文再传进来 ——
 * 实现方拿到的是**明文**，不需要也不应该接触 {@code content_ciphertext}。
 *
 * <p>另外 Worker 已经做过最小化处理（只传正文，不传用户昵称等其他信息）。
 * 若实现方还需要额外脱敏，见 {@code Sanitizer} 接口
 * （§7 预留，Phase 4 之后落地）。
 *
 * <h2>异常与重试的对应关系（实现方要理解，因为影响重试行为）</h2>
 *
 * <p>框架会根据异常类型决定是否重试：
 * <ul>
 *   <li>网络错误 / 超时 / HTTP 429 / 5xx → <b>可重试</b>，指数退避</li>
 *   <li>HTTP 4xx（除 429）/ JSON 校验失败 → <b>不重试</b>，直接 FAILED</li>
 * </ul>
 *
 * <p>所以实现方抛异常时应尽量保留原始错误类型（别一律包成
 * {@code RuntimeException("失败")}），否则框架无法正确分类，
 * 会把"参数错误"也重试 3 次，浪费额度。
 */
public interface CognitionService {

    /**
     * 分析一篇日记。
     *
     * @param userId  日记所属用户（已由框架校验）
     * @param diaryId 日记 ID
     * @param content 日记正文的**明文**（由框架解密后传入）
     * @return 结构化分析结果，不能为 null
     * @throws RuntimeException 分析失败时抛出。异常类型会被框架用于分类重试
     */
    DiaryAnalysisResult analyze(Long userId, Long diaryId, String content);
}
