import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ============================================================
 * 模块 2-2（Diary/Tag 数据层）验收测试
 * ============================================================
 *
 * <h2>这个测试在验什么</h2>
 *
 * <p>Mapper XML 有三类错误【编译器查不出来】，只有真连数据库跑才会暴露：
 * <ol>
 *   <li>{@code <foreach>} 写错 —— 生成的 SQL 语法错误（如 {@code IN ()}）</li>
 *   <li>{@code #{}} 属性名写错 —— MyBatis 运行期报
 *       "There is no getter for property named 'xxx'"</li>
 *   <li>{@code INSERT ... SELECT ... JOIN} 的权限条件写错 ——
 *       不报错，但会【静默多插/少插数据】，也就是越权</li>
 * </ol>
 *
 * <p>本测试直接用 JDBC 执行与 XML 中等价的 SQL，验证第 3 类问题 ——
 * 也就是「A 能不能碰到 B 的数据」。这类问题一旦漏掉就是安全事故，
 * 而且不会以任何报错的形式出现，所以必须用断言主动验证。
 *
 * <h2>为什么用 JDBC 而不是启动 Spring 跑 Mapper</h2>
 *
 * <p>当前没有 Service 层（模块 2-3 才写），而 Spring 容器启动需要
 * 数据源、Flyway 等一系列依赖。JDBC 直连能把「SQL 与表结构是否正确」
 * 单独验证掉，等 2-3 写完再由 HTTP 层的越权测试覆盖完整链路。
 *
 * <h2>不留脏数据</h2>
 *
 * <p>全程在<b>单个事务</b>里跑，最后 {@code rollback()}。
 * 所以不会污染你现有的测试账号和日记。
 *
 * <h2>运行方式</h2>
 *
 * <pre>
 * cd D:\summerDiary\_verify
 * $env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
 * $cp = "D:\summerDiary\.m2repo\com\mysql\mysql-connector-j\8.3.0\mysql-connector-j-8.3.0.jar"
 * &amp; "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 -cp $cp TestDiaryDataLayer.java
 * &amp; "$env:JAVA_HOME\bin\java.exe" '-Dfile.encoding=UTF-8' -cp ".;$cp" TestDiaryDataLayer
 * </pre>
 *
 * <p>需要 MySQL 容器（3307）跑着。凭据自动从项目根 {@code .env} 读取。
 */
public class TestDiaryDataLayer {

    private static int passed = 0;
    private static int failed = 0;

    // ── 跨用户测试用的两个账号 ─────────────────────────────────
    // 用极大 ID 避免和真实数据冲突（表是自增的，短时间到不了这个量级）
    private static final long USER_A = 900000001L;
    private static final long USER_B = 900000002L;

    public static void main(String[] args) throws Exception {
        Map<String, String> env = loadDotenv();
        String url = "jdbc:mysql://" + env.getOrDefault("DB_HOST", "localhost")
                + ":" + env.getOrDefault("DB_PORT", "3307")
                + "/" + env.getOrDefault("DB_NAME", "ai_diary")
                + "?useUnicode=true&characterEncoding=UTF-8"
                + "&connectionCollation=utf8mb4_0900_ai_ci"
                + "&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";

        System.out.println("连接数据库: " + url);
        System.out.println("（全程单事务，结束 rollback，不留脏数据）");
        System.out.println();

        try (Connection conn = DriverManager.getConnection(
                url, env.getOrDefault("DB_USERNAME", "ai_diary"),
                env.getOrDefault("DB_PASSWORD", ""))) {

            conn.setAutoCommit(false);
            try {
                runAll(conn);
            } finally {
                conn.rollback();
                System.out.println();
                System.out.println("已 rollback，未留下任何测试数据。");
            }
        }

        System.out.println();
        System.out.println("通过: " + passed + "   失败: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void runAll(Connection conn) throws SQLException {
        section("一、表结构确认");
        testSchema(conn);

        section("二、日记基础读写（含密文列）");
        long diaryA = testInsertAndRead(conn);

        section("三、跨用户隔离：SELECT 必须查不到");
        testCrossUserSelect(conn, diaryA);

        section("四、软删除语义");
        testSoftDelete(conn, diaryA);

        section("五、标签与同名复用");
        long tagA = testTagInsertAndReuse(conn);

        section("六、★重点★ diary_tag 越权防护（无 user_id 列的表）");
        testDiaryTagOwnership(conn, tagA);

        section("七、标签删除保护（被使用时不能删）");
        testTagDeleteProtection(conn, tagA);

        section("八、分页与筛选 SQL");
        testPagingAndFilter(conn);
    }

    // ══════════════════════════════════════════════════════════
    // 一、表结构
    // ══════════════════════════════════════════════════════════
    private static void testSchema(Connection conn) throws SQLException {
        // diary 必须有 content_ciphertext，而且必须是能装下 Base64 密文的类型
        String contentType = columnType(conn, "diary", "content_ciphertext");
        check("1.1 diary.content_ciphertext 存在且类型为 text",
                "text".equalsIgnoreCase(contentType), "实际类型=" + contentType);

        // diary_tag 必须【没有】user_id 列 —— 这是本模块越权防护的根源
        boolean hasUserId = columnExists(conn, "diary_tag", "user_id");
        check("1.2 diary_tag 确认没有 user_id 列（所以必须 JOIN diary 过滤）",
                !hasUserId, "如果这列存在，说明表结构被改过，越权防护策略需要重新评估");

        // diary_tag 联合主键（防重复关联）
        int pkCols = countPrimaryKeyColumns(conn, "diary_tag");
        check("1.3 diary_tag 是联合主键（2 列）", pkCols == 2, "实际主键列数=" + pkCols);

        // tag 的 (user_id, name) 唯一键 —— 同名标签按用户隔离
        boolean ukExists = indexExists(conn, "tag", "uk_tag_user_name");
        check("1.4 tag 存在唯一键 uk_tag_user_name(user_id, name)", ukExists,
                "没有它就无法保证「同一用户内标签名唯一」");
    }

    // ══════════════════════════════════════════════════════════
    // 二、日记读写
    // ══════════════════════════════════════════════════════════
    private static long testInsertAndRead(Connection conn) throws SQLException {
        String ciphertext = fakeCiphertext("这是一篇测试日记的正文，用来验证密文列能正确存取。");

        long id;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO diary (user_id, title, content_ciphertext, mood, weather, location) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, USER_A);
            ps.setString(2, "数据层测试日记");
            ps.setString(3, ciphertext);
            ps.setString(4, "平静");
            ps.setString(5, "阴");
            ps.setString(6, "图书馆");
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                id = keys.getLong(1);
            }
        }

        check("2.1 INSERT 成功并回填自增主键", id > 0, "id=" + id);

        // 读回并逐字段比对
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, user_id, title, content_ciphertext, mood, weather, location, deleted "
                        + "FROM diary WHERE id = ? AND user_id = ? AND deleted = 0")) {
            ps.setLong(1, id);
            ps.setLong(2, USER_A);
            try (ResultSet rs = ps.executeQuery()) {
                boolean found = rs.next();
                check("2.2 按 id + userId 能查到", found);
                if (found) {
                    check("2.3 密文原样读回（长度一致，未被截断）",
                            ciphertext.equals(rs.getString("content_ciphertext")),
                            "写入长度=" + ciphertext.length()
                                    + " 读回长度=" + len(rs.getString("content_ciphertext")));
                    check("2.4 中文标题/心情/地点无乱码",
                            "数据层测试日记".equals(rs.getString("title"))
                                    && "平静".equals(rs.getString("mood"))
                                    && "图书馆".equals(rs.getString("location")));
                    check("2.5 deleted 默认为 0", rs.getInt("deleted") == 0);
                }
            }
        }

        // 长正文（超过 1000 字符，验证 TEXT 列不会截断）
        String longCipher = fakeCiphertext("长正文测试。".repeat(400));
        long longId;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO diary (user_id, title, content_ciphertext) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, USER_A);
            ps.setString(2, "长正文");
            ps.setString(3, longCipher);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                longId = keys.getLong(1);
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT CHAR_LENGTH(content_ciphertext) FROM diary WHERE id = ?")) {
            ps.setLong(1, longId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                check("2.6 长密文完整存取（>1500 字符，验证 TEXT 不截断）",
                        rs.getInt(1) == longCipher.length(),
                        "写入=" + longCipher.length() + " 读回=" + rs.getInt(1));
            }
        }

        return id;
    }

    // ══════════════════════════════════════════════════════════
    // 三、跨用户隔离
    // ══════════════════════════════════════════════════════════
    private static void testCrossUserSelect(Connection conn, long diaryA) throws SQLException {
        // ★核心断言：B 用 A 的日记 ID 查，必须查不到（而不是查到了再判断归属）
        int rows = queryCount(conn,
                "SELECT COUNT(1) FROM diary WHERE id = ? AND user_id = ? AND deleted = 0",
                diaryA, USER_B);
        check("3.1 ★ B 用 A 的日记 ID 查询 → 0 行（SQL 层就隔离）", rows == 0,
                "实际=" + rows + " 行（应为 0；非 0 说明 WHERE 少了 user_id）");

        // A 自己查得到（对照组，证明上面的 0 行不是"表里没数据"造成的假象）
        int rowsA = queryCount(conn,
                "SELECT COUNT(1) FROM diary WHERE id = ? AND user_id = ? AND deleted = 0",
                diaryA, USER_A);
        check("3.2 对照组：A 自己查同一篇 → 1 行（证明 3.1 的 0 行是真隔离）",
                rowsA == 1, "实际=" + rowsA + " 行");

        // B 尝试改 A 的日记 → 影响行数必须为 0
        int updated;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE diary SET title = ? WHERE id = ? AND user_id = ? AND deleted = 0")) {
            ps.setString(1, "被 B 篡改的标题");
            ps.setLong(2, diaryA);
            ps.setLong(3, USER_B);
            updated = ps.executeUpdate();
        }
        check("3.3 ★ B 尝试更新 A 的日记 → 影响 0 行", updated == 0, "实际=" + updated + " 行");

        // 确认 A 的标题真的没被改
        try (PreparedStatement ps = conn.prepareStatement("SELECT title FROM diary WHERE id = ?")) {
            ps.setLong(1, diaryA);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                check("3.4 复核：A 的标题未被篡改",
                        "数据层测试日记".equals(rs.getString(1)), "实际=" + rs.getString(1));
            }
        }

        // B 尝试删 A 的日记 → 影响行数必须为 0
        int deleted;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE diary SET deleted = 1 WHERE id = ? AND user_id = ? AND deleted = 0")) {
            ps.setLong(1, diaryA);
            ps.setLong(2, USER_B);
            deleted = ps.executeUpdate();
        }
        check("3.5 ★ B 尝试删除 A 的日记 → 影响 0 行", deleted == 0, "实际=" + deleted + " 行");
    }

    // ══════════════════════════════════════════════════════════
    // 四、软删除
    // ══════════════════════════════════════════════════════════
    private static void testSoftDelete(Connection conn, long diaryA) throws SQLException {
        // 单独造一篇用于删除，避免影响后面的测试
        long id = insertDiary(conn, USER_A, "待删除的日记", fakeCiphertext("正文"));

        int affected;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE diary SET deleted = 1 WHERE id = ? AND user_id = ? AND deleted = 0")) {
            ps.setLong(1, id);
            ps.setLong(2, USER_A);
            affected = ps.executeUpdate();
        }
        check("4.1 A 删除自己的日记 → 影响 1 行", affected == 1, "实际=" + affected);

        int visible = queryCount(conn,
                "SELECT COUNT(1) FROM diary WHERE id = ? AND user_id = ? AND deleted = 0", id, USER_A);
        check("4.2 删除后查不到（deleted = 0 条件生效）", visible == 0, "实际=" + visible + " 行");

        int stillThere = queryCount(conn, "SELECT COUNT(1) FROM diary WHERE id = ?", id);
        check("4.3 行还在（软删除，不是物理 DELETE）", stillThere == 1,
                "实际=" + stillThere + " 行（应为 1；若为 0 说明误用了物理删除，"
                        + "会让 Phase 3 的补偿任务失去依据）");

        // 重复删除必须影响 0 行（幂等：第二次删除表现为"已经不存在"）
        int again;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE diary SET deleted = 1 WHERE id = ? AND user_id = ? AND deleted = 0")) {
            ps.setLong(1, id);
            ps.setLong(2, USER_A);
            again = ps.executeUpdate();
        }
        check("4.4 重复删除 → 影响 0 行（幂等，Service 可据此返回 40401）",
                again == 0, "实际=" + again);
    }

    // ══════════════════════════════════════════════════════════
    // 五、标签
    // ══════════════════════════════════════════════════════════
    private static long testTagInsertAndReuse(Connection conn) throws SQLException {
        long tagId = insertTag(conn, USER_A, "数据层测试标签");
        check("5.1 INSERT 标签成功并回填主键", tagId > 0, "id=" + tagId);

        // 同名标签在同一用户内必须查得到（同名复用走"先查再插"）
        int same = queryCount(conn,
                "SELECT COUNT(1) FROM tag WHERE user_id = ? AND name = ?", USER_A, "数据层测试标签");
        check("5.2 同用户同名标签能查到（用于同名复用）", same == 1, "实际=" + same + " 行");

        // ★ 另一个用户可以用同名标签（证明唯一键是 (user_id, name) 而非 (name)）
        long tagB = insertTag(conn, USER_B, "数据层测试标签");
        check("5.3 ★ 用户 B 可以使用同名标签（唯一键按用户隔离）", tagB > 0 && tagB != tagId,
                "B 的标签 id=" + tagB);

        // 跨用户按名称查标签必须查不到
        int cross = queryCount(conn,
                "SELECT COUNT(1) FROM tag WHERE user_id = ? AND name = ?", USER_B, "数据层测试标签");
        // B 也有同名标签，所以这里应是 1（B 自己的）；关键在于"按 A 的 id 查 B 名下"应为 0
        int crossById = queryCount(conn,
                "SELECT COUNT(1) FROM tag WHERE id = ? AND user_id = ?", tagId, USER_B);
        check("5.4 ★ B 用 A 的标签 ID 查 → 0 行", crossById == 0, "实际=" + crossById + " 行");
        check("5.5 同名标签在不同用户下各自独立（B 自己查到 1 行）", cross == 1, "实际=" + cross);

        // 按 ID 集合批量查（对应 selectByIdsAndUserId，foreach 语法验证）
        int batch = queryCount(conn,
                "SELECT COUNT(1) FROM tag WHERE user_id = ? AND id IN (?, ?)",
                USER_A, tagId, tagB);
        check("5.6 批量查 tagIds：B 的标签 ID 混进来会被过滤掉（只返回 1 行）",
                batch == 1, "实际=" + batch + " 行（传入 A 的 + B 的各 1 个，应只剩 A 的）");

        return tagId;
    }

    // ══════════════════════════════════════════════════════════
    // 六、diary_tag 越权防护（本模块最关键）
    // ══════════════════════════════════════════════════════════
    private static void testDiaryTagOwnership(Connection conn, long tagA) throws SQLException {
        long diaryA = insertDiary(conn, USER_A, "A 的日记（打标签用）", fakeCiphertext("A 的正文"));
        long tagB = getTagId(conn, USER_B, "数据层测试标签");
        long diaryB = insertDiary(conn, USER_B, "B 的日记", fakeCiphertext("B 的正文"));

        // ── 正常路径：A 给自己的日记打自己的标签 ──────────────
        int ok = insertDiaryTagSelect(conn, diaryA, List.of(tagA), USER_A);
        check("6.1 A 给自己的日记打自己的标签 → 插入 1 行", ok == 1, "实际=" + ok);

        // ── ★ 越权 1：B 试图给 A 的日记打标签 ─────────────────
        // INSERT ... SELECT ... JOIN diary WHERE d.user_id = B
        // → JOIN 匹配不到 A 的日记 → 插入 0 行（静默拒绝，不报错）
        int attack1 = insertDiaryTagSelect(conn, diaryA, List.of(tagB), USER_B);
        check("6.2 ★★ B 给 A 的日记打标签 → 插入 0 行（JOIN d.user_id 拦住）",
                attack1 == 0, "实际=" + attack1 + " 行（非 0 说明 SQL 少了 d.user_id 条件，是越权漏洞）");

        int leaked = queryCount(conn,
                "SELECT COUNT(1) FROM diary_tag WHERE diary_id = ? AND tag_id = ?", diaryA, tagB);
        check("6.3 ★ 复核：A 的日记下没有出现 B 的标签", leaked == 0, "实际=" + leaked + " 行");

        // ── ★ 越权 2：A 试图用 B 的标签 ID 打自己的日记 ────────
        // JOIN tag WHERE t.user_id = A → 匹配不到 B 的标签 → 插入 0 行
        int attack2 = insertDiaryTagSelect(conn, diaryA, List.of(tagB), USER_A);
        check("6.4 ★★ A 用 B 的标签 ID 给自己日记打标签 → 插入 0 行（JOIN t.user_id 拦住）",
                attack2 == 0, "实际=" + attack2 + " 行（非 0 说明 SQL 少了 t.user_id 条件）");

        // ── 越权 3：往已软删除的日记打标签必须失败 ─────────────
        long deletedDiary = insertDiary(conn, USER_A, "已删除的日记", fakeCiphertext("正文"));
        softDelete(conn, deletedDiary, USER_A);
        int attack3 = insertDiaryTagSelect(conn, deletedDiary, List.of(tagA), USER_A);
        check("6.5 ★ 往已软删除的日记打标签 → 插入 0 行（d.deleted = 0 生效）",
                attack3 == 0, "实际=" + attack3 + " 行");

        // ── 批量查标签时的权限 ────────────────────────────────
        int readable = queryCount(conn,
                "SELECT COUNT(1) FROM diary_tag dt "
                        + "JOIN diary d ON d.id = dt.diary_id "
                        + "JOIN tag t ON t.id = dt.tag_id "
                        + "WHERE d.user_id = ? AND d.deleted = 0 AND t.user_id = ? AND dt.diary_id IN (?, ?)",
                USER_A, USER_A, diaryA, diaryB);
        check("6.6 ★ 批量查标签：传入 [A 的日记, B 的日记] → 只返回 A 的那条",
                readable == 1, "实际=" + readable + " 行（若为 2 说明能读到 B 的日记标签）");

        // ── ★ 最危险的一条：清空关联必须限定 diary_id ─────────
        // 先给 A 的另一篇日记也打上标签，然后清空 diaryA 的标签，
        // 验证"另一篇的标签没被一起删掉"（漏写 dt.diary_id = ? 就会全删）
        long diaryA2 = insertDiary(conn, USER_A, "A 的第二篇日记", fakeCiphertext("正文2"));
        insertDiaryTagSelect(conn, diaryA2, List.of(tagA), USER_A);

        int cleared;
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE dt FROM diary_tag dt JOIN diary d ON d.id = dt.diary_id "
                        + "WHERE dt.diary_id = ? AND d.user_id = ?")) {
            ps.setLong(1, diaryA);
            ps.setLong(2, USER_A);
            cleared = ps.executeUpdate();
        }
        check("6.7 清空 diaryA 的标签 → 删除 1 行", cleared == 1, "实际=" + cleared);

        int remain = queryCount(conn,
                "SELECT COUNT(1) FROM diary_tag dt JOIN diary d ON d.id = dt.diary_id "
                        + "WHERE d.user_id = ? AND dt.diary_id = ?", USER_A, diaryA2);
        check("6.8 ★★ 复核：A 第二篇日记的标签【没被误删】（证明 WHERE 带了 diary_id）",
                remain == 1, "实际=" + remain + " 行（若为 0 说明 DELETE 漏了 dt.diary_id = ?，"
                        + "会把这用户所有日记的标签全删掉）");

        // ── 清理本段留在 diaryA2 上的关联 ──────────────────────
        // ⚠️ 必须清理：第 7 节要断言"标签被几篇日记使用"的精确计数。
        //    如果这里留下关联，7.1 会数出 2 而不是 1，测试就会"看起来失败"
        //    但代码其实是对的 —— 这类由测试自身状态串味造成的假失败，
        //    会浪费大量时间去查不存在的 bug。
        //
        //    6.7/6.8 的断言已经执行完，所以这里删掉不影响本节的结论。
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE dt FROM diary_tag dt JOIN diary d ON d.id = dt.diary_id "
                        + "WHERE d.user_id = ? AND dt.diary_id = ?")) {
            ps.setLong(1, USER_A);
            ps.setLong(2, diaryA2);
            ps.executeUpdate();
        }
    }

    // ══════════════════════════════════════════════════════════
    // 七、标签删除保护
    // ══════════════════════════════════════════════════════════
    private static void testTagDeleteProtection(Connection conn, long tagA) throws SQLException {
        long diary = insertDiary(conn, USER_A, "占用标签的日记", fakeCiphertext("正文"));
        insertDiaryTagSelect(conn, diary, List.of(tagA), USER_A);

        long used = queryCountLong(conn,
                "SELECT COUNT(1) FROM diary_tag dt "
                        + "JOIN diary d ON d.id = dt.diary_id "
                        + "JOIN tag t ON t.id = dt.tag_id "
                        + "WHERE dt.tag_id = ? AND d.user_id = ? AND d.deleted = 0 AND t.user_id = ?",
                tagA, USER_A, USER_A);
        check("7.1 标签被 1 篇未删除日记使用 → 计数为 1（Service 据此拒绝删除）",
                used == 1, "实际=" + used);

        // 日记软删除后，标签应变成"未被使用"
        softDelete(conn, diary, USER_A);
        long usedAfter = queryCountLong(conn,
                "SELECT COUNT(1) FROM diary_tag dt "
                        + "JOIN diary d ON d.id = dt.diary_id "
                        + "JOIN tag t ON t.id = dt.tag_id "
                        + "WHERE dt.tag_id = ? AND d.user_id = ? AND d.deleted = 0 AND t.user_id = ?",
                tagA, USER_A, USER_A);
        check("7.2 日记软删除后 → 计数变 0（d.deleted = 0 生效，标签可被删除）",
                usedAfter == 0, "实际=" + usedAfter + "（若仍为 1，标签会永远删不掉）");

        // 跨用户：B 用 A 的标签 ID 统计使用量 → 必须 0
        long crossUsed = queryCountLong(conn,
                "SELECT COUNT(1) FROM diary_tag dt "
                        + "JOIN diary d ON d.id = dt.diary_id "
                        + "JOIN tag t ON t.id = dt.tag_id "
                        + "WHERE dt.tag_id = ? AND d.user_id = ? AND d.deleted = 0 AND t.user_id = ?",
                tagA, USER_B, USER_B);
        check("7.3 ★ B 用 A 的标签 ID 统计使用量 → 0（不泄露使用情况）",
                crossUsed == 0, "实际=" + crossUsed);

        // 跨用户删除标签 → 影响 0 行
        int del;
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM tag WHERE id = ? AND user_id = ?")) {
            ps.setLong(1, tagA);
            ps.setLong(2, USER_B);
            del = ps.executeUpdate();
        }
        check("7.4 ★ B 尝试删除 A 的标签 → 影响 0 行", del == 0, "实际=" + del);
    }

    // ══════════════════════════════════════════════════════════
    // 八、分页与筛选
    // ══════════════════════════════════════════════════════════
    private static void testPagingAndFilter(Connection conn) throws SQLException {
        // ⚠️ 关键：不能假设数据库是空的。
        //    开发库里已有 Phase 1 的测试账号，甚至可能有手工造的日记，
        //    所以所有断言都必须用「本次测试独有的标识」或「前后差值」，
        //    否则测试会随库里数据量变化而时红时绿。
        //
        // ⚠️ 标识还必须【足够短】：它要同时用作 mood 的值，而
        //    mood 是 VARCHAR(20)、tag.name 是 VARCHAR(30)。
        //    （第一版用了"ZZ数据层测试_" + nanoTime，21+ 字符，
        //      直接撞 Data too long for column 'mood' —— 见开发文档记录的
        //      "数据库列长度约束也是测试数据设计的一部分"。）
        //    这里用 "ZZ" + 毫秒时间戳后 7 位 = 9 字符，远小于 20。
        final String mark = "ZZ" + String.format("%07d", System.currentTimeMillis() % 10_000_000L);
        final String uniquePrefix = mark + "_";     // 标题前缀
        final String uniqueMood = mark;             // 只属于本次测试的心情值（9 字符）

        long[] ids = new long[5];
        for (int i = 0; i < 5; i++) {
            ids[i] = insertDiary(conn, USER_A, uniquePrefix + "分页" + i, fakeCiphertext("正文 " + i));
        }
        updateMood(conn, ids[0], USER_A, uniqueMood);

        // 总数：用差值断言（>= 5），不断言精确值
        long total = queryCountLong(conn,
                "SELECT COUNT(1) FROM diary WHERE user_id = ? AND deleted = 0", USER_A);
        check("8.1 A 的日记总数 >= 5（用差值，不假设库为空）", total >= 5, "实际=" + total);

        // 分页：只统计本次造的 5 篇
        int page1 = queryCount(conn,
                "SELECT COUNT(1) FROM (SELECT id FROM diary WHERE user_id = ? AND deleted = 0 "
                        + "AND title LIKE CONCAT('%', ?, '%') "
                        + "ORDER BY created_at DESC, id DESC LIMIT 2 OFFSET 0) t",
                USER_A, uniquePrefix);
        check("8.2 LIMIT 2 OFFSET 0 返回 2 条", page1 == 2, "实际=" + page1);

        // 第 2 页
        int page2 = queryCount(conn,
                "SELECT COUNT(1) FROM (SELECT id FROM diary WHERE user_id = ? AND deleted = 0 "
                        + "AND title LIKE CONCAT('%', ?, '%') "
                        + "ORDER BY created_at DESC, id DESC LIMIT 2 OFFSET 2) t",
                USER_A, uniquePrefix);
        check("8.3 第 2 页（OFFSET 2）也返回 2 条", page2 == 2, "实际=" + page2);

        // 越界页返回空（而不是报错）
        int beyond = queryCount(conn,
                "SELECT COUNT(1) FROM (SELECT id FROM diary WHERE user_id = ? AND deleted = 0 "
                        + "ORDER BY created_at DESC, id DESC LIMIT 20 OFFSET 100000) t", USER_A);
        check("8.4 越界页返回 0 条（不报错）", beyond == 0, "实际=" + beyond);

        // ★ 同秒创建的 5 篇必须都能被翻出来（验证 created_at 平局用 id 打破）
        //   如果 ORDER BY 只有 created_at，同秒数据顺序不确定，可能重复或漏掉
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int offset = 0; offset < 6; offset += 2) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM diary WHERE user_id = ? AND deleted = 0 "
                            + "AND title LIKE CONCAT('%', ?, '%') "
                            + "ORDER BY created_at DESC, id DESC LIMIT 2 OFFSET ?")) {
                ps.setLong(1, USER_A);
                ps.setString(2, uniquePrefix);
                ps.setInt(3, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        seen.add(rs.getLong(1));
                    }
                }
            }
        }
        check("8.5 ★ 翻页无重复无遗漏（5 篇同秒创建，靠 id 打破平局）",
                seen.size() == 5, "实际翻出 " + seen.size() + " 篇不重复的日记（应为 5）");

        // 标题模糊搜索（只搜标题）—— 前缀唯一，所以可以断言精确值
        int byKeyword = queryCount(conn,
                "SELECT COUNT(1) FROM diary WHERE user_id = ? AND deleted = 0 "
                        + "AND title LIKE CONCAT('%', ?, '%')", USER_A, uniquePrefix);
        check("8.6 标题模糊搜索命中 5 篇", byKeyword == 5, "实际=" + byKeyword);

        // ★ 关键词不应能搜到正文（正文是密文，LIKE 匹配不到明文片段）
        int byContent = queryCount(conn,
                "SELECT COUNT(1) FROM diary WHERE user_id = ? AND deleted = 0 "
                        + "AND title LIKE CONCAT('%', ?, '%')", USER_A, "正文 0");
        check("8.7 ★ 用正文内容当关键词 → 搜不到（证明只搜标题，符合已确认的设计）",
                byContent == 0, "实际=" + byContent + " 篇");

        // 跨用户搜索：B 搜同样关键词应为 0
        int crossKeyword = queryCount(conn,
                "SELECT COUNT(1) FROM diary WHERE user_id = ? AND deleted = 0 "
                        + "AND title LIKE CONCAT('%', ?, '%')", USER_B, uniquePrefix);
        check("8.8 ★ B 搜同样关键词 → 0 篇（搜索也受 user_id 隔离）",
                crossKeyword == 0, "实际=" + crossKeyword + " 篇");

        // 心情筛选 —— 用本次独有的心情值，可断言精确值
        int byMood = queryCount(conn,
                "SELECT COUNT(1) FROM diary WHERE user_id = ? AND deleted = 0 AND mood = ?",
                USER_A, uniqueMood);
        check("8.9 按心情筛选命中 1 篇（心情值本次独有）", byMood == 1, "实际=" + byMood);

        // 时间范围：闭区间应能命中本次的 5 篇（用标题限定，避免受库里其他数据影响）
        int byRange = queryCount(conn,
                "SELECT COUNT(1) FROM diary WHERE user_id = ? AND deleted = 0 "
                        + "AND title LIKE CONCAT('%', ?, '%') "
                        + "AND created_at >= ? AND created_at <= ?",
                USER_A, uniquePrefix, "2000-01-01 00:00:00", "2099-12-31 23:59:59");
        check("8.10 时间范围（闭区间）能命中全部 5 篇", byRange == 5, "实际=" + byRange);

        // 时间范围反向（过去的时间段）应为 0 —— 证明范围条件真的生效
        int beforeRange = queryCount(conn,
                "SELECT COUNT(1) FROM diary WHERE user_id = ? AND deleted = 0 "
                        + "AND title LIKE CONCAT('%', ?, '%') "
                        + "AND created_at >= ? AND created_at <= ?",
                USER_A, uniquePrefix, "2000-01-01 00:00:00", "2000-12-31 23:59:59");
        check("8.11 时间范围落在过去 → 0 篇（证明范围条件真的在过滤）",
                beforeRange == 0, "实际=" + beforeRange);

        // 标签筛选（EXISTS 写法，验证不会因多标签产生重复行）
        long tagX = insertTag(conn, USER_A, uniquePrefix + "_标签X");
        long tagY = insertTag(conn, USER_A, uniquePrefix + "_标签Y");
        long d1 = insertDiary(conn, USER_A, uniquePrefix + " 带两个标签", fakeCiphertext("正文"));
        insertDiaryTagSelect(conn, d1, List.of(tagX, tagY), USER_A);

        int byTag = queryCount(conn,
                "SELECT COUNT(1) FROM diary WHERE user_id = ? AND deleted = 0 AND EXISTS ("
                        + "SELECT 1 FROM diary_tag dt JOIN tag t ON t.id = dt.tag_id "
                        + "WHERE dt.diary_id = diary.id AND t.id = ? AND t.user_id = ?)",
                USER_A, tagX, USER_A);
        check("8.12 ★ 按标签筛选：一篇日记挂 2 个标签也只算 1 行（EXISTS 不产生重复行）",
                byTag == 1, "实际=" + byTag + " 行（若为 2 说明用 JOIN 产生了重复行）");

        // 用 B 的身份按同一个 tagId 筛选 → 0 行（跨用户标签筛选隔离）
        int tagCrossUser = queryCount(conn,
                "SELECT COUNT(1) FROM diary WHERE user_id = ? AND deleted = 0 AND EXISTS ("
                        + "SELECT 1 FROM diary_tag dt JOIN tag t ON t.id = dt.tag_id "
                        + "WHERE dt.diary_id = diary.id AND t.id = ? AND t.user_id = ?)",
                USER_B, tagX, USER_B);
        check("8.13 ★ B 用 A 的 tagId 筛选 → 0 行（标签筛选也受隔离）",
                tagCrossUser == 0, "实际=" + tagCrossUser);
    }

    // ══════════════════════════════════════════════════════════
    // SQL 辅助（与 XML 中的语句等价）
    // ══════════════════════════════════════════════════════════

    /** 等价于 DiaryTagMapper.insertByDiaryIdAndTagIds 生成的 SQL。 */
    private static int insertDiaryTagSelect(Connection conn, long diaryId,
                                            List<Long> tagIds, long userId) throws SQLException {
        StringBuilder union = new StringBuilder();
        for (int i = 0; i < tagIds.size(); i++) {
            if (i > 0) {
                union.append(" UNION ALL ");
            }
            union.append("SELECT ? AS tag_id");
        }
        String sql = "INSERT INTO diary_tag (diary_id, tag_id) "
                + "SELECT ?, v.tag_id FROM (" + union + ") v "
                + "JOIN diary d ON d.id = ? "
                + "JOIN tag t ON t.id = v.tag_id "
                + "WHERE d.user_id = ? AND d.deleted = 0 AND t.user_id = ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setLong(idx++, diaryId);
            for (Long tagId : tagIds) {
                ps.setLong(idx++, tagId);
            }
            ps.setLong(idx++, diaryId);
            ps.setLong(idx++, userId);
            ps.setLong(idx, userId);
            return ps.executeUpdate();
        }
    }

    private static long insertDiary(Connection conn, long userId, String title, String cipher)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO diary (user_id, title, content_ciphertext) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setString(2, title);
            ps.setString(3, cipher);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static long insertTag(Connection conn, long userId, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tag (user_id, name) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setString(2, name);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static long getTagId(Connection conn, long userId, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM tag WHERE user_id = ? AND name = ? LIMIT 1")) {
            ps.setLong(1, userId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    private static void softDelete(Connection conn, long id, long userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE diary SET deleted = 1 WHERE id = ? AND user_id = ? AND deleted = 0")) {
            ps.setLong(1, id);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    private static void updateMood(Connection conn, long id, long userId, String mood)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE diary SET mood = ? WHERE id = ? AND user_id = ? AND deleted = 0")) {
            ps.setString(1, mood);
            ps.setLong(2, id);
            ps.setLong(3, userId);
            ps.executeUpdate();
        }
    }

    // ══════════════════════════════════════════════════════════
    // 元数据查询
    // ══════════════════════════════════════════════════════════

    private static String columnType(Connection conn, String table, String column) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT DATA_TYPE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "(列不存在)";
            }
        }
    }

    private static boolean columnExists(Connection conn, String table, String column)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(1) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private static int countPrimaryKeyColumns(Connection conn, String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(1) FROM information_schema.KEY_COLUMN_USAGE "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? "
                        + "AND CONSTRAINT_NAME = 'PRIMARY'")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static boolean indexExists(Connection conn, String table, String indexName)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(1) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?")) {
            ps.setString(1, table);
            ps.setString(2, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // 通用工具
    // ══════════════════════════════════════════════════════════

    /**
     * 造一段"看起来像真密文"的 Base64 字符串。
     *
     * <p>本测试只验证<b>列的存取能力</b>，不验证加解密本身
     * （那由 backend/src/test 的 AesGcmUtilTest 51 条覆盖），
     * 所以这里不需要真的加密，用 Base64 包装即可，
     * 好处是这个测试不依赖 Spring 的 jar。
     */
    private static String fakeCiphertext(String plaintext) {
        return java.util.Base64.getEncoder()
                .encodeToString(plaintext.getBytes(StandardCharsets.UTF_8));
    }

    private static int queryCount(Connection conn, String sql, Object... params) throws SQLException {
        return (int) queryCountLong(conn, sql, params);
    }

    /**
     * 参数全是字符串时用的重载。
     *
     * <p>为什么需要它：Java 对「单个 String 实参」会优先匹配 {@code (String sql, Object...)}
     * 还是 {@code (String sql, String...)} 存在歧义风险 —— 显式提供 String 版本
     * 可以避免编译器的重载解析出现意外。
     */
    private static int queryCount(Connection conn, String sql, String p1) throws SQLException {
        return (int) queryCountLong(conn, sql, p1);
    }

    private static int queryCount(Connection conn, String sql, String p1, String p2)
            throws SQLException {
        return (int) queryCountLong(conn, sql, p1, p2);
    }

    private static long queryCountLong(Connection conn, String sql, Object... params)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static int len(String s) {
        return s == null ? -1 : s.length();
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("── " + title + " ──");
    }

    private static void check(String name, boolean ok) {
        check(name, ok, null);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name + (detail == null ? "" : "  → " + detail));
        }
    }

    /** 从项目根的 .env 读数据库凭据（与后端同一份配置真源）。 */
    private static Map<String, String> loadDotenv() {
        Map<String, String> map = new HashMap<>();
        Path[] candidates = {
                Path.of("..", ".env"),
                Path.of(".env"),
                Path.of("D:", "summerDiary", ".env")
        };
        for (Path p : candidates) {
            if (!Files.isRegularFile(p)) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) {
                        continue;
                    }
                    int eq = t.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String key = t.substring(0, eq).trim();
                    String value = t.substring(eq + 1).trim();
                    if (value.length() >= 2
                            && ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'")))) {
                        value = value.substring(1, value.length() - 1);
                    } else {
                        int hash = value.indexOf(" #");
                        if (hash > 0) {
                            value = value.substring(0, hash).trim();
                        }
                    }
                    map.put(key, value);
                }
                System.out.println("已加载凭据: " + p.toAbsolutePath().normalize());
                break;
            } catch (Exception ignored) {
                // 读不到就退回默认值，下面的报错会说明问题
            }
        }
        return map;
    }
}
