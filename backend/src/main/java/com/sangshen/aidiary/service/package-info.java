/**
 * 业务层。规则见开发文档 §3.1 与 §4.7。
 *
 * <h2>Service 的职责</h2>
 * <ul>
 *   <li>业务规则与权限判断</li>
 *   <li>事务边界（{@code @Transactional}）</li>
 *   <li>领域事件发布（如 {@code DiaryCreatedEvent}）</li>
 *   <li>Entity ↔ DTO 的显式映射</li>
 *   <li>分页参数的上限校验（{@code size <= 100}，见下）</li>
 * </ul>
 *
 * <p><b>不负责</b>：HTTP 状态码细节（那是 Controller 和
 * {@code GlobalExceptionHandler} 的事）。
 *
 * <h2>三个必须遵守的点</h2>
 *
 * <h3>1. 分页 size 强制上限</h3>
 * <p>有人传 {@code size=100000} 就能拖垮数据库。Service 层必须兜住：
 * <pre>{@code
 * private static final int MAX_PAGE_SIZE = 100;
 *
 * if (query.getSize() == null || query.getSize() <= 0) {
 *     query.setSize(20);
 * } else if (query.getSize() > MAX_PAGE_SIZE) {
 *     throw new BusinessException(ErrorCode.BAD_REQUEST,
 *             "每页最多 " + MAX_PAGE_SIZE + " 条");
 * }
 * }</pre>
 *
 * <h3>2. 权限一律从安全上下文取，不接受前端传入</h3>
 * <pre>{@code
 * // ❌ 前端传 userId 作为权限依据
 * public List<Diary> list(Long userId) { ... }
 *
 * // ✅ Controller 从上下文取好再传进来
 * Long currentUserId = SecurityUtils.getCurrentUserId();
 * }</pre>
 *
 * <h3>3. 模型调用必须在事务外</h3>
 * <p>AI 调用一次可能 30 秒，塞进事务会长时间持有数据库连接，
 * 且 Worker 可能读到未提交数据。正确做法见开发文档 §6.7：
 * 日记保存与任务创建在同一事务内，事件用
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 在提交后投递，
 * 模型调用在事务外异步执行。
 *
 * <h2>子包</h2>
 * <ul>
 *   <li>{@code impl} —— Service 实现</li>
 *   <li>{@code ai} —— AI 相关服务接口（接口由前后端负责人定义，AI 模块负责人实现）</li>
 *   <li>{@code memory} —— 三层记忆服务（同上）</li>
 * </ul>
 *
 * @see com.sangshen.aidiary.exception.BusinessException
 */
package com.sangshen.aidiary.service;
