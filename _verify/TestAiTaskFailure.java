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
 * Phase 3 acceptance: the model FAILS, the diary still saves
 * ============================================================
 *
 * <h2>This is THE Phase 3 acceptance criterion</h2>
 *
 * <blockquote>
 * 模型超时/报错时<b>正文照样保存成功</b>，任务标 FAILED 可重试。
 * </blockquote>
 *
 * <p>Verifying it needs a failing model, which does not exist without an API
 * key. So the Mock is put into a deliberate failure mode through
 * {@code AI_MOCK_OUTCOME}:
 *
 * <table border="1">
 *   <caption>modes accepted as args[0]</caption>
 *   <tr><th>mode</th><th>Mock throws</th><th>classified as</th><th>retried?</th></tr>
 *   <tr><td>{@code timeout}</td>
 *       <td>RuntimeException wrapping SocketTimeoutException</td>
 *       <td>{@code AI_TIMEOUT}</td><td>yes, up to maxRetries</td></tr>
 *   <tr><td>{@code invalid-json}</td><td>MockJsonParseException</td>
 *       <td>{@code JSON_INVALID}</td><td>no (4xx-like, retrying is waste)</td></tr>
 * </table>
 *
 * <p>Usage: {@code java TestAiTaskFailure timeout}
 *
 * <h2>What is asserted</h2>
 * <ol>
 *   <li>POST /api/diaries -> <b>201</b>, content intact, fast (no waiting on the model)</li>
 *   <li>the diary stays readable afterwards, byte for byte</li>
 *   <li>the task ends in FAILED with the right {@code error_code}</li>
 *   <li>retry policy: retryable -> maxRetries attempts; not retryable -> 1 attempt</li>
 *   <li>{@code can_retry=true} and "重新分析" really re-runs the task
 *       (proved by {@code started_at} changing, not just by a status string)</li>
 *   <li>the error message is sanitised and within the column width</li>
 * </ol>
 */
public class TestAiTaskFailure {

    /** Override with {@code -Dai.base=http://localhost:8081} (see TestAiTaskApi). */
    private static final String ROOT = System.getProperty("ai.base", "http://localhost:8080");
    private static final String BASE = ROOT + "/api";
    private static final String ACTUATOR = ROOT + "/actuator/health";

    private static final String DB_URL = "jdbc:mysql://localhost:3307/ai_diary"
            + "?useUnicode=true&characterEncoding=UTF-8"
            + "&connectionCollation=utf8mb4_0900_ai_ci"
            + "&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
    private static final String DB_USER = "ai_diary";
    private static final String DB_PASS = "ai_diary_dev_pw";

    private static final String RUN = "ZZAIF" + (System.currentTimeMillis() % 100000);
    private static final long POLL_TIMEOUT_MS = 60_000L;
    private static final long POLL_INTERVAL_MS = 250L;

    /** Column width of ai_task.error_message -- anything longer would fail the write. */
    private static final int ERROR_MESSAGE_MAX = 500;

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("========== Phase 3 acceptance: model failure ==========");

        String mode = args.length > 0 ? args[0] : "";
        if (!"timeout".equals(mode) && !"invalid-json".equals(mode)) {
            System.out.println("  usage: java TestAiTaskFailure <timeout|invalid-json>");
            System.exit(2);
        }
        String expectedCode = "timeout".equals(mode) ? "AI_TIMEOUT" : "JSON_INVALID";
        boolean expectRetry = "timeout".equals(mode);
        System.out.println("mode            : " + mode);
        System.out.println("expected code   : " + expectedCode);
        System.out.println("retry expected  : " + expectRetry);

        // ── 0. preflight ────────────────────────────────────────
        section("0. preflight: the backend must really be in '" + mode + "' mode");
        if (!healthOk()) {
            System.out.println("  [FAIL] backend not reachable at " + BASE);
            System.exit(1);
        }
        String health = httpGet(ACTUATOR);
        String analysisEnabled = jsonString(health, "analysisEnabled");
        String workerActive = jsonString(health, "workerActive");
        String mockOutcome = jsonString(health, "mockOutcome");
        String maxRetriesRaw = jsonString(health, "maxRetries");

        check("0.1 AI analysis enabled", "true".equals(analysisEnabled),
                "analysisEnabled=" + analysisEnabled);
        check("0.2 ★ Worker bean exists", "true".equals(workerActive),
                "workerActive=" + workerActive + " -> tasks would never execute");
        /*
         * ⚠️ This check is the whole reason the mode is read from the backend
         * instead of being assumed: if the backend still runs in success mode,
         * every assertion below would "fail" while the code is perfectly fine.
         * Fail here, with the exact command to fix it, and stop.
         */
        check("0.3 ★★ backend is in '" + mode + "' mode", mode.equals(mockOutcome),
                "mockOutcome=" + mockOutcome + " -> restart the backend with:"
                        + "  $env:AI_MOCK_OUTCOME='" + mode + "'; mvn -o spring-boot:run");
        if (!"true".equals(analysisEnabled) || !mode.equals(mockOutcome)) {
            System.out.println("\n  aborting: backend is not in the required mode");
            summary();
            System.exit(1);
        }
        int maxRetries = (int) parseLong(maxRetriesRaw, 3L);

        Session s = new Session();
        try {
            // ── 1. user ─────────────────────────────────────────
            section("1. test user");
            String u = RUN.toLowerCase() + "_a";
            Resp reg = s.post("/auth/register",
                    "{\"username\":\"" + u + "\",\"password\":\"pw123456\",\"nickname\":\"失败验收\"}");
            check("1.1 register (201)", reg.status == 201, "HTTP=" + reg.status + " " + reg.shortBody());
            Resp login = s.post("/auth/login",
                    "{\"username\":\"" + u + "\",\"password\":\"pw123456\"}");
            check("1.2 login", login.status == 200 && login.setCookie != null, "HTTP=" + login.status);
            long userId = extractLong(s.get("/auth/me").body, "id");
            check("1.3 user id resolved", userId > 0, "id=" + userId);

            // ── 2. the diary must save anyway ───────────────────
            section("2. ★★ THE criterion: the model fails, the diary is still saved");
            String content = "这段话必须在模型失败之后仍然完整存在。" + RUN;
            long t0 = System.currentTimeMillis();
            Resp created = s.post("/diaries",
                    "{\"title\":\"模型失败验收 " + RUN + "\",\"content\":\"" + esc(content) + "\","
                            + "\"mood\":\"平静\",\"location\":\"书房\"}");
            long createMs = System.currentTimeMillis() - t0;

            check("2.1 ★★ POST /diaries -> 201 even though the model will fail",
                    created.status == 201, "HTTP=" + created.status + " " + created.shortBody());
            long diaryId = extractLong(created.body, "id");
            check("2.2 diary id returned", diaryId > 0, "id=" + diaryId);
            check("2.3 ★★ content echoed back intact", created.body.contains(content),
                    "body=" + created.shortBody());
            check("2.4 create did not block on the failing model (" + createMs + "ms)",
                    createMs < 5000, "elapsed=" + createMs + "ms");

            // ── 3. the task must fail (not succeed, not hang) ────
            section("3. the background task ends in FAILED");
            Poll p = waitForTerminal(s, diaryId);
            check("3.1 reached a terminal state within " + (POLL_TIMEOUT_MS / 1000) + "s",
                    p.terminal, "last=" + p.lastStatus + " seen=" + p.seen);
            check("3.2 ★★ status is 'failed' (not 'success', not stuck in running)",
                    "failed".equals(p.lastStatus),
                    "status=" + p.lastStatus + " error=" + p.lastError);
            check("3.3 ★★ error_code=" + expectedCode,
                    expectedCode.equals(jsonString(p.lastBody, "error_code")),
                    "error_code=" + jsonString(p.lastBody, "error_code")
                            + " -> a wrong code means the classifier mapped the failure wrong");

            Resp ana = s.get("/diaries/" + diaryId + "/analysis");
            check("3.4 can_retry=true on failure (the user can retry)", ana.body.contains("\"can_retry\":true"),
                    "body=" + ana.shortBody());
            check("3.5 analysis payload has no result (analysis=null)",
                    jsonString(ana.body, "summary") == null, "body=" + ana.shortBody());

            String errMsg = jsonString(ana.body, "error_message");
            check("3.6 error_message is present and human-readable",
                    errMsg != null && !errMsg.isBlank(), "error_message=" + errMsg);
            check("3.7 ★ error_message is within the " + ERROR_MESSAGE_MAX + "-char column width",
                    errMsg != null && errMsg.length() <= ERROR_MESSAGE_MAX,
                    "length=" + (errMsg == null ? -1 : errMsg.length())
                            + " -> an over-long message makes the write itself fail,"
                            + " leaving the task stuck in RUNNING forever");
            check("3.8 ★ error_message carries no raw stack trace (sanitised to type + message)",
                    errMsg != null && !errMsg.contains("\tat ") && !errMsg.contains("Exception in thread"),
                    "error_message=" + errMsg);

            // ── 4. retry policy ─────────────────────────────────
            section("4. retry policy (retryable => maxRetries attempts)");
            int retryCount = (int) parseLong(jsonString(ana.body, "retry_count"), -1L);
            if (expectRetry) {
                check("4.1 ★★ retryable failure was retried up to maxRetries ("
                                + retryCount + "/" + maxRetries + ")",
                        retryCount == maxRetries,
                        "retry_count=" + retryCount + " max_retries=" + maxRetries
                                + " -> the exponential-backoff path did not run to exhaustion");
                check("4.2 an intermediate 'pending' state was observed (RETRYING maps to pending)",
                        p.seen.contains("pending") || p.seen.contains("running"),
                        "seen=" + p.seen);
            } else {
                check("4.1 ★★ non-retryable failure was NOT retried (retry_count=" + retryCount + ")",
                        retryCount == 1,
                        "retry_count=" + retryCount
                                + " -> retrying a 4xx/JSON error burns quota for nothing");
                check("4.2 only one attempt was made (max_retries=" + maxRetries + " unused)",
                        retryCount < maxRetries, "retry_count=" + retryCount);
            }

            // ── 5. the diary survived the failure ───────────────
            section("5. ★★ the diary is intact after the failed analysis");
            Resp detail = s.get("/diaries/" + diaryId);
            check("5.1 detail -> 200", detail.status == 200, "HTTP=" + detail.status);
            check("5.2 ★★ content is byte-for-byte the same", detail.body.contains(content),
                    "body=" + detail.shortBody());
            check("5.3 mood/location survived too",
                    detail.body.contains("平静") && detail.body.contains("书房"),
                    "body=" + detail.shortBody());
            check("5.4 detail.analysis_status == failed",
                    "failed".equals(jsonString(detail.body, "analysis_status")),
                    "analysis_status=" + jsonString(detail.body, "analysis_status"));
            Resp list = s.get("/diaries?keyword=" + RUN);
            check("5.5 the diary is still listed", list.body.contains(String.valueOf(diaryId)),
                    "body=" + list.shortBody());

            // ── 6. manual retry really re-runs the task ─────────
            section("6. 'retry' button path: POST /api/ai/diaries/{id}/analyze");
            long taskId = 0;
            Resp tasksBefore = s.get("/diaries/" + diaryId + "/analysis");
            check("6.0 task is discoverable before retry", tasksBefore.status == 200, "");

            /*
             * !! startedBefore MUST be captured HERE, before the POST below !!
             *
             * POST /analyze re-arms the task via AiTaskMapper.resetForRetry, which
             * deliberately sets started_at = NULL / finished_at = NULL so the task
             * looks brand new (see the comment on that statement). Reading
             * started_at AFTER the POST therefore always yields null, and the 6.6
             * comparison below would compare against null -- a false failure whose
             * symptom ("the retry never ran") points at entirely the wrong place.
             *
             * This is not hypothetical: on the very first run of this file, 6.3
             * failed with started_at=null while 6.6 passed, which is self-
             * contradictory and was the clue that the read was simply too late.
             *
             * The analysis payload does not expose the task id, so it comes from SQL.
             */
            long taskIdBefore = scalarInt("SELECT id FROM ai_task WHERE user_id = " + userId
                    + " AND source_id = " + diaryId
                    + " AND source_type = 'DIARY' AND task_type = 'DIARY_ANALYZE'");
            String startedBefore = jsonString(s.get("/ai/tasks/" + taskIdBefore).body, "started_at");

            /*
             * !! Wait past a second boundary before re-arming the task !!
             *
             * started_at is a plain DATETIME, i.e. SECOND precision (see
             * V2__ai_analysis.sql), so a retry that is claimed within the same
             * second as the first attempt stores a byte-identical timestamp and
             * 6.6 fails even though the task genuinely re-ran.
             *
             * Not hypothetical: in invalid-json mode the mock fails instantly with
             * no backoff, so the first attempt and the retry both landed on
             * 2026-09-25T05:56:27Z and 6.6 failed while every other assertion in
             * this section passed. timeout mode masked the flaw only because its
             * three backoff rounds happen to span several seconds.
             *
             * Same class of trap as the "created_at and updated_at landing in the
             * same second" case already recorded in the project docs (11.3.9).
             */
            Thread.sleep(1100L);

            Resp retry = s.post("/ai/diaries/" + diaryId + "/analyze", "");
            check("6.1 POST /analyze -> 200 (AI is enabled, diary is ours)",
                    retry.status == 200, "HTTP=" + retry.status + " " + retry.shortBody());
            taskId = extractLong(retry.body, "id");
            check("6.2 task id returned", taskId > 0, "id=" + taskId);
            check("6.3 task has a started_at from the first attempt (read BEFORE the reset)",
                    startedBefore != null,
                    "started_at=" + startedBefore + " taskId=" + taskIdBefore
                            + " -> null means the first attempt never claimed the task");

            Resp retry2 = s.post("/ai/diaries/" + diaryId + "/analyze", "");
            check("6.4 double-click is idempotent (still 200, same task id)",
                    retry2.status == 200 && extractLong(retry2.body, "id") == taskId,
                    "HTTP=" + retry2.status + " id=" + extractLong(retry2.body, "id"));

            Poll p2 = waitForTerminal(s, diaryId);
            check("6.5 the retried task failed again (same deterministic failure)",
                    "failed".equals(p2.lastStatus), "status=" + p2.lastStatus + " seen=" + p2.seen);

            Resp taskAfter = s.get("/ai/tasks/" + taskId);
            String startedAfter = jsonString(taskAfter.body, "started_at");
            /*
             * ★★ Why started_at instead of "status went back to pending":
             * the worker picks the task up within milliseconds, so a test that
             * sampled the status too late could never see 'pending' and would
             * wrongly report "retry did nothing". started_at is written on every
             * claim, so a changed value proves a NEW execution really happened.
             */
            check("6.6 ★★ started_at changed -> the task really re-ran",
                    startedAfter != null && !startedAfter.equals(startedBefore),
                    "before=" + startedBefore + " after=" + startedAfter);
            check("6.7 still exactly one task row after two retries",
                    scalarInt("SELECT COUNT(1) FROM ai_task WHERE user_id = " + userId
                            + " AND source_id = " + diaryId) == 1,
                    "rows=" + scalarInt("SELECT COUNT(1) FROM ai_task WHERE user_id = " + userId
                            + " AND source_id = " + diaryId));
            check("6.8 error_code is still " + expectedCode + " after the retry",
                    expectedCode.equals(jsonString(taskAfter.body, "error_code")),
                    "error_code=" + jsonString(taskAfter.body, "error_code"));

            // ── 7. database state ───────────────────────────────
            section("7. database state");
            check("7.1 ai_task.status = 'FAILED'",
                    "FAILED".equals(rawColumn("ai_task", "status", taskId)),
                    "raw=" + rawColumn("ai_task", "status", taskId));
            check("7.2 ai_task.error_code = " + expectedCode,
                    expectedCode.equals(rawColumn("ai_task", "error_code", taskId)),
                    "raw=" + rawColumn("ai_task", "error_code", taskId));
            check("7.3 finished_at was written (a failed task is not left open)",
                    rawColumn("ai_task", "finished_at", taskId) != null,
                    "finished_at=" + rawColumn("ai_task", "finished_at", taskId));
            check("7.4 ★ no diary_analysis row was written for a failed analysis",
                    scalarInt("SELECT COUNT(1) FROM diary_analysis WHERE diary_id = " + diaryId) == 0,
                    "rows=" + scalarInt("SELECT COUNT(1) FROM diary_analysis WHERE diary_id = "
                            + diaryId) + " -> a partial write would leave a half-analysed diary");

        } finally {
            section("8. cleanup (this test removes what it created)");
            int[] n = cleanup(RUN.toLowerCase() + "_a");
            check("8.1 test user and all rows removed", n[0] > 0,
                    "users=" + n[0] + " diary=" + n[1] + " ai_task=" + n[2] + " analysis=" + n[3]);
        }

        summary();
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ══════════════════════════════════════════════════════════
    // polling
    // ══════════════════════════════════════════════════════════

    static class Poll {
        boolean terminal;
        String lastStatus;
        String lastError;
        String lastBody;
        final Set<String> seen = new LinkedHashSet<>();
    }

    private static Poll waitForTerminal(Session s, long diaryId) throws Exception {
        Poll p = new Poll();
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Resp r = s.get("/diaries/" + diaryId + "/analysis");
            if (r.status != 200) {
                p.lastStatus = "HTTP " + r.status;
                return p;
            }
            p.lastBody = r.body;
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

    // ══════════════════════════════════════════════════════════
    // database helpers
    // ══════════════════════════════════════════════════════════

    /** @return counts: users, diaries, tasks, analyses */
    private static int[] cleanup(String u) throws Exception {
        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             Statement st = c.createStatement()) {
            StringBuilder ids = new StringBuilder();
            try (ResultSet r = st.executeQuery(
                    "SELECT id FROM `user` WHERE username = '" + u + "'")) {
                while (r.next()) {
                    if (ids.length() > 0) {
                        ids.append(',');
                    }
                    ids.append(r.getLong(1));
                }
            }
            if (ids.length() == 0) {
                return new int[]{0, 0, 0, 0};
            }
            String in = ids.toString();
            int users = in.split(",").length;
            st.executeUpdate("DELETE FROM diary_tag WHERE diary_id IN "
                    + "(SELECT id FROM diary WHERE user_id IN (" + in + "))");
            int analyses = st.executeUpdate(
                    "DELETE FROM diary_analysis WHERE user_id IN (" + in + ")");
            int tasks = st.executeUpdate("DELETE FROM ai_task WHERE user_id IN (" + in + ")");
            int diaries = st.executeUpdate("DELETE FROM diary WHERE user_id IN (" + in + ")");
            st.executeUpdate("DELETE FROM tag WHERE user_id IN (" + in + ")");
            st.executeUpdate("DELETE FROM `user` WHERE id IN (" + in + ")");
            return new int[]{users, diaries, tasks, analyses};
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
            return b.length() > 300 ? b.substring(0, 300) + "..." : b;
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
                            // ⚠️ Required: actuator negotiates on its own media type,
                            // and HttpClient sends no Accept header by default.
                            .header("Accept", "application/json")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
        } catch (Exception e) {
            return "";
        }
    }

    // ══════════════════════════════════════════════════════════
    // tiny JSON readers
    // ══════════════════════════════════════════════════════════

    private static String jsonString(String json, String field) {
        if (json == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(\"([^\"]*)\"|null|true|false|-?\\d+)")
                .matcher(json);
        if (!m.find()) {
            return null;
        }
        if (m.group(2) != null) {
            return m.group(2);
        }
        // literal `null` must become Java null, not the string "null"
        return "null".equals(m.group(1)) ? null : m.group(1);
    }

    private static long extractLong(String json, String field) {
        return parseLong(jsonString(json, field), -1L);
    }

    private static long parseLong(String v, long fallback) {
        try {
            return Long.parseLong(v.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String esc(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ══════════════════════════════════════════════════════════
    // reporting
    // ══════════════════════════════════════════════════════════

    private static void section(String t) {
        System.out.println("\n-- " + t + " --");
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
