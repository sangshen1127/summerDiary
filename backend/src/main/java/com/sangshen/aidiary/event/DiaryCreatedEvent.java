package com.sangshen.aidiary.event;

/**
 * 日记创建成功事件。
 *
 * <h2>⚠️ 这个类存在的唯一理由：把「保存日记」和「调用模型」解耦</h2>
 *
 * <p>验收标准是「模型超时/报错时<b>正文照样保存成功</b>」。
 * 要做到这一点，保存日记的事务里<b>只能</b>写 {@code diary} 与
 * {@code diary_tag}，然后发个事件就立刻返回。
 *
 * <p>反例（绝对不要这样写）：
 * <pre>{@code
 * // ❌ 在保存日记的事务里同步调用模型
 * @Transactional
 * public DiaryResponse create(...) {
 *     diaryMapper.insert(diary);
 *     DiaryAnalysis a = cognitionService.analyze(userId, diary.getId());  // 最长 30 秒！
 *     analysisMapper.upsert(a);
 *     return ...;
 * }
 * }</pre>
 * 三个后果，每一个都够喝一壶：
 * <ol>
 *   <li><b>事务被拉长到 30 秒</b>，期间持有行锁，并发写入全被拖死</li>
 *   <li><b>模型挂了日记就存不进去</b>，直接违反验收标准</li>
 *   <li>Worker 若在别处读这篇日记，会读到<b>未提交</b>的数据</li>
 * </ol>
 *
 * <h2>为什么事件里只放 ID，不放正文</h2>
 *
 * <p>事件对象可能被日志打印、被序列化、被传递到别的线程。
 * 正文是<b>明文隐私数据</b>（开发文档 §5.4 红线），
 * 放进事件等于把它扩散到更多地方。
 *
 * <p>Listener 拿到 ID 后自己去查库并解密 —— 数据只在那一个受控位置出现。
 * 代价是多一次查询，收益是"正文不扩散"。
 *
 * @param userId  日记所属用户。冗余（从 diaryId 也能查到），
 *                但刻意保留：Listener 需要它来构造任务的归属条件，
 *                而记错归属是致命的 —— 显式传比让下游再查一次更安全
 * @param diaryId 日记 ID
 */
public record DiaryCreatedEvent(Long userId, Long diaryId) {

    /**
     * 紧凑构造器：拒绝非法参数。
     *
     * <p>事件是"事后通知"，一旦发出去就无法收回。
     * 如果带着 null 的 diaryId 发出去，问题会推迟到 Listener 里才暴露，
     * 那时已经离出错点很远了。所以在这里就拦住。
     */
    public DiaryCreatedEvent {
        if (userId == null || diaryId == null) {
            throw new IllegalArgumentException(
                    "DiaryCreatedEvent 的 userId / diaryId 都不能为 null");
        }
    }

    /**
     * 安全的事件描述 —— <b>刻意不包含任何正文</b>。
     *
     * <p>本记录的字段只有两个 ID，所以默认的 {@code toString}
     * 其实也是安全的。但仍然显式提供这个方法，
     * 是为了表明意图：**将来若有人给事件加字段，必须重新审视这里**。
     */
    public String toSafeString() {
        return "DiaryCreatedEvent{userId=" + userId + ", diaryId=" + diaryId + '}';
    }
}
