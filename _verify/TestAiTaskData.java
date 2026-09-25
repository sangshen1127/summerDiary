import com.sangshen.aidiary.entity.AiTask;
import com.sangshen.aidiary.entity.DiaryAnalysis;
import com.sangshen.aidiary.entity.enums.AiTaskStatus;
import com.sangshen.aidiary.entity.enums.AiTaskTypes.SourceType;
import com.sangshen.aidiary.entity.enums.AiTaskTypes.TaskType;
import com.sangshen.aidiary.mapper.AiTaskMapper;
import com.sangshen.aidiary.mapper.DiaryAnalysisMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

/**
 * ============================================================
 * Phase 3 数据层验收：V2 迁移 + 真实 MyBatis 链路
 * ============================================================
 *
 * <h2>做两件事</h2>
 * <ol>
 *   <li>把 V2 迁移应用到开发库（幂等，可重复执行）</li>
 *   <li>用真实 MyBatis 跑一遍新增的 Mapper，验证：
 *       嵌套枚举的 typeHandler、INSERT IGNORE 幂等、
 *       原子抢占、JSON 列读写、upsert</li>
 * </ol>
 *
 * <h2>为什么必须走真实 MyBatis（而不是 JDBC 手抄 SQL）</h2>
 *
 * <p>本次新增的 XML 里到处是
 * {@code typeHandler=com.sangshen.aidiary.entity.enums.AiTaskTypes$SourceType}
 * 这种引用。**嵌套类名里的 {@code $} 是否被正确解析、枚举是否真的按
 * name 存取**，JDBC 测试完全测不到 —— 而一旦 typeHandler 退化成
 * Ordinal，数据库里会存 0/1/2，可读性全毁且极难排查。
 *
 * <h2>数据清理</h2>
 *
 * <p>全程单事务 + rollback（本文件不测 HTTP，所以可以这么做）。
 */
public class TestAiTaskData {

    private static final String URL = "jdbc:mysql://localhost:3307/ai_diary"
            + "?useUnicode=true&characterEncoding=UTF-8"
            + "&connectionCollation=utf8mb4_0900_ai_ci"
            + "&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";

    private static final String MIGRATION =
            "../backend/src/main/resources/db/migration/V2__ai_analysis.sql";

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("========== Phase 3 data layer acceptance ==========");

        // ── 1. 应用 V2 迁移（幂等）─────────────────────────────
        section("1. apply V2 migration");
        applyMigration();

        // ── 2. 真实 MyBatis ───────────────────────────────────
        SqlSessionFactory factory = buildFactory();
        System.out.println("\nMyBatis SqlSessionFactory built (mapper XML parsed OK)");

        try (SqlSession session = factory.openSession()) {
            try {
                AiTaskMapper taskMapper = session.getMapper(AiTaskMapper.class);
                DiaryAnalysisMapper analysisMapper = session.getMapper(DiaryAnalysisMapper.class);

                /*
                 * ⚠️ 取当前 SqlSession 的底层 Connection，供"直接查原始列"用。
                 *
                 * 第一版这里犯了错：辅助方法各自 DriverManager.getConnection()
                 * 开新连接，结果读到 0 行 —— 因为测试数据在**未提交的事务**里
                 * （本测试最后 rollback），另一个连接按 REPEATABLE READ
                 * 根本看不到。
                 *
                 * 症状是 "数据库里 status 列存的是 '(no row)'"，看起来像
                 * 数据没写进去，实际是**看不到**。这类"隔离级别导致的假失败"
                 * 很容易被误判成功能 bug。
                 *
                 * 用同一个 connection 查才不会踩到。
                 */
                java.sql.Connection conn = session.getConnection();

                section("2. AiTaskMapper: insert + 枚举 typeHandler");
                long taskId = testInsert(taskMapper, conn);

                section("3. AiTaskMapper: 幂等键（INSERT IGNORE）");
                testIdempotency(taskMapper, conn);

                section("4. AiTaskMapper: 查询与权限隔离");
                testQueryAndOwnership(taskMapper, taskId);

                section("5. AiTaskMapper: 原子抢占（并发安全的关键）");
                testClaim(taskMapper, taskId);

                section("6. AiTaskMapper: 状态流转");
                testStatusFlow(taskMapper);

                section("7. DiaryAnalysisMapper: JSON 列 + upsert");
                testAnalysis(analysisMapper, conn);

                section("8. DiaryAnalysisMapper: 权限隔离");
                testAnalysisOwnership(analysisMapper);
            } finally {
                session.rollback();
                System.out.println("\nrolled back -- no test data left.");
            }
        }

        System.out.println();
        System.out.println("通过: " + passed + "   失败: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ══════════════════════════════════════════════════════════
    private static void applyMigration() throws Exception {
        String sql = Files.readString(Path.of(MIGRATION), StandardCharsets.UTF_8);
        try (Connection c = DriverManager.getConnection(URL, "ai_diary", "ai_diary_dev_pw")) {
            int ok = 0;
            StringBuilder cur = new StringBuilder();
            for (String rawLine : sql.split("\n")) {
                String line = rawLine;
                int ci = line.indexOf("--");
                if (ci >= 0) {
                    line = line.substring(0, ci);
                }
                if (line.isBlank()) {
                    continue;
                }
                cur.append(line).append('\n');
                if (line.trim().endsWith(";")) {
                    String stmt = cur.toString().trim();
                    stmt = stmt.substring(0, stmt.length() - 1);
                    if (!stmt.isBlank()) {
                        try (Statement s = c.createStatement()) {
                            s.execute(stmt);
                            ok++;
                        }
                    }
                    cur.setLength(0);
                }
            }
            check("1.1 V2 migration applied (" + ok + " statements)", ok == 2, "executed=" + ok);

            // 确认表在有数据的库上真的建出来了
            check("1.2 ai_task table exists",
                    tableExists(c, "ai_task"), "not found");
            check("1.3 diary_analysis table exists",
                    tableExists(c, "diary_analysis"), "not found");
        }
    }

    private static boolean tableExists(Connection c, String t) throws Exception {
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT COUNT(1) FROM information_schema.TABLES "
                     + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='" + t + "'")) {
            r.next();
            return r.getInt(1) > 0;
        }
    }

    private static SqlSessionFactory buildFactory() {
        UnpooledDataSource ds = new UnpooledDataSource(
                "com.mysql.cj.jdbc.Driver", URL, "ai_diary", "ai_diary_dev_pw");
        Configuration cfg = new Configuration(
                new Environment("verify", new JdbcTransactionFactory(), ds));
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.setCallSettersOnNulls(true);
        cfg.getTypeAliasRegistry().registerAliases("com.sangshen.aidiary.entity");

        String[] xmls = {"mapper/AiTaskMapper.xml", "mapper/DiaryAnalysisMapper.xml"};
        for (String xml : xmls) {
            try (InputStream in = TestAiTaskData.class.getClassLoader().getResourceAsStream(xml)) {
                if (in == null) {
                    throw new IllegalStateException("classpath 缺少 " + xml
                            + "（先跑 mvn -o -DskipTests compile）");
                }
                new XMLMapperBuilder(in, cfg, xml, cfg.getSqlFragments()).parse();
            } catch (Exception e) {
                throw new IllegalStateException("解析 " + xml + " 失败: " + e.getMessage(), e);
            }
        }
        System.out.println("  registered: AiTaskMapper=" + cfg.hasMapper(AiTaskMapper.class)
                + " DiaryAnalysisMapper=" + cfg.hasMapper(DiaryAnalysisMapper.class));
        return new SqlSessionFactoryBuilder().build(cfg);
    }

    // ══════════════════════════════════════════════════════════
    private static long testInsert(AiTaskMapper m, java.sql.Connection conn) {
        AiTask t = newTask(9001L, 555L, "idem-1");
        int n = m.insert(t);
        check("2.1 insert 影响 1 行", n == 1, "实际=" + n);
        check("2.2 主键已回填", t.getId() != null && t.getId() > 0, "id=" + t.getId());

        AiTask loaded = m.selectByIdAndUserId(t.getId(), 9001L);
        check("2.3 能查到", loaded != null);
        if (loaded != null) {
            // ★ 枚举 typeHandler 的关键验证：数据库里必须是 name 而不是 ordinal
            check("2.4 ★ 枚举按 name 存取（sourceType=DIARY）",
                    loaded.getSourceType() == SourceType.DIARY,
                    "实际=" + loaded.getSourceType());
            check("2.5 ★ 枚举按 name 存取（taskType=DIARY_ANALYZE）",
                    loaded.getTaskType() == TaskType.DIARY_ANALYZE,
                    "实际=" + loaded.getTaskType());
            check("2.6 ★ 枚举按 name 存取（status=PENDING）",
                    loaded.getStatus() == AiTaskStatus.PENDING,
                    "实际=" + loaded.getStatus());
            check("2.7 默认 retry_count=0", loaded.getRetryCount() == 0,
                    "实际=" + loaded.getRetryCount());
            check("2.8 默认 version=1", loaded.getVersion() == 1,
                    "实际=" + loaded.getVersion());
            check("2.9 created_at 由数据库填充（非 null）",
                    loaded.getCreatedAt() != null, "为 null");
            check("2.10 updated_at 由数据库填充（非 null）",
                    loaded.getUpdatedAt() != null, "为 null");
        }

        // ★ 直接查数据库确认存的是字符串而不是数字
        String rawStatus = rawColumn(conn, "ai_task", "status", t.getId());
        check("2.11 ★★ 数据库里 status 列存的是字符串 '" + rawStatus + "' 而不是 0/1",
                "PENDING".equals(rawStatus), "实际=" + rawStatus);
        String rawSource = rawColumn(conn, "ai_task", "source_type", t.getId());
        check("2.12 ★★ 数据库里 source_type 存的是 'DIARY'",
                "DIARY".equals(rawSource), "实际=" + rawSource);
        return t.getId();
    }

    private static void testIdempotency(AiTaskMapper m, java.sql.Connection conn) {
        AiTask dup = newTask(9001L, 555L, "idem-1");   // 同一个幂等键
        int n = m.insertIgnoreDuplicate(dup);
        check("3.1 ★★ 同幂等键第二次插入 → 0 行（被忽略，不报错）",
                n == 0, "实际=" + n + "（应为 0；非 0 说明唯一键没生效）");

        int total = countTasksWithKey(conn, "idem-1");
        check("3.2 库里该幂等键只有 1 条任务", total == 1, "实际=" + total);

        AiTask other = newTask(9001L, 556L, "idem-2");
        int n2 = m.insertIgnoreDuplicate(other);
        check("3.3 不同幂等键 → 正常插入 1 行", n2 == 1, "实际=" + n2);
    }

    private static void testQueryAndOwnership(AiTaskMapper m, long taskId) {
        AiTask mine = m.selectByIdAndUserId(taskId, 9001L);
        check("4.1 本人能查到自己创建的任务", mine != null);

        AiTask others = m.selectByIdAndUserId(taskId, 9002L);
        check("4.2 ★ 别人用同一个 taskId 查 → null（不泄露存在性）",
                others == null, "查到了别人的任务！");

        AiTask bySource = m.selectBySource(9001L, SourceType.DIARY, 555L, TaskType.DIARY_ANALYZE);
        check("4.3 按来源查任务能命中", bySource != null && bySource.getId() == taskId,
                bySource == null ? "null" : ("id=" + bySource.getId()));

        AiTask wrongOwner = m.selectBySource(9002L, SourceType.DIARY, 555L, TaskType.DIARY_ANALYZE);
        check("4.4 ★ 别人按来源查 → null", wrongOwner == null, "查到了！");
    }

    private static void testClaim(AiTaskMapper m, long taskId) {
        // 先造一条 PENDING 任务
        AiTask t = newTask(9003L, 700L, "idem-claim");
        m.insertIgnoreDuplicate(t);
        long id = t.getId();

        List<AiTaskStatus> claimable = List.of(AiTaskStatus.PENDING, AiTaskStatus.RETRYING);

        int first = m.claimForRunning(id, claimable);
        check("5.1 ★★ 第一次抢占 → 1 行（抢到了）", first == 1, "实际=" + first);

        // ★ 这是并发安全的核心：第二次抢占必须失败
        int second = m.claimForRunning(id, claimable);
        check("5.2 ★★ 第二次抢占同一任务 → 0 行（已被抢走，正确拒绝）",
                second == 0, "实际=" + second
                        + "（非 0 说明两个 Worker 会同时执行同一任务！）");

        AiTask after = m.selectByIdAndUserId(id, 9003L);
        check("5.3 状态已变为 RUNNING",
                after != null && after.getStatus() == AiTaskStatus.RUNNING,
                after == null ? "null" : ("status=" + after.getStatus()));
        check("5.4 started_at 已写入", after != null && after.getStartedAt() != null,
                "为 null");
    }

    private static void testStatusFlow(AiTaskMapper m) {
        // ── 成功路径 ──────────────────────────────────────────
        AiTask ok = newTask(9004L, 800L, "idem-ok");
        m.insertIgnoreDuplicate(ok);
        m.claimForRunning(ok.getId(), List.of(AiTaskStatus.PENDING));
        int n = m.markSuccess(ok.getId(), "v1");
        check("6.1 RUNNING → SUCCESS 影响 1 行", n == 1, "实际=" + n);
        AiTask okAfter = m.selectByIdAndUserId(ok.getId(), 9004L);
        check("6.2 状态为 SUCCESS 且 finished_at 已写",
                okAfter != null && okAfter.getStatus() == AiTaskStatus.SUCCESS
                        && okAfter.getFinishedAt() != null,
                okAfter == null ? "null" : String.valueOf(okAfter.getStatus()));

        // 重复标成功应该无效（条件要求 RUNNING）
        int again = m.markSuccess(ok.getId(), "v1");
        check("6.3 重复 markSuccess → 0 行（条件 status=RUNNING 生效）",
                again == 0, "实际=" + again);

        // ── 失败 + 重试路径 ───────────────────────────────────
        AiTask bad = newTask(9005L, 900L, "idem-bad");
        m.insertIgnoreDuplicate(bad);
        m.claimForRunning(bad.getId(), List.of(AiTaskStatus.PENDING));
        int f = m.markFailed(bad.getId(), "AI_TIMEOUT", "connect timed out");
        check("6.4 RUNNING → FAILED 影响 1 行", f == 1, "实际=" + f);

        AiTask badAfter = m.selectByIdAndUserId(bad.getId(), 9005L);
        check("6.5 ★ retry_count 已 +1", badAfter != null && badAfter.getRetryCount() == 1,
                badAfter == null ? "null" : ("retry=" + badAfter.getRetryCount()));
        check("6.6 error_code 已记录",
                badAfter != null && "AI_TIMEOUT".equals(badAfter.getErrorCode()),
                badAfter == null ? "null" : ("code=" + badAfter.getErrorCode()));

        int r = m.markRetrying(bad.getId());
        check("6.7 FAILED → RETRYING 影响 1 行", r == 1, "实际=" + r);

        // RETRYING 也能被抢占
        int claimRetry = m.claimForRunning(bad.getId(), List.of(AiTaskStatus.PENDING, AiTaskStatus.RETRYING));
        check("6.8 ★ RETRYING 状态也能被抢占（重试路径通）", claimRetry == 1, "实际=" + claimRetry);

        // ── 重新分析：重置 ────────────────────────────────────
        m.markFailed(bad.getId(), "AI_TIMEOUT", "second failure");
        int reset = m.resetForRetry(bad.getId());
        check("6.9 FAILED → 重置为 PENDING 影响 1 行", reset == 1, "实际=" + reset);
        AiTask resetAfter = m.selectByIdAndUserId(bad.getId(), 9005L);
        check("6.10 重置后 retry_count 归零、错误信息清空、状态为 PENDING",
                resetAfter != null && resetAfter.getRetryCount() == 0
                        && resetAfter.getErrorCode() == null
                        && resetAfter.getStatus() == AiTaskStatus.PENDING,
                resetAfter == null ? "null" : resetAfter.toSafeString());

        // ★ 运行中的任务不能被重置（防重复执行）
        AiTask running = newTask(9006L, 901L, "idem-running");
        m.insertIgnoreDuplicate(running);
        m.claimForRunning(running.getId(), List.of(AiTaskStatus.PENDING));
        int resetRunning = m.resetForRetry(running.getId());
        check("6.11 ★★ 重置 RUNNING 中的任务 → 0 行（防止两个 Worker 同时跑）",
                resetRunning == 0, "实际=" + resetRunning);
    }

    private static void testAnalysis(DiaryAnalysisMapper m, java.sql.Connection conn) {
        DiaryAnalysis a = new DiaryAnalysis();
        a.setUserId(9001L);
        a.setDiaryId(555L);
        a.setSummary("今天去了河边");
        a.setEmotionJson("{\"primary\":\"平静\",\"intensity\":0.6}");
        a.setTopicsJson("[\"散步\",\"阅读\"]");
        a.setSchemaVersion("v1");
        a.setPromptVersion("v1");

        int n = m.upsert(a);
        check("7.1 upsert 影响 1 行（新插入）", n == 1, "实际=" + n);

        DiaryAnalysis loaded = m.selectByDiaryIdAndUserId(555L, 9001L);
        check("7.2 能查到", loaded != null);
        if (loaded != null) {
            check("7.3 summary 原样存取", "今天去了河边".equals(loaded.getSummary()),
                    "实际=" + loaded.getSummary());
            check("7.4 ★ JSON 列能读回（emotion_json 非 null）",
                    loaded.getEmotionJson() != null, "为 null");
            check("7.5 ★ JSON 内容正确",
                    loaded.getEmotionJson() != null
                            && loaded.getEmotionJson().contains("平静"),
                    "实际=" + loaded.getEmotionJson());
            check("7.6 topics_json 是数组形式",
                    loaded.getTopicsJson() != null && loaded.getTopicsJson().startsWith("["),
                    "实际=" + loaded.getTopicsJson());
            check("7.7 schema_version / prompt_version 已存",
                    "v1".equals(loaded.getSchemaVersion()) && "v1".equals(loaded.getPromptVersion()),
                    "schema=" + loaded.getSchemaVersion() + " prompt=" + loaded.getPromptVersion());
        }

        // ★ 重新分析：upsert 应该覆盖而不是报错
        a.setSummary("覆盖后的摘要");
        a.setEmotionJson("{\"primary\":\"开心\"}");
        // user_id 必填（upsert 会更新它）
        a.setUserId(9001L);
        int n2 = m.upsert(a);
        check("7.8 ★★ 同日记再 upsert → 覆盖（MySQL 语义影响 2 行）", n2 == 2,
                "实际=" + n2 + "（1=插入 2=更新）");

        DiaryAnalysis after = m.selectByDiaryIdAndUserId(555L, 9001L);
        check("7.9 ★ 摘要已被覆盖为新值",
                after != null && "覆盖后的摘要".equals(after.getSummary()),
                after == null ? "null" : after.getSummary());
        check("7.10 数据库里该日记只有 1 条分析（唯一键生效）",
                countAnalysis(conn, 555L) == 1, "实际=" + countAnalysis(conn, 555L));

        // 删除
        int d = m.deleteByDiaryIdAndUserId(555L, 9001L);
        check("7.11 删除影响 1 行", d == 1, "实际=" + d);
        check("7.12 删除后查不到",
                m.selectByDiaryIdAndUserId(555L, 9001L) == null, "还能查到");
    }

    private static void testAnalysisOwnership(DiaryAnalysisMapper m) {
        DiaryAnalysis a = new DiaryAnalysis();
        a.setUserId(9010L);
        a.setDiaryId(999L);
        a.setSummary("别人的分析");
        m.upsert(a);

        check("8.1 ★ 别人按 diaryId 查 → null",
                m.selectByDiaryIdAndUserId(999L, 9011L) == null, "查到了别人的分析！");
        check("8.2 本人查得到（对照组）",
                m.selectByDiaryIdAndUserId(999L, 9010L) != null, "查不到");

        int d = m.deleteByDiaryIdAndUserId(999L, 9011L);
        check("8.3 ★ 别人删除 → 0 行（userId 条件拦住）", d == 0, "实际=" + d);
        check("8.4 复核：数据还在",
                m.selectByDiaryIdAndUserId(999L, 9010L) != null, "被删掉了！");
    }

    // ══════════════════════════════════════════════════════════
    // 工具
    // ══════════════════════════════════════════════════════════

    private static AiTask newTask(long userId, long sourceId, String idemKey) {
        AiTask t = new AiTask();
        t.setUserId(userId);
        t.setSourceType(SourceType.DIARY);
        t.setSourceId(sourceId);
        t.setTaskType(TaskType.DIARY_ANALYZE);
        t.setStatus(AiTaskStatus.PENDING);
        t.setRetryCount(0);
        t.setIdempotencyKey(idemKey);
        t.setVersion(1);
        return t;
    }

    /** 直接读原始列值，用于确认枚举存的是 name 而不是 ordinal。 */
    private static String rawColumn(java.sql.Connection c, String table, String column, long id) {
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT " + column + " FROM " + table
                     + " WHERE id = " + id)) {
            return r.next() ? r.getString(1) : "(no row)";
        } catch (Exception e) {
            return "(error: " + e.getMessage() + ")";
        }
    }

    private static int countTasksWithKey(java.sql.Connection c, String key) {
        return scalarInt(c, "SELECT COUNT(1) FROM ai_task WHERE idempotency_key = '" + key + "'");
    }

    private static int countAnalysis(java.sql.Connection c, long diaryId) {
        return scalarInt(c, "SELECT COUNT(1) FROM diary_analysis WHERE diary_id = " + diaryId);
    }

    /**
     * ⚠️ 必须用调用方传入的 connection，不能自己 DriverManager.getConnection()。
     *
     * <p>测试数据在未提交的事务里；新开连接按 REPEATABLE READ 看不到它，
     * 会得到 0 行 —— 表现为"看起来数据没写进去"的假失败。
     */
    private static int scalarInt(java.sql.Connection c, String sql) {
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getInt(1);
        } catch (Exception e) {
            return -1;
        }
    }

    private static void section(String t) {
        System.out.println("\n-- " + t + " --");
    }

    private static void check(String name, boolean ok) {
        check(name, ok, null);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + "  -> " + detail);
        }
    }
}
