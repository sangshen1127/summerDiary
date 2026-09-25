package com.sangshen.aidiary.service.impl;

import com.sangshen.aidiary.common.ErrorCode;
import com.sangshen.aidiary.common.crypto.AesGcmUtil;
import com.sangshen.aidiary.dto.query.DiaryQuery;
import com.sangshen.aidiary.dto.request.DiaryCreateRequest;
import com.sangshen.aidiary.dto.request.DiaryUpdateRequest;
import com.sangshen.aidiary.dto.response.AiTaskResponse;
import com.sangshen.aidiary.dto.response.DiaryResponse;
import com.sangshen.aidiary.dto.response.PageResponse;
import com.sangshen.aidiary.dto.response.TagResponse;
import com.sangshen.aidiary.entity.AiTask;
import com.sangshen.aidiary.entity.Diary;
import com.sangshen.aidiary.entity.enums.AiTaskTypes.SourceType;
import com.sangshen.aidiary.entity.enums.AiTaskTypes.TaskType;
import com.sangshen.aidiary.event.DiaryCreatedEvent;
import com.sangshen.aidiary.exception.BusinessException;
import com.sangshen.aidiary.mapper.AiTaskMapper;
import com.sangshen.aidiary.mapper.DiaryAnalysisMapper;
import com.sangshen.aidiary.mapper.DiaryMapper;
import com.sangshen.aidiary.mapper.DiaryTagMapper;
import com.sangshen.aidiary.service.DiaryService;
import com.sangshen.aidiary.service.TagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 日记服务实现。
 *
 * <h2>本类的核心职责：加解密的唯一发生地</h2>
 *
 * <p>开发文档 §5.3：只有 {@code DiaryService} 的读写路径接触加解密。
 * 本类因此是<b>唯一</b>注入并使用 {@link AesGcmUtil} 的业务类。
 *
 * <p>每个方法的加解密位置：
 * <pre>
 * create  : content(明文) --encrypt--> contentCiphertext --&gt; Mapper
 * get     : Mapper --&gt; contentCiphertext --decrypt--> content(明文) --&gt; DTO
 * list    : Mapper --&gt; contentCiphertext --【不解密】--> DTO 的 content = null
 * update  : content(明文) --encrypt--> contentCiphertext --&gt; Mapper
 * delete  : 不涉及正文
 * </pre>
 *
 * <h2>⚠️ list 为什么不解密</h2>
 *
 * <p>不是"忘了"，而是刻意的安全与性能取舍：
 * 列表每页最多 100 条，如果全部解密并塞进响应体，
 * 明文正文会同时出现在网络传输、浏览器内存、以及任何中间日志里。
 * 列表页本来也不需要正文（只显示标题和摘要），所以干脆不传。
 *
 * <h2>三条纪律</h2>
 * <ol>
 *   <li>不返回 HTTP 状态细节 —— 只抛 {@link BusinessException}</li>
 *   <li>所有读写都带 userId（由 Mapper 的 SQL 保证隔离）</li>
 *   <li>日志绝不出现正文（明文与密文都不行）、密钥 —— 开发文档 §5.4 红线</li>
 * </ol>
 *
 * <h2>Phase 3 加进来的两件事，以及它们的边界</h2>
 *
 * <ol>
 *   <li><b>发布 {@code DiaryCreatedEvent}</b>（只在 {@link #create}）。
 *       本类<b>不</b>注入 {@code CognitionService}、<b>不</b>碰 {@code ai_task}
 *       的写入 —— 那是 {@code DiaryCreatedEventListener} 与 {@code AiTaskWorker} 的事。
 *       这里只负责"日记存好了，通知一声"。</li>
 *   <li><b>读分析状态</b>（{@link #get} 与 {@link #buildResponse}）。
 *       只读 {@code ai_task}，<b>不读</b> {@code diary_analysis} ——
 *       结果渲染属于 {@code GET /api/diaries/{id}/analysis}。</li>
 * </ol>
 *
 * <p>⚠️ 依赖方向是<b>单向</b>的：DiaryService → ai_task（只读 + 发事件）。
 * 反向依赖（AI 侧直接改 diary）是被禁止的 —— 那会绕开本类的加解密纪律，
 * 也会让"谁改了什么"变得说不清。AI 侧要读正文，应该走
 * {@code CognitionService.analyze(userId, diaryId, content)} 这种
 * "由本类解密后传入"的形式。
 */
@Service
public class DiaryServiceImpl implements DiaryService {

    private static final Logger log = LoggerFactory.getLogger(DiaryServiceImpl.class);

    /**
     * 每页最大条数。开发文档 §4.3 的契约。
     *
     * <p>为什么不靠前端约束：攻击者可以直接构造 {@code ?size=1000000}，
     * 一次把整表读进内存。这个上限必须由服务端强制。
     */
    private static final int MAX_PAGE_SIZE = 100;

    private final DiaryMapper diaryMapper;
    private final DiaryTagMapper diaryTagMapper;
    private final TagService tagService;
    private final AesGcmUtil aesGcmUtil;

    /** 只用于"查这篇日记有没有分析任务、跑到哪一步了"。 */
    private final AiTaskMapper aiTaskMapper;

    /** 日记删除时顺手清掉派生数据（分析结果）。 */
    private final DiaryAnalysisMapper diaryAnalysisMapper;

    /**
     * 事件发布器。用来在事务内发 {@code DiaryCreatedEvent}。
     *
     * <p>为什么用 {@code ApplicationEventPublisher} 而不是直接调监听器：
     * 解耦只是次要好处，<b>关键</b>是只有走 Spring 的事件机制，
     * {@code @TransactionalEventListener(AFTER_COMMIT)} 才能生效 ——
     * 直接调方法会变成"提交前就执行"，于是 Worker 可能读到还没提交的日记。
     */
    private final ApplicationEventPublisher eventPublisher;

    public DiaryServiceImpl(DiaryMapper diaryMapper,
                            DiaryTagMapper diaryTagMapper,
                            TagService tagService,
                            AesGcmUtil aesGcmUtil,
                            AiTaskMapper aiTaskMapper,
                            DiaryAnalysisMapper diaryAnalysisMapper,
                            ApplicationEventPublisher eventPublisher) {
        this.diaryMapper = diaryMapper;
        this.diaryTagMapper = diaryTagMapper;
        this.tagService = tagService;
        this.aesGcmUtil = aesGcmUtil;
        this.aiTaskMapper = aiTaskMapper;
        this.diaryAnalysisMapper = diaryAnalysisMapper;
        this.eventPublisher = eventPublisher;
    }

    // ══════════════════════════════════════════════════════════
    // 1. 创建
    // ══════════════════════════════════════════════════════════

    @Override
    @Transactional
    public DiaryResponse create(DiaryCreateRequest request, Long userId) {
        // ── 步骤 1：校验标签归属 ────────────────────────────────
        // 必须【先】校验再插入。理由：SQL 里的 t.user_id 条件会静默过滤掉
        // 别人的标签，导致"选了 3 个只存了 2 个"且没有任何提示。
        // 这里显式校验，数量对不上就 40401。
        Set<Long> tagIds = tagService.requireOwnedTagIds(request.tagIds(), userId);

        // ── 步骤 2：加密正文并插入 ──────────────────────────────
        Diary diary = new Diary();
        diary.setUserId(userId);
        diary.setTitle(request.title().trim());
        diary.setContentCiphertext(aesGcmUtil.encrypt(request.content()));
        diary.setMood(normalizeOptional(request.mood()));
        diary.setWeather(normalizeOptional(request.weather()));
        diary.setLocation(normalizeOptional(request.location()));

        diaryMapper.insert(diary);

        // ── 步骤 3：写标签关联 ──────────────────────────────────
        // 注意 Set 已经去重（requireOwnedTagIds 用 LinkedHashSet 处理过），
        // 所以不会撞 diary_tag 的联合主键。
        if (!tagIds.isEmpty()) {
            diaryTagMapper.insertByDiaryIdAndTagIds(diary.getId(), new ArrayList<>(tagIds), userId);
        }

        // 日志只记 id 和标签数量，绝不记标题与正文（§5.4 红线）
        log.debug("创建日记成功: userId={} diaryId={} tagCount={}",
                userId, diary.getId(), tagIds.size());

        // ── 步骤 4：发布创建事件（AI 分析任务的入队由监听器异步完成）────
        // ⚠️ 位置很关键，三个"必须"：
        //   1. 必须在【事务内】发 —— 否则 @TransactionalEventListener(AFTER_COMMIT)
        //      根本收不到（没有事务可挂靠），事件会被直接丢掉，
        //      症状是"日记能存但永远不分析"，且没有任何报错
        //   2. 必须在【插入之后】发 —— 事件里带 diaryId，而 diaryId 是
        //      insert 之后才由数据库回填的。放在插入前会发出 diaryId=null
        //   3. 必须【只发事件、不做别的】—— 事件对象里刻意只有 userId + diaryId，
        //      没有正文。这样即使监听器写日志、进队列、被序列化，也不会泄密
        eventPublisher.publishEvent(new DiaryCreatedEvent(userId, diary.getId()));

        // 回读一次，让返回的 createdAt / updatedAt 是数据库的真实值
        // （插入时用的是 DEFAULT CURRENT_TIMESTAMP，Java 侧没有这个值）。
        //
        // ⚠️ 此刻 analysis_status 大概率是 null 而不是 "pending"：
        //    入队发生在事务提交之后（而且是异步线程），而这里还在事务里。
        //    这不是 bug —— 前端收到 null 后正常显示"尚未分析"，
        //    下次进详情页就能看到 pending/running。
        //    反过来，如果为了"让返回值好看"而在事务内手动插入任务，
        //    就会破坏"回滚时不留脏任务"这条保证。
        return buildResponse(diary.getId(), userId);
    }

    // ══════════════════════════════════════════════════════════
    // 2. 详情
    // ══════════════════════════════════════════════════════════

    @Override
    public DiaryResponse get(Long diaryId, Long userId) {
        // requireDiary 内部已含 40401 逻辑，且是 SQL 层隔离
        Diary diary = requireDiary(diaryId, userId);
        List<TagResponse> tags = loadTags(List.of(diaryId), userId)
                .getOrDefault(diaryId, List.of());

        // 解密。解密失败（密钥不对/密文被篡改）会抛 DiaryDecryptionException，
        // 由 GlobalExceptionHandler 转成 50001 —— 那是服务端问题，不该是 4xx。
        String content = aesGcmUtil.decrypt(diary.getContentCiphertext());

        return DiaryResponse.from(diary, content, tags, loadAnalysisStatus(diaryId, userId));
    }

    @Override
    public void requireOwned(Long diaryId, Long userId) {
        // 复用同一段逻辑，保证"什么算可见"只有一个定义。
        // 注意这里【刻意不解密】—— 权限校验不需要正文（见接口注释）。
        requireDiary(diaryId, userId);
    }

    // ══════════════════════════════════════════════════════════
    // 3. 列表
    // ══════════════════════════════════════════════════════════

    @Override
    public PageResponse<DiaryResponse> list(DiaryQuery query, Long userId) {
        // ── 强制分页上限（开发文档 §4.3）────────────────────────
        // 放在 Service 而不是 Controller：这是业务规则，
        // 而且 Controller 可能被多个入口调用（当前还有一个未来可能的内部调用方）。
        if (query.getSize() > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "每页最多 " + MAX_PAGE_SIZE + " 条，当前请求 " + query.getSize() + " 条");
        }
        if (query.getSize() < 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "每页至少 1 条");
        }
        if (query.getPage() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "页码不能为负数");
        }

        // 先查总数（与列表查询共用同一段 XML 筛选条件，保证口径一致）
        long total = diaryMapper.countByUserId(query, userId);

        // 总数为 0 时可以直接返回，省掉一次查询。
        // 注意这里返回的 page/size 仍然原样带回，前端分页组件要用。
        if (total == 0) {
            return PageResponse.of(List.of(), query.getPage(), query.getSize(), 0);
        }

        List<Diary> diaries = diaryMapper.selectPageByUserId(query, userId);
        if (diaries.isEmpty()) {
            // 可能发生：例如请求的页码超出了实际范围。
            // 返回空列表 + 真实 total，让前端知道"总共还有多少条"。
            return PageResponse.of(List.of(), query.getPage(), query.getSize(), total);
        }

        // ── 批量取标签（避免 N+1）──────────────────────────────
        List<Long> diaryIds = diaries.stream().map(Diary::getId).toList();
        Map<Long, List<TagResponse>> tagsByDiaryId = loadTags(diaryIds, userId);

        // ── 组装：content 传 null（列表不解密，见类注释）────────
        // analysisStatus 同样传 null：要填它就得对每条再查一次 ai_task（N+1），
        // 而列表页不展示分析状态。详见 DiaryService.list 的注释。
        List<DiaryResponse> items = diaries.stream()
                .map(d -> DiaryResponse.from(d, null,
                        tagsByDiaryId.getOrDefault(d.getId(), List.of()), null))
                .toList();

        return PageResponse.of(items, query.getPage(), query.getSize(), total);
    }

    // ══════════════════════════════════════════════════════════
    // 4. 更新
    // ══════════════════════════════════════════════════════════

    @Override
    @Transactional
    public DiaryResponse update(Long diaryId, DiaryUpdateRequest request, Long userId) {
        // ── 步骤 1：确认日记归属（不存在/不属于我 → 40401）──────
        Diary diary = requireDiary(diaryId, userId);

        // ── 步骤 2：校验标签归属 ────────────────────────────────
        Set<Long> tagIds = tagService.requireOwnedTagIds(request.tagIds(), userId);

        // ── 步骤 3：更新日记本体 ────────────────────────────────
        diary.setTitle(request.title().trim());
        // ⚠️ 重新加密会使用【新的随机 IV】—— 这是正确行为，不是浪费。
        //    GCM 下同一个 (密钥, IV) 组合加密两段不同明文是灾难性的，
        //    所以每次加密都必须换 IV，代价是密文长度不变但内容全变。
        diary.setContentCiphertext(aesGcmUtil.encrypt(request.content()));
        diary.setMood(normalizeOptional(request.mood()));
        diary.setWeather(normalizeOptional(request.weather()));
        diary.setLocation(normalizeOptional(request.location()));

        int affected = diaryMapper.updateByIdAndUserId(diary);
        if (affected == 0) {
            // 走到这里说明：requireDiary 查到了，但 update 没改到。
            // 唯一解释是并发 —— 另一个请求刚把这篇日记删了。
            // 返回 40401 是诚实语义（比假装成功好）。
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        // ── 步骤 4：标签"先清后插" ──────────────────────────────
        // PUT 是整体替换语义，所以无条件清空再重建。
        // 空列表也是合法输入（表示清空所有标签），此时只清不插。
        diaryTagMapper.deleteByDiaryIdAndUserId(diaryId, userId);
        if (!tagIds.isEmpty()) {
            diaryTagMapper.insertByDiaryIdAndTagIds(diaryId, new ArrayList<>(tagIds), userId);
        }

        log.debug("更新日记成功: userId={} diaryId={} tagCount={}",
                userId, diaryId, tagIds.size());

        return buildResponse(diaryId, userId);
    }

    // ══════════════════════════════════════════════════════════
    // 5. 删除（软删除）
    // ══════════════════════════════════════════════════════════

    @Override
    @Transactional
    public void delete(Long diaryId, Long userId) {
        // WHERE 里同时带 id / user_id / deleted = 0，
        // 所以"不存在""不属于我""已经删过"三种情况都返回 0 行 → 统一 40401。
        int affected = diaryMapper.softDeleteByIdAndUserId(diaryId, userId);
        if (affected == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        // ⚠️ 刻意不清理 diary_tag 关联。理由见 DiaryService.delete 的接口注释：
        //    软删除的日记已查不出来，关联留着不影响正确性；
        //    而且标签的"被使用计数"会自动随 deleted = 0 条件下降。
        //
        //    也不物理删除 diary 行 —— Phase 3 的补偿任务需要知道"哪篇被删了"。

        // ── Phase 3：清掉【派生】数据 ────────────────────────────
        // 分析结果（摘要/情绪/实体）是从正文派生出来的内容。
        // 用户删日记的意图包含"别再留着我的东西"，所以派生内容要一起删。
        //
        // ⚠️ 但 ai_task 记录【不删】：它是运维记录（跑过没有、失败原因），
        //    不含用户内容，而且 Worker 捞到它时会自己转 CANCELLED（不会浪费模型调用，
        //    因为查日记在调模型之前）。详见 DiaryService.delete 的接口注释。
        //
        // 顺序：先软删除成功、再删派生数据。反过来不行 ——
        // 如果先删了分析结果但软删除失败（返回 0 行 → 抛 40401），
        // 事务回滚会把分析结果一起还原，看起来没损失；但语义上
        // "确认这篇日记确实被我删掉了"应当是第一位的。
        int analysisDeleted = diaryAnalysisMapper.deleteByDiaryIdAndUserId(diaryId, userId);
        if (analysisDeleted > 0) {
            log.debug("已清理日记的派生分析结果: userId={} diaryId={}", userId, diaryId);
        }

        log.debug("软删除日记成功: userId={} diaryId={}", userId, diaryId);
    }

    // ══════════════════════════════════════════════════════════
    // 私有工具方法
    // ══════════════════════════════════════════════════════════

    /**
     * 按 ID + 归属查日记，查不到就抛 40401。
     *
     * <p>「不存在」与「不属于当前用户」<b>刻意不区分</b>：
     * 区分它们等于告诉攻击者"这个 ID 真实存在，只是不是你的"，
     * 可以被用来枚举系统里有多少日记（开发文档 §4.2 安全约定）。
     */
    private Diary requireDiary(Long diaryId, Long userId) {
        Diary diary = diaryMapper.selectByIdAndUserId(diaryId, userId);
        if (diary == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return diary;
    }

    /**
     * 回读日记并解密，组装成完整响应。仅用于 create / update 的返回。
     *
     * <p>为什么"插入/更新之后要再查一次"而不是直接用内存里的对象：
     * {@code created_at} / {@code updated_at} 是数据库的
     * {@code DEFAULT CURRENT_TIMESTAMP} / {@code ON UPDATE CURRENT_TIMESTAMP}
     * 填的，Java 侧<b>没有</b>这两个值。如果直接用内存对象返回，
     * 前端会收到 {@code created_at: null}，列表排序和"刚刚"的显示都会出错。
     *
     * <p>代价是多一次查询。这个代价是值得的 —— 让时间只有一个来源（数据库时钟）。
     * 另一种做法是插入前用 {@code LocalDateTime.now(ZoneOffset.UTC)} 自己填，
     * 但那会引入"应用服务器与数据库时钟不一致"的隐患，且两处都能写时间，
     * 容易漂移。
     */
    private DiaryResponse buildResponse(Long diaryId, Long userId) {
        Diary fresh = diaryMapper.selectByIdAndUserId(diaryId, userId);
        if (fresh == null) {
            // 刚插入/更新完就查不到，说明有并发删除。
            // 这不是编码错误，抛 40401 让调用方得到一致的语义。
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        List<TagResponse> tags = loadTags(List.of(diaryId), userId)
                .getOrDefault(diaryId, List.of());

        return DiaryResponse.from(fresh,
                aesGcmUtil.decrypt(fresh.getContentCiphertext()),
                tags,
                loadAnalysisStatus(diaryId, userId));
    }

    /**
     * 查某篇日记的分析状态，映射成对外取值；没有任务时返回 null。
     *
     * <h2>⚠️ 为什么"没有任务"不返回 {@code "pending"}</h2>
     *
     * <p>看起来更"友好"——用户看到"排队中"总比"尚未分析"积极。
     * 但那是个谎：{@code analysis_status} 为 null 有三种原因
     * （全局开关关着 / 用户自己关了 AI / 日记建于 Phase 3 之前），
     * 只有第一种情况将来会变成 pending。老日记永远不会被自动分析，
     * 报 "pending" 就是永远转不完的圈。
     *
     * <p>所以这里如实返回 null，由前端结合
     * {@code DiaryAnalysisDetailResponse.enabled} 判断该显示
     * 「AI 未启用」还是「尚未分析（可点击开始分析）」。
     *
     * <h2>为什么只有一个查询、没有 N+1 风险</h2>
     *
     * <p>本方法每次只服务<b>一篇</b>日记（详情 / 创建 / 更新的返回）。
     * 列表路径刻意不调用它 —— 见 {@link #list} 里的注释。
     */
    private String loadAnalysisStatus(Long diaryId, Long userId) {
        AiTask task = aiTaskMapper.selectBySource(
                userId, SourceType.DIARY, diaryId, TaskType.DIARY_ANALYZE);
        if (task == null) {
            return null;
        }
        // 映射规则与 AiTaskResponse 共用同一个方法，避免两处漂移
        return AiTaskResponse.toExternalStatus(task.getStatus());
    }

    /** 批量取一批日记的标签。空列表直接返回空 Map（避免非法的 {@code IN ()}）。 */
    private Map<Long, List<TagResponse>> loadTags(List<Long> diaryIds, Long userId) {
        return tagService.mapTagsByDiaryIds(diaryIds, userId);
    }

    /**
     * 归一化可选文本字段。
     *
     * <p>把「空白字符串」统一成 {@code null}，理由：
     * <ul>
     *   <li>前端表单里用户清空一个输入框，提交上来往往是 {@code ""} 而不是 {@code null}。
     *       如果原样落库，数据库里会同时存在 {@code ""} 和 {@code null} 两种"空"，
     *       而 {@code WHERE mood = ?} 查 {@code null} 是查不到 {@code ""} 的 ——
     *       症状是"筛选'未填写心情'时漏数据"，很难查。</li>
     *   <li>统一成 {@code null} 后，"有没有填"只有一个表示法。</li>
     * </ul>
     *
     * @param value 原始值，可为 null
     * @return 去掉首尾空白后的值；空白字符串返回 null
     */
    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
