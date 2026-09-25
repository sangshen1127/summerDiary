import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 模块 1-4 授权矩阵验收。
 *
 * 核心问题：每个接口在「不带会话」时的行为是否符合设计？
 * 以及 401 响应是否走统一响应体（而不是 Spring 默认的 HTML 错误页）。
 *
 * ⚠️ 本测试只覆盖「未认证」这一维。
 *    「已认证但越权」的跨用户测试需要 diary / tag 等资源接口，
 *    属于 Phase 2 的验收内容 —— 现在没有资源可跨。
 */
public class TestAuthorizationMatrix {

    private static final String HOST = "http://localhost:8080";
    private static int passed = 0;
    private static int failed = 0;

    /** 未登录的客户端（不保留任何 Cookie） */
    private static HttpClient anon() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 模拟浏览器（自动保存 Cookie） */
    private static HttpClient browser() {
        CookieManager cm = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .cookieHandler(cm)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("══════════ 模块 1-4 授权矩阵验收 ══════════\n");

        // ══════════════════════════════════════════════════════════
        // A. 白名单接口：未登录也必须可用
        // ══════════════════════════════════════════════════════════
        System.out.println("── A. 白名单接口（未登录应可访问）──");

        expect("A1. GET /api/health 免登录", 200, 0,
                get(anon(), "/api/health"));

        expect("A2. GET /actuator/health 免登录", 200, -1,  // -1 = 不校验业务码（actuator 不走统一响应体）
                get(anon(), "/actuator/health"));

        // 登录接口本身可访问（参数为空时是 400 参数错误，证明"能进来"而不是 401）
        expect("A3. POST /api/auth/login 免登录（返参数错误而非401）", 400, 40001,
                post(anon(), "/api/auth/login", "{}"));

        expect("A4. POST /api/auth/register 免登录（返参数错误而非401）", 400, 40001,
                post(anon(), "/api/auth/register", "{}"));

        expect("A5. POST /api/auth/logout 免登录（幂等，返成功）", 200, 0,
                post(anon(), "/api/auth/logout", ""));

        // ══════════════════════════════════════════════════════════
        // B. 受保护接口：未登录必须 401 + 40101
        // ══════════════════════════════════════════════════════════
        System.out.println("\n── B. 受保护接口（未登录应 401/40101）──");

        HttpResponse<String> meAnon = get(anon(), "/api/auth/me");
        expect("B1. GET /api/auth/me 未登录 → 401/40101", 401, 40101, meAnon);
        System.out.println("         " + meAnon.body());

        // 断言响应体是统一结构，不是 Spring 默认的 HTML 错误页
        assertUnifiedBody("B2. 401 响应体是统一结构（非 HTML）", meAnon.body());

        // ══════════════════════════════════════════════════════════
        // C. 会话有效时受保护接口可用
        // ══════════════════════════════════════════════════════════
        System.out.println("\n── C. 有会话时（应 200）──");

        String user = "authz_" + System.currentTimeMillis() % 100000;
        post(anon(), "/api/auth/register",
                "{\"username\":\"" + user + "\",\"password\":\"abc12345\"}");

        HttpClient loggedIn = browser();
        HttpResponse<String> login = post(loggedIn, "/api/auth/login",
                "{\"username\":\"" + user + "\",\"password\":\"abc12345\"}");
        expect("C1. 登录成功", 200, 0, login);

        expect("C2. 有会话访问 /api/auth/me → 200", 200, 0,
                get(loggedIn, "/api/auth/me"));

        // ══════════════════════════════════════════════════════════
        // D. 不存在的路径：不应泄露信息
        // ══════════════════════════════════════════════════════════
        System.out.println("\n── D. 不存在的路径 ──");

        HttpResponse<String> notFoundAnon = get(anon(), "/api/diaries-not-exist");
        HttpResponse<String> notFoundLogged = get(loggedIn, "/api/diaries-not-exist");

        System.out.println("         未登录: HTTP=" + notFoundAnon.statusCode()
                + " " + notFoundAnon.body());
        System.out.println("         已登录: HTTP=" + notFoundLogged.statusCode()
                + " " + notFoundLogged.body());

        // 关键断言：无论是否登录，不存在的路径响应必须<b>一致</b> ——
        // 否则攻击者可以用「401 vs 404」的差异探测哪些路径存在。
        //
        // ⚠️ 这里有两个坑，都踩过：
        //   1. 断言逻辑写反（第一版用 !equals 判失败）
        //   2. 拿完整响应体做字符串比较 —— 响应体里的 timestamp 每次都不同，
        //      即使结果一样也必然不等。必须只比较「状态码 + 业务码」。
        int anonCode = extractCode(notFoundAnon.body());
        int loggedCode = extractCode(notFoundLogged.body());

        if (notFoundAnon.statusCode() == 404
                && notFoundLogged.statusCode() == 404
                && anonCode == 40401
                && loggedCode == 40401) {
            pass("D1. 不存在路径：登录与否都返回 404/40401（不泄露路径是否存在）");
        } else if (notFoundAnon.statusCode() == notFoundLogged.statusCode()
                && anonCode == loggedCode) {
            fail("D1. 响应一致但状态码不是 404",
                    "两者都是 HTTP=" + notFoundAnon.statusCode() + "/code=" + anonCode + "，期望 404/40401");
        } else {
            fail("D1. 不存在路径的响应随登录态变化 —— 存在路径枚举风险",
                    "anon=HTTP" + notFoundAnon.statusCode() + "/code" + anonCode
                            + "  logged=HTTP" + notFoundLogged.statusCode() + "/code" + loggedCode);
        }

        // ══════════════════════════════════════════════════════════
        // E. 方法不支持
        // ══════════════════════════════════════════════════════════
        System.out.println("\n── E. 方法不支持 ──");

        expect("E1. GET /api/auth/login → 405/40501", 405, 40501,
                get(anon(), "/api/auth/login"));

        // ══════════════════════════════════════════════════════════
        // F. 会话伪造 / 篡改
        // ══════════════════════════════════════════════════════════
        System.out.println("\n── F. 会话伪造 ──");

        expect("F1. 随机伪造会话 ID → 401/40101", 401, 40101,
                getWithCookie(anon(), "/api/auth/me", "AI_DIARY_SESSION=DEADBEEF0123456789ABCDEF01234567"));

        expect("F2. 空会话 ID → 401/40101", 401, 40101,
                getWithCookie(anon(), "/api/auth/me", "AI_DIARY_SESSION="));

        expect("F3. 用别人的用户名但错误密码 → 401/40101", 401, 40101,
                post(anon(), "/api/auth/login",
                        "{\"username\":\"" + user + "\",\"password\":\"definitely_wrong_1\"}"));

        System.out.println("\n══════════ 结果 ══════════");
        System.out.println("通过: " + passed + "   失败: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ── 断言工具 ──────────────────────────────────────────────────

    private static void expect(String name, int http, int code, HttpResponse<String> resp) {
        int actualHttp = resp.statusCode();
        int actualCode = extractCode(resp.body());
        // code == -1 表示不校验业务码
        boolean ok = actualHttp == http && (code == -1 || actualCode == code);
        if (ok) {
            pass(name + "  [HTTP=" + actualHttp + "]");
        } else {
            fail(name, "期望 HTTP=" + http + "/code=" + code
                    + "，实际 HTTP=" + actualHttp + "/code=" + actualCode
                    + "  body=" + truncate(resp.body()));
        }
    }

    private static void assertUnifiedBody(String name, String body) {
        boolean ok = body.startsWith("{")
                && body.contains("\"code\"")
                && body.contains("\"message\"")
                && body.contains("\"timestamp\"")
                && !body.toLowerCase().contains("<html");
        if (ok) {
            pass(name);
        } else {
            fail(name, "响应体不是统一结构: " + truncate(body));
        }
    }

    private static String truncate(String s) {
        return s.length() > 150 ? s.substring(0, 150) + "..." : s;
    }

    private static int extractCode(String body) {
        int i = body.indexOf("\"code\":");
        if (i < 0) return Integer.MIN_VALUE;
        int start = i + 7, end = start;
        while (end < body.length() && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '-')) end++;
        try {
            return Integer.parseInt(body.substring(start, end));
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    private static HttpResponse<String> post(HttpClient c, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(HOST + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20));
        if (json.isEmpty()) {
            b.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            b.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        }
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> get(HttpClient c, String path) throws Exception {
        return c.send(HttpRequest.newBuilder()
                .uri(URI.create(HOST + path))
                .timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> getWithCookie(HttpClient c, String path, String cookie) throws Exception {
        return c.send(HttpRequest.newBuilder()
                .uri(URI.create(HOST + path))
                .header("Cookie", cookie)
                .timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void pass(String n) { passed++; System.out.println("  [PASS] " + n); }

    private static void fail(String n, String d) { failed++; System.out.println("  [FAIL] " + n + " —— " + d); }
}
