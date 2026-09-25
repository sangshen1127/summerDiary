/**
 * MyBatis Mapper 接口层。
 *
 * <p>这个包由 {@code AiDiaryApplication} 上的
 * {@code @MapperScan("com.sangshen.aidiary.mapper")} 扫描，
 * 因此<b>这个包必须真实存在</b>：
 * <ul>
 *   <li>Maven 编译期不会检查 {@code @MapperScan} 的字符串，所以包不存在也能编译通过</li>
 *   <li>但 IDE 会做静态解析，包不存在就标红「无法解析软件包 mapper」</li>
 *   <li>MyBatis 启动时会打 WARN：{@code No MyBatis mapper was found in ...}</li>
 * </ul>
 *
 * <h2>写 Mapper 的规范（开发文档 §3.1 / §6.3）</h2>
 *
 * <p><b>接口只暴露业务需要的查询</b>，复杂 SQL 和动态条件写在
 * {@code resources/mapper/*.xml} 里。
 *
 * <p><b>三条禁令</b>：
 * <ol>
 *   <li>禁止 {@code SELECT *} —— 显式列名，避免误读密文和大字段</li>
 *   <li>禁止字符串拼接用户输入 —— 只用 {@code #{}} 占位符</li>
 *   <li><b>禁止省略 owner 条件</b> —— 所有涉及用户数据的查询、更新、删除
 *       都必须显式带 {@code user_id}</li>
 * </ol>
 *
 * <p><b>❌ 绝对禁止的写法</b>（跨用户数据泄露的根源）：
 * <pre>{@code
 * // 先按 id 查全表，再在 Java 内存里判断归属
 * Diary diary = diaryMapper.selectById(id);
 * if (diary.getUserId().equals(currentUserId)) { ... }   // 危险！
 * }</pre>
 *
 * <p><b>✅ 正确写法</b>：
 * <pre>{@code
 * // Controller 从安全上下文取 userId，不接受前端传入
 * Long currentUserId = SecurityUtils.getCurrentUserId();
 *
 * // Mapper 接口把 userId 作为参数
 * Diary selectByIdAndUserId(@Param("id") Long id,
 *                           @Param("userId") Long userId);
 * }</pre>
 *
 * <pre>{@code
 * <!-- XML：条件写在 SQL 里，数据库层就完成隔离 -->
 * <select id="selectByIdAndUserId" resultType="Diary">
 *     SELECT id, user_id, title, content_ciphertext, mood, weather, location,
 *            created_at, updated_at
 *     FROM diary
 *     WHERE id = #{id} AND user_id = #{userId} AND deleted = 0
 * </select>
 * }</pre>
 *
 * <p>方法命名约定：{@code selectByXxx} / {@code insert} / {@code updateByIdAndUserId}
 * / {@code deleteByIdAndUserId}。
 *
 * @see com.sangshen.aidiary.AiDiaryApplication
 */
package com.sangshen.aidiary.mapper;
