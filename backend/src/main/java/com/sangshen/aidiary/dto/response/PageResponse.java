package com.sangshen.aidiary.dto.response;

import java.util.List;

/**
 * 统一分页响应体。
 *
 * <p>契约见开发文档 §4.3：
 * <pre>{@code
 * {
 *   "items": [],
 *   "page": 0,
 *   "size": 20,
 *   "total": 0,
 *   "hasNext": false
 * }
 * }</pre>
 *
 * <h2>为什么把 hasNext 放进响应，而不是让前端自己算</h2>
 *
 * <p>前端自己算是 {@code page * size + items.length < total} —— 这个公式<b>很容易写错</b>
 * （边界是 {@code <} 还是 {@code <=}、空列表时怎么算），而且一旦写错，
 * 症状是"最后一页点下一页没反应"或"翻过了最后一页"，都不报错。
 *
 * <p>由后端算只有一个实现处，前端直接用。代价是响应体多一个布尔值。
 *
 * <h2>为什么字段名是 items 而不是 list</h2>
 *
 * <p>{@code list} 太通用，而且在 Java 里和集合类型名撞。{@code items} 更明确，
 * 也便于将来给其他资源复用同一套分页结构（标签、记忆、会话都用它）。
 *
 * @param items   当前页数据。**永远不是 null**，无数据时是空列表 ——
 *                理由见下方 {@link #of}
 * @param page    当前页码，从 <b>0</b> 开始
 * @param size    每页条数（已由 Service 校验不超过 100）
 * @param total   符合条件的总条数
 * @param hasNext 是否还有下一页
 * @param <T>     元素类型
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long total,
        boolean hasNext
) {

    /**
     * 构造分页响应，并自动计算 {@code hasNext}。
     *
     * <h2>⚠️ items 为 null 时会被换成空列表</h2>
     *
     * <p>这不是"过度防御"，而是为了让接口契约里
     * <b>{@code items} 永远是数组</b>这条成立。
     *
     * <p>如果允许 null，前端就要写 {@code data.items?.length ?? 0} 这种防御代码；
     * 漏写一处就是白屏（{@code Cannot read properties of null}）。
     * 而且空数组和 null 在 JSON 里长得完全不同，前端类型也得写成
     * {@code Tag[] | null}，把复杂度推给了每一个使用方。
     *
     * <p>后端统一保证"要么有内容，要么是空数组"，前端就能直接用。
     *
     * @param items 当前页数据，可为 null（会被换成空列表）
     * @param page  页码，负值会被夹到 0
     * @param size  每页条数
     * @param total 总条数
     */
    public static <T> PageResponse<T> of(List<T> items, int page, int size, long total) {
        List<T> safeItems = items == null ? List.of() : items;
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, size);

        // hasNext 的判断：已经"取到的累计条数"是否还没到总数。
        // 用 (page + 1) * size < total 而不是 items.size() == size ——
        // 后者在"总数正好是 size 的整数倍"时会多给一页空的。
        boolean hasNext = (long) (safePage + 1) * safeSize < total;

        return new PageResponse<>(safeItems, safePage, safeSize, total, hasNext);
    }
}
