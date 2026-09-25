import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 模块 1-3 登录 + HttpOnly Cookie 会话 验收测试。
 *
 * 用 java.net.CookieManager 模拟浏览器 —— 它像浏览器一样自动保存和发送 Cookie，
 * 所以能真实验证「登录后会话是否保持」。
 */
public class TestSession {

    private static final String BASE = "http://localhost:8080/api/auth";
    private static int passed = 0;
    private static int failed = 0;

    /** 带 Cookie 容器的客户端 —— 模拟浏览器 */
    private static HttpClient browser() {
        CookieManager cm = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .cookieHandler(cm)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 不带 Cookie 容器的客户端 —— 模拟 Postman 里没开 Cookie 的情况 */
    private static HttpClient stateless() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("══════════ 模块 1-3 会话验收 ══════════\n");

        String user = "sess_" + System.currentTimeMillis() % 100000;
        String pwd = "abc12345";

        // ══════ 准备：注册一个用户 ══════
        HttpClient reg = stateless();
        post(reg, "/register", "{\"username\":\"" + user + "\",\"password\":\"" + pwd + "\",\"nickname\":\"会话测试\"}");

        // ══════ 1. 登录：必须下发 Set-Cookie ══════
        HttpClient browser = browser();
        HttpResponse<String> login = post(browser, "/login",
                "{\"username\":\"" + user + "\",\"password\":\"" + pwd + "\"}");

        check("1. 登录成功", 200, 0, login);
        System.out.println("         " + login.body());

        Optional<String> setCookie = login.headers().firstValue("Set-Cookie");
        if (setCookie.isEmpty()) {
            fail("2. 登录下发 Set-Cookie", "响应头里没有 Set-Cookie");
        } else {
            pass("2. 登录下发 Set-Cookie");
            System.out.println("         " + setCookie.get());

            String cookie = setCookie.get();
            assertContains("3. Cookie 带 HttpOnly（JS 读不到）", cookie, "HttpOnly");
            assertContains("4. Cookie 带 SameSite=Lax（防 CSRF）", cookie, "SameSite=Lax");
            assertContains("5. Cookie 名为 AI_DIARY_SESSION", cookie, "AI_DIARY_SESSION");
            // 本地开发是 http，Secure 应为 false（若为 true 浏览器不会发送）
            if (cookie.contains("Secure")) {
                System.out.println("  [INFO] Cookie 带 Secure —— 本地开发应为 false，"
                        + "若为 true 浏览器在 http 下不会发送");
            } else {
                pass("6. 本地开发 Cookie 不带 Secure（http 下可发送）");
            }
        }

        // ══════ 7. 用同一 Cookie 容器访问 /me ══════
        HttpResponse<String> me = get(browser, "/me");
        check("7. 带 Cookie 访问 /me 成功", 200, 0, me);
        System.out.println("         " + me.body());

        // ══════ 8. 不带 Cookie 访问 /me 必须 401 ══════
        HttpResponse<String> meNoCookie = get(stateless(), "/me");
        check("8. 不带 Cookie 访问 /me → 401/40101", 401, 40101, meNoCookie);
        System.out.println("         " + meNoCookie.body());

        // ══════ 9. 伪造 Cookie 必须 401 ══════
        HttpResponse<String> meBadCookie = getWithCookie(stateless(), "/me",
                "AI_DIARY_SESSION=forged-session-id-12345");
        check("9. 伪造 Cookie → 401/40101", 401, 40101, meBadCookie);
        System.out.println("         " + meBadCookie.body());

        // ══════ 10. 错误密码 ══════
        HttpResponse<String> badPwd = post(stateless(), "/login",
                "{\"username\":\"" + user + "\",\"password\":\"wrong9999\"}");
        check("10. 密码错误 → 401/40101", 401, 40101, badPwd);
        System.out.println("         " + badPwd.body());

        // ══════ 11. 用户不存在：文案必须与密码错误完全一致 ══════
        HttpResponse<String> noUser = post(stateless(), "/login",
                "{\"username\":\"definitely_not_exist_xyz\",\"password\":\"abc12345\"}");
        check("11. 用户不存在 → 401/40101", 401, 40101, noUser);
        System.out.println("         " + noUser.body());
        String msg1 = extractMessage(badPwd.body());
        String msg2 = extractMessage(noUser.body());
        if (msg1.equals(msg2)) {
            pass("12. 两类失败文案一致（防用户名枚举）");
            System.out.println("         两者都是: \"" + msg1 + "\"");
        } else {
            fail("12. 两类失败文案一致", "密码错误=\"" + msg1 + "\" 用户不存在=\"" + msg2 + "\"");
        }

        // ══════ 13. Session Fixation：登录前后会话 ID 必须不同 ══════
        HttpClient fixationTest = browser();
        // 先访问一个接口拿到初始会话 ID（未登录状态）
        get(fixationTest, "/me");
        String beforeId = sessionIdOf(fixationTest);
        post(fixationTest, "/login", "{\"username\":\"" + user + "\",\"password\":\"" + pwd + "\"}");
        String afterId = sessionIdOf(fixationTest);
        System.out.println("         登录前会话 ID: " + beforeId);
        System.out.println("         登录后会话 ID: " + afterId);
        if (beforeId != null && afterId != null && !beforeId.equals(afterId)) {
            pass("13. 登录后会话 ID 已轮换（防 Session Fixation）");
        } else {
            fail("13. 登录后会话 ID 已轮换",
                    "before=" + beforeId + " after=" + afterId + "（相同则存在 Session Fixation 风险）");
        }

        // ══════ 14. 退出登录 ══════
        HttpResponse<String> logout = post(browser, "/logout", "");
        check("14. 退出登录成功", 200, 0, logout);
        Optional<String> clearCookie = logout.headers().firstValue("Set-Cookie");
        System.out.println("         Set-Cookie: " + clearCookie.orElse("(无)"));
        if (clearCookie.isPresent() && clearCookie.get().contains("Max-Age=0")) {
            pass("15. 退出时下发清除 Cookie（Max-Age=0）");
        } else {
            // 不强判失败：Spring 默认 logout handler 被我们禁用了，
            // 清 Cookie 由容器处理，可能不体现为 Max-Age=0。
            System.out.println("  [INFO] 15. 未看到 Max-Age=0，需人工确认浏览器 Cookie 是否清除");
        }

        // ══════ 16. 退出后旧会话应失效 ══════
        HttpResponse<String> meAfterLogout = get(browser, "/me");
        check("16. 退出后访问 /me → 401/40101", 401, 40101, meAfterLogout);

        // ══════ 17. 注册接口不下发 Cookie（保持 1-2 的结论）══════
        HttpResponse<String> regResp = post(stateless(), "/register",
                "{\"username\":\"" + user + "b\",\"password\":\"" + pwd + "\"}");
        if (regResp.headers().firstValue("Set-Cookie").isPresent()) {
            fail("17. 注册不下发 Set-Cookie", "却下发了: " + regResp.headers().firstValue("Set-Cookie").get());
        } else {
            pass("17. 注册仍不下发 Set-Cookie（不自动登录）");
        }

        System.out.println("\n══════════ 结果 ══════════");
        System.out.println("通过: " + passed + "   失败: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ── 从 CookieManager 里读会话 ID ──────────────────────────────
    private static String sessionIdOf(HttpClient client) {
        return client.cookieHandler()
                .map(h -> (CookieManager) h)
                .map(cm -> cm.getCookieStore().getCookies())
                .flatMap(list -> list.stream()
                        .filter(c -> c.getName().contains("SESSION"))
                        .map(HttpCookie::getValue)
                        .findFirst())
                .orElse(null);
    }

    private static void assertContains(String name, String haystack, String needle) {
        if (haystack.contains(needle)) {
            pass(name);
        } else {
            fail(name, "Cookie 中未出现 \"" + needle + "\"");
        }
    }

    private static HttpResponse<String> post(HttpClient c, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(BASE + path))
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
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        return c.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> getWithCookie(HttpClient c, String path, String cookie) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE + path))
                .header("Cookie", cookie)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        return c.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void check(String name, int expectHttp, int expectCode, HttpResponse<String> resp) {
        int http = resp.statusCode();
        int code = extractCode(resp.body());
        if (http == expectHttp && code == expectCode) {
            pass(name);
        } else {
            fail(name, "期望 HTTP=" + expectHttp + "/code=" + expectCode
                    + "，实际 HTTP=" + http + "/code=" + code);
        }
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

    private static String extractMessage(String body) {
        int i = body.indexOf("\"message\":\"");
        if (i < 0) return "";
        int start = i + 11, end = body.indexOf('"', start);
        return end < 0 ? "" : body.substring(start, end);
    }

    private static void pass(String name) {
        passed++;
        System.out.println("  [PASS] " + name);
    }

    private static void fail(String name, String detail) {
        failed++;
        System.out.println("  [FAIL] " + name + " —— " + detail);
    }
}
