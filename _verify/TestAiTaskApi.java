import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ============================================================
 * Phase 3 acceptance: AI analysis over real HTTP (success path)
 * ============================================================
 *
 * <h2>What this proves</h2>
 * <ol>
 *   <li>Create diary returns fast <b>without waiting for the model</b>
 *       (mock is made slow on purpose via AI_MOCK_DELAY_MS).</li>
 *   <li>The task walks PENDING/RUNNING -> SUCCESS in the background,
 *       and {@code analysis_status} reflects it.</li>
 *   <li>{@code GET /api/diaries/{id}/analysis} returns the full snapshot
 *       and the JSON columns come back as <b>real objects/arrays</b>
 *       (not double-escaped strings) -- this is the {@code @JsonRawValue}
 *       contract, which is invisible to the compiler.</li>
 *   <li>Re-analysis is idempotent: still exactly ONE task row per diary.</li>
 *   <li>Ownership: another user gets 40401 on all three AI endpoints.</li>
 *   <li>No cookie -> 40101.</li>
 * </ol>
 *
 * <h2>Preconditions (the test checks them itself)</h2>
 * <pre>
 *   backend must run with:
 *     AI_ANALYSIS_ENABLED=true
 *     AI_MOCK_OUTCOME=success        (default)
 *     AI_MOCK_DELAY_MS=1500          (strongly recommended, see docs)
 *     AI_WORKER_INTERVAL_MS=500
 *     AI_RETRY_BACKOFF_BASE_MS=500
 * </pre>
 * The mode is read from {@code /actuator/health} instead of being assumed --
 * running this against a backend in the wrong mode would otherwise produce
 * "failures" that are really just wrong expectations.
 *
 * <h2>Data hygiene</h2>
 * <p>Creates two users ({@code ZZAI<ts>_a/_b}) and deletes them plus all
 * their rows at the end (section 12). Earlier phases left 122 accounts behind
 * because their tests did not clean up; this one does.
 */
public class TestAiTaskApi {

    /**
     * Backend base URL.
     *
     * <p>Override with {@code -Dai.base=http://localhost:8081} to run against a
     * second backend instance started in AI mode -- useful when the normal
     * 8080 instance (IDE / mvn spring-boot:run) must keep running untouched.
     */
    private static final String ROOT = System.getProperty("ai.base", "http://localhost:8080");
    private static final String BASE = ROOT + "/api";
    private static final String ACTUATOR = ROOT + "/actuator/health";

    private static final String DB_URL = "jdbc:mysql://localhost:3307/ai_diary"
            + "?useUnicode=true&characterEncoding=UTF-8"
            + "&connectionCollation=utf8mb4_0900_ai_ci"
            + "&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
    private static final String DB_USER = "ai_diary";
    private static final String DB_PASS = "ai_diary_dev_pw";

    /** Unique per run so repeated runs never collide on the username unique key. */
    private static final String RUN = "ZZAI" + (System.currentTimeMillis() % 100000);

    /** How long we are willing to wait for the background worker. */
    private static final long POLL_TIMEOUT_MS = 40_000L;
    private static final long POLL_INTERVAL_MS = 250L;

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("========== Phase 3 AI task acceptance (success path) ==========");

        // ── 0. preflight ────────────────────────────────────────
        section("0. preflight: backend mode");
        if (!healthOk()) {
            System.out.println("  [FAIL] backend not reachable at " + BASE);
            System.out.println("  start it: cd D:\\summerDiary\\backend ; mvn -o spring-boot:run");
            System.exit(1);
        }
        check("0.1 /api/health is UP", true);

        String health = httpGet(ACTUATOR);
        String analysisEnabled = jsonString(health, "analysisEnabled");
        String workerActive = jsonString(health, "workerActive");
        String mockOutcome = jsonString(health, "mockOutcome");
        String mockDelay = jsonString(health, "mockDelayMs");
        // Included in every failure detail below: when the preflight cannot read
        // the mode, the raw body is the only thing that explains why.
        String hint = " [actuator=" + ACTUATOR + " body=" + snippet(health) + "]";

        check("0.2 ★ AI analysis is enabled on the backend", "true".equals(analysisEnabled),
                "analysisEnabled=" + analysisEnabled
                        + " -> restart with $env:AI_ANALYSIS_ENABLED='true'" + hint);
        check("0.3 ★★ the Worker bean actually exists (workerActive=true)", "true".equals(workerActive),
                "workerActive=" + workerActive
                        + " -> analysisEnabled=true but no Worker means tasks are queued"
                        + " and NEVER executed (silent, no error in the log)" + hint);
        check("0.4 backend is in success mode (mockOutcome=success)", "success".equals(mockOutcome),
                "mockOutcome=" + mockOutcome + " -> run TestAiTaskFailure for failure modes" + hint);
        check("0.5 mock delay is set (mockDelayMs>0) so async behaviour is observable",
                parseLong(mockDelay, -1) > 0,
                "mockDelayMs=" + mockDelay
                        + " -> set $env:AI_MOCK_DELAY_MS='1500'; without it a synchronous"
                        + " implementation would pass this test too" + hint);

        if (!"true".equals(analysisEnabled) || !"success".equals(mockOutcome)) {
            System.out.println("\n  aborting: backend is not in the required mode");
            summary();
            System.exit(1);
        }
        long mockDelayMs = parseLong(mockDelay, 1500L);

        Session a = new Session();
        Session b = new Session();
        Session anon = new Session();

        try {
            // ── 1. users ────────────────────────────────────────
            section("1. test users");
            String ua = RUN.toLowerCase() + "_a";
            String ub = RUN.toLowerCase() + "_b";
            Resp ra = a.post("/auth/register",
                    "{\"username\":\"" + ua + "\",\"password\":\"pw123456\",\"nickname\":\"AI甲\"}");
            check("1.1 register A (201)", ra.status == 201, "HTTP=" + ra.status + " " + ra.shortBody());
            Resp rb = b.post("/auth/register",
                    "{\"username\":\"" + ub + "\",\"password\":\"pw123456\",\"nickname\":\"AI乙\"}");
            check("1.2 register B (201)", rb.status == 201, "HTTP=" + rb.status + " " + rb.shortBody());
            Resp la = a.post("/auth/login",
                    "{\"username\":\"" + ua + "\",\"password\":\"pw123456\"}");
            check("1.3 A logged in", la.status == 200 && la.setCookie != null, "HTTP=" + la.status);
            Resp lb = b.post("/auth/login",
                    "{\"username\":\"" + ub + "\",\"password\":\"pw123456\"}");
            check("1.4 B logged in", lb.status == 200 && lb.setCookie != null, "HTTP=" + lb.status);

            long userA = extractLong(a.get("/auth/me").body, "id");
            long userB = extractLong(b.get("/auth/me").body, "id");
            check("1.5 both user ids resolved", userA > 0 && userB > 0, "A=" + userA + " B=" + userB);

            // ── 2. auth required ────────────────────────────────
            section("2. both AI endpoints require a session");
            Resp n1 = anon.get("/diaries/1/analysis");
            check("2.1 GET /diaries/1/analysis without cookie -> 40101",
                    n1.status == 401 && n1.body.contains("40101"),
                    "HTTP=" + n1.status + " " + n1.shortBody());
            Resp n2 = anon.post("/ai/diaries/1/analyze", "");
            check("2.2 POST /ai/diaries/1/analyze without cookie -> 40101",
                    n2.status == 401 && n2.body.contains("40101"),
                    "HTTP=" + n2.status + " " + n2.shortBody());
            Resp n3 = anon.get("/ai/tasks/1");
            check("2.3 GET /ai/tasks/1 without cookie -> 40101",
                    n3.status == 401 && n3.body.contains("40101"),
                    "HTTP=" + n3.status + " " + n3.shortBody());

            // ── 3. nonexistent diary ────────────────────────────
            section("3. nonexistent diary -> 40401 (not 500, not 200)");
            Resp x1 = a.get("/diaries/999999999/analysis");
            check("3.1 GET analysis of a nonexistent diary -> 40401",
                    x1.status == 404 && x1.body.contains("40401"),
                    "HTTP=" + x1.status + " " + x1.shortBody());
            Resp x2 = a.post("/ai/diaries/999999999/analyze", "");
            check("3.2 POST analyze on a nonexistent diary -> 40401",
                    x2.status == 404 && x2.body.contains("40401"),
                    "HTTP=" + x2.status + " " + x2.shortBody());

            // ── 4. create: must NOT wait for the model ──────────
            section("4. create diary: saved without waiting for the model");
            String content = "今天下午雨停了，去河边走了很久。" + RUN;
            long t0 = System.currentTimeMillis();
            Resp created = a.post("/diaries",
                    "{\"title\":\"AI 分析验收 " + RUN + "\",\"content\":\"" + esc(content) + "\","
                            + "\"mood\":\"平静\",\"weather\":\"阴\",\"location\":\"河边\"}");
            long createMs = System.currentTimeMillis() - t0;

            check("4.1 create -> 201", created.status == 201,
                    "HTTP=" + created.status + " " + created.shortBody());
            long diaryId = extractLong(created.body, "id");
            check("4.2 diary id returned", diaryId > 0, "id=" + diaryId);

            /*
             * ★★ THE core Phase 3 criterion, in its "save must not block" form.
             *
             * The Mock sleeps mockDelayMs (1500 in acceptance) per analysis call.
             * If saving a diary ever called the model synchronously, this
             * round-trip would take >= 1500ms. It must stay well under that.
             */
            check("4.3 ★★ create returned without waiting for the model ("
                            + createMs + "ms < " + (mockDelayMs / 2) + "ms half-delay)",
                    createMs < mockDelayMs / 2,
                    "elapsed=" + createMs + "ms, mockDelayMs=" + mockDelayMs
                            + " -> a synchronous model call would take >= mockDelayMs");

            check("4.4 ★ content echoed back in plaintext (encryption round-trip intact)",
                    created.body.contains(content), "body=" + created.shortBody());

            String immediateStatus = jsonString(created.body, "analysis_status");
            check("4.5 ★ analysis is NOT finished yet in the create response ("
                            + immediateStatus + ")",
                    !"success".equals(immediateStatus),
                    "analysis_status=" + immediateStatus
                            + " -> analysis finished inside the save request = synchronous");

            // ── 5. poll to SUCCESS ──────────────────────────────
            section("5. background worker: PENDING/RUNNING -> SUCCESS");
            Poll poll = waitForTerminal(a, diaryId);

            check("5.1 reached a terminal state within " + (POLL_TIMEOUT_MS / 1000) + "s",
                    poll.terminal, "last=" + poll.lastStatus + " seen=" + poll.seen);
            check("5.2 ★★ final status is success", "success".equals(poll.lastStatus),
                    "status=" + poll.lastStatus + " error=" + poll.lastError);

            /*
             * Observing an intermediate state is what proves the work really
             * happened asynchronously (not inside the create request).
             * It is only reliable because AI_MOCK_DELAY_MS makes the analysis slow.
             */
            check("5.3 ★ an intermediate state (pending/running) was observed",
                    poll.seen.contains("pending") || poll.seen.contains("running"),
                    "seen=" + poll.seen + " -> with a fast mock this may be missed;"
                            + " raise AI_MOCK_DELAY_MS");

            Resp ana = a.get("/diaries/" + diaryId + "/analysis");
            check("5.4 GET /analysis -> 200", ana.status == 200, "HTTP=" + ana.status);
            check("5.5 enabled=true in the analysis payload", ana.body.contains("\"enabled\":true"),
                    "body=" + ana.shortBody());
            check("5.6 can_retry=true after success", ana.body.contains("\"can_retry\":true"),
                    "body=" + ana.shortBody());

            String summary = jsonString(ana.body, "summary");
            check("5.7 summary is non-empty", summary != null && !summary.isBlank(),
                    "summary=" + summary);

            /*
             * ★★ @JsonRawValue contract -- compile-invisible, silent when wrong.
             *
             * Without @JsonRawValue the field would be serialised as an ESCAPED
             * STRING: "emotion":"{\"label\":\"...\"}" and every frontend
             * `analysis.emotion.label` would be undefined with no error anywhere.
             */
            check("5.8 ★★ emotion is a real JSON OBJECT, not an escaped string",
                    Pattern.compile("\"emotion\"\\s*:\\s*\\{").matcher(ana.body).find(),
                    "body=" + ana.shortBody());
            check("5.9 ★★ topics is a real JSON ARRAY", 
                    Pattern.compile("\"topics\"\\s*:\\s*\\[").matcher(ana.body).find(),
                    "body=" + ana.shortBody());
            check("5.10 topics contains the mock marker",
                    ana.body.contains("Mock"), "body=" + ana.shortBody());
            check("5.11 schema_version=1.0", "1.0".equals(jsonString(ana.body, "schema_version")),
                    "schema_version=" + jsonString(ana.body, "schema_version"));
            check("5.12 prompt_version=mock-v1 (proves it came from the Mock)",
                    "mock-v1".equals(jsonString(ana.body, "prompt_version")),
                    "prompt_version=" + jsonString(ana.body, "prompt_version"));
            check("5.13 analysis.created_at carries the Z suffix (UTC contract)",
                    containsZTimestamp(ana.body, "created_at"), "body=" + ana.shortBody());
            check("5.14 status field in the payload is success",
                    "success".equals(jsonString(ana.body, "status")),
                    "status=" + jsonString(ana.body, "status"));

            // ── 6. diary detail mirrors the status ──────────────
            section("6. analysis_status is wired into the diary payloads");
            Resp detail = a.get("/diaries/" + diaryId);
            check("6.1 detail.analysis_status == success",
                    "success".equals(jsonString(detail.body, "analysis_status")),
                    "analysis_status=" + jsonString(detail.body, "analysis_status"));
            check("6.2 detail still returns the decrypted content",
                    detail.body.contains(content), "body=" + detail.shortBody());

            Resp list = a.get("/diaries?keyword=" + RUN);
            check("6.3 list finds the diary", list.body.contains(String.valueOf(diaryId)),
                    "body=" + list.shortBody());
            /*
             * Documented behaviour, not an oversight: the list path does not do
             * an extra ai_task query per row (N+1), and the list page does not
             * show analysis state. Asserted so it stays a decision, not a bug.
             */
            check("6.4 ★ list.analysis_status is null (documented: no N+1 in list)",
                    jsonString(list.body, "analysis_status") == null,
                    "analysis_status=" + jsonString(list.body, "analysis_status"));

            // ── 7. idempotency ──────────────────────────────────
            section("7. re-analysis is idempotent: one task row per diary");
            Resp first = a.post("/ai/diaries/" + diaryId + "/analyze", "");
            check("7.1 POST /analyze -> 200", first.status == 200,
                    "HTTP=" + first.status + " " + first.shortBody());
            long taskId = extractLong(first.body, "id");
            check("7.2 task id returned", taskId > 0, "id=" + taskId);
            check("7.3 max_retries reported from config", jsonString(first.body, "max_retries") != null,
                    "body=" + first.shortBody());

            Resp second = a.post("/ai/diaries/" + diaryId + "/analyze", "");
            check("7.4 second POST /analyze is also 200 (idempotent, not 409)",
                    second.status == 200, "HTTP=" + second.status + " " + second.shortBody());
            check("7.5 ★★ same task id both times", extractLong(second.body, "id") == taskId,
                    "first=" + taskId + " second=" + extractLong(second.body, "id"));

            int taskRows = scalarInt("SELECT COUNT(1) FROM ai_task WHERE user_id = " + userA
                    + " AND source_id = " + diaryId + " AND source_type = 'DIARY'"
                    + " AND task_type = 'DIARY_ANALYZE'");
            check("7.6 ★★ exactly ONE ai_task row in the database", taskRows == 1,
                    "rows=" + taskRows + " -> a duplicate means the idempotency key is broken"
                            + " (double model spend)");

            Poll poll2 = waitForTerminal(a, diaryId);
            check("7.7 ★ re-analysis reached success again", "success".equals(poll2.lastStatus),
                    "status=" + poll2.lastStatus + " seen=" + poll2.seen);

            // ── 8. task endpoint ────────────────────────────────
            section("8. GET /api/ai/tasks/{id}");
            Resp task = a.get("/ai/tasks/" + taskId);
            check("8.1 task -> 200", task.status == 200, "HTTP=" + task.status + " " + task.shortBody());
            check("8.2 returned id matches", extractLong(task.body, "id") == taskId,
                    "body=" + task.shortBody());
            check("8.3 status is an external value (pending/running/success/failed/cancelled)",
                    isExternalStatus(jsonString(task.body, "status")),
                    "status=" + jsonString(task.body, "status")
                            + " -> internal enum names must not leak to the API");
            check("8.4 retry_count=0 after a successful run",
                    "0".equals(jsonString(task.body, "retry_count")),
                    "retry_count=" + jsonString(task.body, "retry_count"));
            check("8.5 error_code is null after success",
                    jsonString(task.body, "error_code") == null,
                    "error_code=" + jsonString(task.body, "error_code"));
            check("8.6 created_at carries Z", containsZTimestamp(task.body, "created_at"),
                    "body=" + task.shortBody());

            // ── 9. cross-user isolation ─────────────────────────
            section("9. cross-user: B must not reach A's analysis");
            Resp c1 = b.get("/diaries/" + diaryId + "/analysis");
            check("9.1 ★ B GET A's analysis -> 40401",
                    c1.status == 404 && c1.body.contains("40401"),
                    "HTTP=" + c1.status + " " + c1.shortBody());
            Resp c2 = b.post("/ai/diaries/" + diaryId + "/analyze", "");
            check("9.2 ★ B POST analyze on A's diary -> 40401 (cannot burn A's quota)",
                    c2.status == 404 && c2.body.contains("40401"),
                    "HTTP=" + c2.status + " " + c2.shortBody());
            Resp c3 = b.get("/ai/tasks/" + taskId);
            check("9.3 ★ B GET A's task -> 40401", c3.status == 404 && c3.body.contains("40401"),
                    "HTTP=" + c3.status + " " + c3.shortBody());

            // ── 10. B's own diary works (control group) ─────────
            section("10. control group: B's own diary analyses fine");
            String bContent = "B 的日记内容 " + RUN;
            Resp bCreated = b.post("/diaries",
                    "{\"title\":\"B 的日记 " + RUN + "\",\"content\":\"" + esc(bContent) + "\"}");
            check("10.1 B created a diary", bCreated.status == 201, "HTTP=" + bCreated.status);
            long bDiaryId = extractLong(bCreated.body, "id");
            Poll bPoll = waitForTerminal(b, bDiaryId);
            check("10.2 ★ B's diary reached success",
                    "success".equals(bPoll.lastStatus), "status=" + bPoll.lastStatus);
            Resp bAna = b.get("/diaries/" + bDiaryId + "/analysis");
            check("10.3 B reads B's analysis -> 200", bAna.status == 200, "HTTP=" + bAna.status);
            check("10.4 B still cannot read A's analysis -> 40401",
                    b.get("/diaries/" + diaryId + "/analysis").status == 404, "");
            check("10.5 A cannot read B's analysis -> 40401",
                    a.get("/diaries/" + bDiaryId + "/analysis").status == 404, "");

            // ── 11. database invariants ─────────────────────────
            section("11. database invariants");
            check("11.1 ai_task.status stored as the NAME 'SUCCESS', not an ordinal",
                    "SUCCESS".equals(rawColumn("ai_task", "status", taskId)),
                    "raw=" + rawColumn("ai_task", "status", taskId)
                            + " -> an ordinal here means the enum TypeHandler regressed");
            check("11.2 ai_task.source_type = 'DIARY'",
                    "DIARY".equals(rawColumn("ai_task", "source_type", taskId)),
                    "raw=" + rawColumn("ai_task", "source_type", taskId));
            check("11.3 ai_task.task_type = 'DIARY_ANALYZE'",
                    "DIARY_ANALYZE".equals(rawColumn("ai_task", "task_type", taskId)),
                    "raw=" + rawColumn("ai_task", "task_type", taskId));
            check("11.4 ★ idempotency_key format = DIARY:<diaryId>:DIARY_ANALYZE:<userId>:1",
                    ("DIARY:" + diaryId + ":DIARY_ANALYZE:" + userA + ":1")
                            .equals(rawColumn("ai_task", "idempotency_key", taskId)),
                    "raw=" + rawColumn("ai_task", "idempotency_key", taskId));
            check("11.5 error columns cleared after success",
                    rawColumn("ai_task", "error_code", taskId) == null,
                    "error_code=" + rawColumn("ai_task", "error_code", taskId));

            int anaRows = scalarInt("SELECT COUNT(1) FROM diary_analysis WHERE diary_id = " + diaryId);
            check("11.6 exactly one diary_analysis row (unique diary_id)", anaRows == 1,
                    "rows=" + anaRows);
            check("11.7 emotion_json persisted as parseable JSON text",
                    rawColumn("diary_analysis", "emotion_json", analysisRowId(diaryId))
                            .startsWith("{"),
                    "raw=" + rawColumn("diary_analysis", "emotion_json", analysisRowId(diaryId)));
            check("11.8 diary_analysis.user_id matches the owner (redundant column kept in sync)",
                    String.valueOf(userA)
                            .equals(rawColumn("diary_analysis", "user_id", analysisRowId(diaryId))),
                    "raw=" + rawColumn("diary_analysis", "user_id", analysisRowId(diaryId)));

        } finally {
            // ── 12. cleanup (runs even if an assertion above threw) ──
            section("12. cleanup (this test removes what it created)");
            int[] n = cleanup(RUN.toLowerCase() + "_a", RUN.toLowerCase() + "_b");
            check("12.1 test users and all their rows removed",
                    n[0] > 0, "users=" + n[0] + " diary=" + n[1] + " tag=" + n[2]
                            + " diary_tag=" + n[3] + " ai_task=" + n[4] + " analysis=" + n[5]);
        }

        summary();
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ══════════════════════════════════════════════════════════
    // polling
    // ══════════════════════════════════════════════════════════

    /** Result of polling one diary's analysis endpoint. */
    static class Poll {
        boolean terminal;
        String lastStatus;
        String lastError;
        final Set<String> seen = new LinkedHashSet<>();
    }

    /**
     * Poll {@code /diaries/{id}/analysis} until a terminal state.
     *
     * <p>Stops as soon as the status is terminal -- anything else would keep
     * hammering the backend (the same mistake the frontend has to avoid).
     */
    private static Poll waitForTerminal(Session s, long diaryId) throws Exception {
        Poll p = new Poll();
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Resp r = s.get("/diaries/" + diaryId + "/analysis");
            if (r.status != 200) {
                p.lastStatus = "HTTP " + r.status;
                return p;
            }
            String status = jsonString(r.body, "status");
            p.lastStatus = status;
            p.lastError = jsonString(r.body, "error_message");
            if (status != null) {
                p.seen.add(status);
            }
            if (status == null || "success".equals(status)
                    || "failed".equals(status) || "cancelled".equals(status)) {
                p.terminal = true;
                return p;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return p;
    }

    private static boolean isExternalStatus(String s) {
        return "pending".equals(s) || "running".equals(s) || "success".equals(s)
                || "failed".equals(s) || "cancelled".equals(s);
    }

    // ══════════════════════════════════════════════════════════
    // cleanup
    // ══════════════════════════════════════════════════════════

    /**
     * Deletes the two test users and every row that belongs to them.
     *
     * <p>⚠️ Scoped strictly to the usernames generated by this run --
     * there is no {@code DELETE FROM user} without a WHERE, and no reliance on
     * "the newest rows". The dev database holds the author's real accounts.
     *
     * @return counts: users, diaries, tags, diary_tags, tasks, analyses
     */
    private static int[] cleanup(String ua, String ub) throws Exception {
        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             Statement st = c.createStatement()) {
            String ids = "SELECT id FROM `user` WHERE username IN ('" + ua + "','" + ub + "')";
            StringBuilder idList = new StringBuilder();
            try (ResultSet r = st.executeQuery(ids)) {
                while (r.next()) {
                    if (idList.length() > 0) {
                        idList.append(',');
                    }
                    idList.append(r.getLong(1));
                }
            }
            if (idList.length() == 0) {
                return new int[]{0, 0, 0, 0, 0, 0};
            }
            String in = idList.toString();
            int users = idList.toString().split(",").length;

            int diaryTags = st.executeUpdate("DELETE FROM diary_tag WHERE diary_id IN "
                    + "(SELECT id FROM diary WHERE user_id IN (" + in + "))");
            int analyses = st.executeUpdate(
                    "DELETE FROM diary_analysis WHERE user_id IN (" + in + ")");
            int tasks = st.executeUpdate("DELETE FROM ai_task WHERE user_id IN (" + in + ")");
            int diaries = st.executeUpdate("DELETE FROM diary WHERE user_id IN (" + in + ")");
            int tags = st.executeUpdate("DELETE FROM tag WHERE user_id IN (" + in + ")");
            st.executeUpdate("DELETE FROM `user` WHERE id IN (" + in + ")");
            return new int[]{users, diaries, tags, diaryTags, tasks, analyses};
        }
    }

    /** id of the single diary_analysis row for a diary (-1 when absent). */
    private static long analysisRowId(long diaryId) throws Exception {
        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             Statement st = c.createStatement();
             ResultSet r = st.executeQuery(
                     "SELECT id FROM diary_analysis WHERE diary_id = " + diaryId)) {
            return r.next() ? r.getLong(1) : -1L;
        }
    }

    private static String rawColumn(String table, String column, long id) {
        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             Statement st = c.createStatement();
             ResultSet r = st.executeQuery(
                     "SELECT " + column + " FROM " + table + " WHERE id = " + id)) {
            if (!r.next()) {
                return null;
            }
            String v = r.getString(1);
            // getString() returns null for SQL NULL; distinguish it from "no row"
            return r.wasNull() ? null : v;
        } catch (Exception e) {
            return "(error: " + e.getMessage() + ")";
        }
    }

    private static int scalarInt(String sql) {
        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             Statement st = c.createStatement();
             ResultSet r = st.executeQuery(sql)) {
            r.next();
            return r.getInt(1);
        } catch (Exception e) {
            return -1;
        }
    }

    // ══════════════════════════════════════════════════════════
    // HTTP plumbing
    // ══════════════════════════════════════════════════════════

    /** One independent browser session (its own cookie jar). */
    static class Session {
        private final HttpClient client;
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

        Session() {
            this.client = HttpClient.newBuilder()
                    .cookieHandler(cookies)
                    .version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        }

        Resp get(String path) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(BASE + path))
                    .header("Accept", "application/json").GET().build());
        }

        Resp post(String path, String json) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(BASE + path))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build());
        }

        private Resp send(HttpRequest request) throws Exception {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Resp(response.statusCode(), response.body(),
                    response.headers().firstValue("Set-Cookie").orElse(null));
        }
    }

    record Resp(int status, String body, String setCookie) {
        String shortBody() {
            String b = body == null ? "" : body;
            return b.length() > 260 ? b.substring(0, 260) + "..." : b;
        }
    }

    private static boolean healthOk() {
        try {
            HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            HttpResponse<String> r = c.send(
                    HttpRequest.newBuilder(URI.create(BASE + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static String httpGet(String url) {
        try {
            HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            return c.send(HttpRequest.newBuilder(URI.create(url))
                            /*
                             * ⚠️ The Accept header is REQUIRED here.
                             *
                             * Spring Boot's actuator negotiates on
                             * application/vnd.spring-boot.actuator.v3+json.
                             * Java's HttpClient sends no Accept header at all
                             * (PowerShell's Invoke-WebRequest does, which is why
                             * this worked by hand and failed in the test) --
                             * without it the endpoint does not answer with the
                             * JSON body we want, and every field reads back null,
                             * looking exactly like "the backend is misconfigured".
                             */
                            .header("Accept", "application/json")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
        } catch (Exception e) {
            return "";
        }
    }

    // ══════════════════════════════════════════════════════════
    // tiny JSON readers (regex is enough for a flat, known payload)
    // ══════════════════════════════════════════════════════════

    /**
     * Reads a JSON field as text.
     *
     * <p>Returns {@code null} both for a missing key and for a literal JSON
     * {@code null} -- which is exactly what most assertions here want to know.
     */
    private static String jsonString(String json, String field) {
        if (json == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(\"([^\"]*)\"|null|true|false|-?\\d+)")
                .matcher(json);
        if (!m.find()) {
            return null;
        }
        // A quoted value is in group 2 (no quotes). Everything else lands in
        // group 1 -- and the literal token `null` must be normalised to Java
        // null, otherwise `"error_code":null` would read as the STRING "null"
        // and every "is it null?" assertion would silently invert.
        if (m.group(2) != null) {
            return m.group(2);
        }
        return "null".equals(m.group(1)) ? null : m.group(1);
    }

    private static long extractLong(String json, String field) {
        String v = jsonString(json, field);
        return parseLong(v, -1L);
    }

    private static long parseLong(String v, long fallback) {
        try {
            return Long.parseLong(v.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Short one-line view of a response body for failure messages. */
    private static String snippet(String body) {
        if (body == null || body.isEmpty()) {
            return "(empty)";
        }
        return body.length() > 160 ? body.substring(0, 160) + "..." : body;
    }

    private static boolean containsZTimestamp(String json, String field) {
        return Pattern.compile("\"" + field + "\"\\s*:\\s*\"[^\"]*Z\"").matcher(json).find();
    }

    /** Escapes a string for embedding in a hand-built JSON body. */
    private static String esc(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ══════════════════════════════════════════════════════════
    // reporting
    // ══════════════════════════════════════════════════════════

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

    private static void summary() {
        System.out.println();
        System.out.println("==================================================");
        System.out.println("通过: " + passed + "  失败: " + failed);
        System.out.println("==================================================");
    }
}
