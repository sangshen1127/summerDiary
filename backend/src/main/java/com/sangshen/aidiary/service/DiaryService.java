package com.sangshen.aidiary.service;

import com.sangshen.aidiary.dto.query.DiaryQuery;
import com.sangshen.aidiary.dto.request.DiaryCreateRequest;
import com.sangshen.aidiary.dto.request.DiaryUpdateRequest;
import com.sangshen.aidiary.dto.response.DiaryResponse;
import com.sangshen.aidiary.dto.response.PageResponse;

/**
 * 日记服务。
 *
 * <p>契约见开发文档 §4.6「日记与标签」段落：
 * <pre>
 * POST   /api/diaries        创建
 * GET    /api/diaries        分页列表（keyword / from / to / mood / tag_id）
 * GET    /api/diaries/{id}   详情
 * PUT    /api/diaries/{id}   修改
 * DELETE /api/diaries/{id}   删除（软删除）
 * </pre>
 *
 * <h2>⚠️ 本服务是唯一接触「明文正文」的地方</h2>
 *
 * <p>开发文档 §5.3 明确规定：
 *
 * <blockquote>
 * 只有 {@code DiaryService} 的读写路径接触加解密，<b>其他所有地方拿到的都是明文</b>。
 * </blockquote>
 *
 * <p>具体含义：
 * <ul>
 *   <li><b>写入</b>：Controller 传来的 {@code content} 是明文，
 *       本服务用 {@code AesGcmUtil.encrypt()} 加密后才交给 Mapper</li>
 *   <li><b>读出</b>：Mapper 从数据库取出的是密文，
 *       本服务用 {@code AesGcmUtil.decrypt()} 解密后才放进 DTO</li>
 *   <li>除此之外的任何组件（Controller、TagService、将来的 AI 模块）
 *       都只应看到明文，<b>不应该</b>接触 {@code content_ciphertext}</li>
 * </ul>
 *
 * <p>这样划分的好处：加密格式（IV 长度、tag 长度、Base64 布局）的改动
 * 只需要动这一个类，不会散落到各处。
 *
 * <h2>越权一律 40401</h2>
 *
 * <p>「日记不存在」和「日记不属于当前用户」返回<b>完全相同</b>的
 * {@code 40401}，不用 40301 —— 防止通过错误码差异枚举出哪些 ID 真实存在
 * （开发文档 §4.2）。
 *
 * <p>实现上靠 {@code WHERE id = ? AND user_id = ?}：
 * 查不到就是 40401，不需要先查出来再在 Java 里判断归属。
 */
public interface DiaryService {

    /**
     * 创建日记。
     *
     * <h2>一个事务里做完三件事</h2>
     * <ol>
     *   <li>校验 {@code tagIds} 是否全部属于当前用户（不匹配 → 40401）</li>
     *   <li>加密正文并插入 {@code diary}</li>
     *   <li>插入 {@code diary_tag} 关联</li>
     * </ol>
     *
     * <p>必须是同一个事务：如果第 3 步失败而第 2 步已提交，
     * 就会出现"日记建好了但标签没打上"的半成品状态。
     *
     * <h2>Phase 3：事务内只发事件，<b>绝不同步调模型</b></h2>
     *
     * <p>本方法在事务内发布 {@code DiaryCreatedEvent}，
     * 由 {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code @Async}
     * 在事务提交后入队 AI 分析任务。整条链路里本方法<b>不接触</b>
     * {@code CognitionService}、不等待任何网络调用。
     *
     * <p>⚠️ 这是验收标准「<b>模型超时/报错时正文照样保存成功</b>」的实现方式。
     * 反过来，如果在这里同步调模型：
     * <ul>
     *   <li>模型超时 30 秒 → 用户点"保存"后要转圈 30 秒</li>
     *   <li>模型报错 → 事务回滚 → <b>日记丢失</b>（用户白写）</li>
     *   <li>模型很慢 → 数据库事务长时间打开，占着连接池</li>
     * </ul>
     * 这三条都是致命的，所以"异步"不是优化，是<b>正确性要求</b>。
     *
     * @param request 已通过 {@code @Valid} 校验的创建请求
     * @param userId  当前用户 ID（来自安全上下文，不接受前端传入）
     * @return 新建的日记（含明文正文与标签）
     * @throws com.sangshen.aidiary.exception.BusinessException
     *         标签不存在或不属于当前用户 → {@code 40401}
     */
    DiaryResponse create(DiaryCreateRequest request, Long userId);

    /**
     * 查询日记详情。
     *
     * <p>返回的 {@code content} 是<b>已解密的明文全文</b>。
     *
     * <p>{@code analysis_status} 是真实取值（Phase 3 起），
     * 由一次 {@code ai_task} 查询得到。完整渲染 AI 区块请调
     * {@code GET /api/diaries/{id}/analysis}。
     *
     * @param diaryId 日记 ID
     * @param userId  当前用户 ID
     * @return 日记详情（含明文正文与标签）
     * @throws com.sangshen.aidiary.exception.BusinessException
     *         不存在或不属于当前用户 → {@code 40401}
     */
    DiaryResponse get(Long diaryId, Long userId);

    /**
     * 断言日记存在且属于当前用户，否则抛 {@code 40401}。
     *
     * <h2>为什么单独开一个方法，而不是让调用方用 {@link #get}</h2>
     *
     * <p>AI 任务相关的接口（{@code POST /api/ai/diaries/{id}/analyze} 等）
     * 需要做同一件事的<b>第一步</b>：确认"这篇日记是我的"。
     * 如果它们去调 {@link #get}，会有两个问题：
     * <ul>
     *   <li>{@link #get} <b>会解密正文</b>，而"确认归属"根本不需要正文 ——
     *       白白把明文读进内存，凭空扩大明文暴露面（§5.4 的精神）</li>
     *   <li>将来 {@link #get} 的返回结构变化（比如加字段、改成懒加载）
     *       会波及一堆只是想做权限校验的调用方</li>
     * </ul>
     *
     * <h2>为什么是"抛异常"而不是返回 boolean</h2>
     *
     * <p>本项目里「不存在」与「不属于你」<b>都是 40401</b>，没有第三种结果。
     * 返回 boolean 会诱使调用方写出
     * {@code if (!exists) throw new BusinessException(NOT_FOUND)} ——
     * 那段样板代码抄错一次（比如有人写成 40301）就会泄露资源存在性。
     * 让本方法直接抛，权限策略就只有一处定义。
     *
     * <p>命名沿用 {@code TagService.requireOwnedTagIds} 的约定：
     * {@code require*} 表示"不满足就抛"。
     *
     * @param diaryId 日记 ID
     * @param userId  当前用户 ID
     * @throws com.sangshen.aidiary.exception.BusinessException
     *         不存在、不属于当前用户、或已被软删除 → {@code 40401}
     */
    void requireOwned(Long diaryId, Long userId);

    /**
     * 分页查询日记列表。
     *
     * <h2>返回的 content 为 null</h2>
     *
     * <p>列表页不返回正文，理由见 {@code DiaryResponse} 的类注释 ——
     * 每页几十上百条全部解密并传输，等于成倍扩大明文暴露面
     * （网络、浏览器内存、日志）。
     *
     * <p>前端的处理：列表页本来只显示标题与摘要，
     * 需要正文时点进详情页（{@link #get}）。
     *
     * <h2>⚠️ 列表里 analysis_status 也恒为 null（与 content 同理）</h2>
     *
     * <p>要填它就得对每条日记各查一次 {@code ai_task}（N+1）。
     * 而列表页不展示分析状态 —— 那是详情页的事。
     * 所以这里传 null 而不是"忘了填"，需要状态请点进详情或调
     * {@code GET /api/diaries/{id}/analysis}。
     *
     * <h2>size 超过 100 会失败</h2>
     *
     * <p>返回 {@code 40001}。这是开发文档 §4.3 的契约，
     * 而且必须由 Service 强制 —— 不能只靠前端的 size 参数约束，
     * 因为攻击者可以直接构造 {@code ?size=1000000} 拖垮数据库。
     *
     * @param query  筛选与分页条件（<b>不含</b> userId）
     * @param userId 当前用户 ID
     * @return 分页结果，{@code items} 中每条的 {@code content} 都是 null
     * @throws com.sangshen.aidiary.exception.BusinessException
     *         {@code size > 100} 或 {@code page < 0} 或 {@code size < 1} → {@code 40001}
     */
    PageResponse<DiaryResponse> list(DiaryQuery query, Long userId);
    /**
     * 修改日记。
     *
     * <p>PUT 语义是<b>整体替换</b>：没传的可选字段会被清空。
     * 详见 {@code DiaryUpdateRequest}。
     *
     * <h2>标签的处理：先清后插</h2>
     *
     * <p>不计算差集，直接删掉该日记的全部关联再插入新的。理由见
     * {@code DiaryUpdateRequest} —— 标签数量小，全量替换的语义更简单、
     * 不会出现"差集算错导致旧标签残留"。
     *
     * <h2>一个事务里做完三件事</h2>
     * <ol>
     *   <li>校验归属与 tagIds</li>
     *   <li>更新 {@code diary}（正文重新加密，<b>每次都用新的随机 IV</b>）</li>
     *   <li>清空并重建 {@code diary_tag}</li>
     * </ol>
     *
     * @param diaryId 日记 ID
     * @param request 已通过 {@code @Valid} 校验的更新请求
     * @param userId  当前用户 ID
     * @return 更新后的日记
     * @throws com.sangshen.aidiary.exception.BusinessException
     *         日记不存在/不属于当前用户，或标签不合法 → {@code 40401}
     */
    DiaryResponse update(Long diaryId, DiaryUpdateRequest request, Long userId);

    /**
     * 删除日记（<b>软删除</b>：{@code deleted = 1}）。
     *
     * <h2>为什么是软删除而不是物理 DELETE</h2>
     *
     * <p>删除日记需要触发「来源记忆 + 向量」的补偿任务（Phase 3 起），
     * 立即物理删除会让补偿任务失去依据 —— 它需要知道"哪篇日记被删了"。
     *
     * <p>软删除后的日记对所有查询都不可见（所有 SQL 都带 {@code deleted = 0}），
     * 效果等同于删除。等账户清除（Phase 7）时再统一物理清理。
     *
     * <h2>不清理 diary_tag 关联</h2>
     *
     * <p>软删除的日记已经查不出来，关联行留着不影响正确性。
     * 反而有个额外好处：标签的"被使用计数"会随着日记软删除而下降
     * （{@code countUsedByDiaries} 带 {@code d.deleted = 0}），
     * 于是那些标签就变成可删除的了。
     *
     * <h2>Phase 3：会删掉分析结果，但<b>刻意不删任务记录</b></h2>
     *
     * <p>两者的处理不同，理由是它们性质不一样：
     * <ul>
     *   <li><b>分析结果（{@code diary_analysis}）删掉</b>：它是从正文<b>派生</b>出的内容
     *       （摘要、情绪、实体），用户删日记的意图里包含"别再留着我的东西"。
     *       留着就等于"正文删了、但正文的改写版还在"</li>
     *   <li><b>任务记录（{@code ai_task}）保留</b>：它是<b>运维记录</b>（跑过没有、花了多久、
     *       失败原因），不含任何用户内容。而且 Worker 捞到它时会发现日记已不存在，
     *       自己转成 {@code CANCELLED}（见 {@code AiTaskWorker.TaskAbortedException}）——
     *       也不会浪费一次模型调用，因为它在调模型<b>之前</b>就先查日记</li>
     * </ul>
     *
     * @param diaryId 日记 ID
     * @param userId  当前用户 ID
     * @throws com.sangshen.aidiary.exception.BusinessException
     *         不存在、不属于当前用户、或已经是删除状态 → {@code 40401}
     */
    void delete(Long diaryId, Long userId);
}
