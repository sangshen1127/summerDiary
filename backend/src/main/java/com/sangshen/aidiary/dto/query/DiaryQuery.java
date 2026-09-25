package com.sangshen.aidiary.dto.query;

import java.time.LocalDateTime;

/**
 * 日记列表的筛选条件 + 分页参数。
 *
 * <p>对应 {@code GET /api/diaries} 的查询参数（开发文档 §4.6）：
 * <pre>
 * page, size, keyword, from, to, mood, tag_id
 * </pre>
 *
 * <h2>⚠️ 这个类里没有 userId，而且永远不会有</h2>
 *
 * <p>owner 条件由 Controller 从安全上下文取（{@code SecurityUtils.getCurrentUserId()}），
 * 再作为<b>独立参数</b>传给 Service 和 Mapper（开发文档 §4.7 权限铁律）。
 *
 * <p>禁止把 userId 放进本类、或让它能被请求参数绑定 —— 否则前端传一个
 * {@code ?userId=2} 就能读别人的日记。这是最经典也最致命的越权漏洞。
 *
 * <h2>字段语义</h2>
 *
 * <table border="1">
 *   <caption>筛选字段</caption>
 *   <tr><th>字段</th><th>含义</th><th>为 null 时</th></tr>
 *   <tr><td>{@code keyword}</td><td>按<b>标题</b>模糊匹配</td><td>不筛选</td></tr>
 *   <tr><td>{@code from} / {@code to}</td><td>按日记时间范围（闭区间）</td><td>该侧不限</td></tr>
 *   <tr><td>{@code mood}</td><td>按心情精确匹配</td><td>不筛选</td></tr>
 *   <tr><td>{@code tagId}</td><td>按标签筛选（含该标签的日记）</td><td>不筛选</td></tr>
 * </table>
 *
 * <h2>⚠️ keyword 只搜标题 —— 这是密码学决定的，不是偷懒</h2>
 *
 * <p>正文存的是 AES-GCM 密文，而且<b>每次加密用新的随机 IV</b>，
 * 所以同一段文字每次密文都不同。这意味着：
 * <ul>
 *   <li>SQL 里<b>不可能</b>对正文做 {@code LIKE} —— 密文里不含明文片段</li>
 *   <li>即使同一段明文加密两次，密文也完全不同，无法比对</li>
 * </ul>
 *
 * <p>如果哪天需要「搜正文」，正确做法不是去掉加密，而是<b>另建一列可检索的
 * 索引列</b>（例如摘要或分词结果），并接受那一列的隐私代价。
 * 这件事已明确推迟到 Phase 5（向量检索），Phase 2 只搜标题。
 *
 * <h2>为什么是可变的类而不是 record</h2>
 *
 * <p>它要被 Spring MVC 从查询参数绑定（需要无参构造 + setter），
 * 而且 Service 层会修正非法值（例如把 size 从 500 夹到 100），
 * 所以不能是不可变的 record。
 */
public class DiaryQuery {

    /** 页码，从 <b>0</b> 开始（开发文档 §4.3）。默认 0 */
    private int page = 0;

    /** 每页条数，默认 20。Service 层强制 {@code <= 100}，超出返回 40001 */
    private int size = 20;

    /** 标题关键词，模糊匹配。空串/null 视为不筛选 */
    private String keyword;

    /**
     * 时间范围起点（闭区间），按日记的 {@code created_at} 比较。
     *
     * <p>统一用 UTC 语义的 {@code LocalDateTime}（开发文档 §4.4）：
     * 数据库存的就是 UTC，比较时不能再做时区转换，否则会整体偏移几小时。
     */
    private LocalDateTime from;

    /** 时间范围终点（闭区间） */
    private LocalDateTime to;

    /** 心情精确匹配 */
    private String mood;

    /** 标签 ID。筛选出「带这个标签」的日记 */
    private Long tagId;

    // ── getter / setter ────────────────────────────────────────

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public LocalDateTime getFrom() {
        return from;
    }

    public void setFrom(LocalDateTime from) {
        this.from = from;
    }

    public LocalDateTime getTo() {
        return to;
    }

    public void setTo(LocalDateTime to) {
        this.to = to;
    }

    public String getMood() {
        return mood;
    }

    public void setMood(String mood) {
        this.mood = mood;
    }

    public Long getTagId() {
        return tagId;
    }

    public void setTagId(Long tagId) {
        this.tagId = tagId;
    }

    /**
     * 计算 SQL 的 OFFSET。
     *
     * <p>放在 DTO 里而不是 XML 里算 {@code (page-1)*size}：MySQL 的
     * {@code LIMIT} 不支持表达式里的算术（部分版本可以，但语义容易混淆），
     * 而且「page 从 0 开始」这个约定<b>只应该有一个实现处</b>。
     *
     * @return 偏移量，最小 0（防负数）
     */
    public int getOffset() {
        return Math.max(0, page) * Math.max(0, size);
    }

    /**
     * 调试输出。筛选条件里可能含用户输入的标题关键词，
     * 但关键词本身不是敏感数据（正文才是），所以可以打印。
     */
    @Override
    public String toString() {
        return "DiaryQuery{page=" + page
                + ", size=" + size
                + ", keyword='" + keyword + '\''
                + ", from=" + from
                + ", to=" + to
                + ", mood='" + mood + '\''
                + ", tagId=" + tagId
                + '}';
    }
}
