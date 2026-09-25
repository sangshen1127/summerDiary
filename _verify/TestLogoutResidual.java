import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 专项验证：退出登录后，浏览器里残留的旧 Cookie 是否真的无效。
 *
 * 背景：我们的 /logout 只是 session.invalidate() + clearContext()，
 * 没有下发 Set-Cookie: ...; Max-Age=0 去主动删除浏览器里的 Cookie。
 * 所以浏览器会保留一个"已失效的会话 ID"。
 *
 * 这个测试回答两个问题：
 *   1. 用退出前的旧 Cookie 访问 /me，会不会被错误地放行？（安全性）
 *   2. 退出后能否重新登录？（可用性）
 */
public class TestLogoutResidual {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("══════════ 退出后残留 Cookie 专项验证 ══════════\n");

        String user = "lo_" + System.currentTimeMillis() % 100000;
        String pwd = "abc12345";

        CookieManager cm = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .cookieHandler(cm)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        // 准备用户
        post(HttpClient.newHttpClient(), "/register",
                "{\"username\":\"" + user + "\",\"password\":\"" + pwd + "\"}");

        // 登录
        HttpResponse<String> login = post(client, "/login",
                "{\"username\":\"" + user + "\",\"password\":\"" + pwd + "\"}");
        String cookieBefore = cm.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().contains("SESSION"))
                .map(c -> c.getName() + "=" + c.getValue())
                .findFirst().orElse("");
        System.out.println("登录后 Cookie: " + cookieBefore + "\n");

        // 退出
        HttpResponse<String> logout = post(client, "/logout", "");
        System.out.println("退出响应头 Set-Cookie: "
                + logout.headers().firstValue("Set-Cookie").orElse("(无)"));
        System.out.println("退出后 CookieStore 里还剩: "
                + cm.getCookieStore().getCookies().size() + " 个\n");

        // ── 问题 1：用旧 Cookie（手动构造，绕过 CookieStore）访问 /me ──
        if (!cookieBefore.isEmpty()) {
            HttpResponse<String> residual = getWithCookie(HttpClient.newHttpClient(), "/me", cookieBefore);
            int code = extractCode(residual.body());
            if (residual.statusCode() == 401 && code == 40101) {
                pass("1. 退出后旧 Cookie 已失效（服务端会话已销毁）");
            } else {
                fail("1. 退出后旧 Cookie 仍可用 —— 严重安全问题",
                        "HTTP=" + residual.statusCode() + " code=" + code);
            }
            System.out.println("         " + residual.body());
        }

        // ── 问题 2：退出后能否重新登录（可用性）──────────────────
        HttpResponse<String> relogin = post(client, "/login",
                "{\"username\":\"" + user + "\",\"password\":\"" + pwd + "\"}");
        if (relogin.statusCode() == 200 && extractCode(relogin.body()) == 0) {
            pass("2. 退出后可重新登录（残留 Cookie 不影响）");
        } else {
            fail("2. 退出后重新登录失败",
                    "HTTP=" + relogin.statusCode() + " " + relogin.body());
        }

        // ── 问题 3：重新登录后新会话可用 ─────────────────────────
        HttpResponse<String> me = get(client, "/me");
        if (me.statusCode() == 200 && extractCode(me.body()) == 0) {
            pass("3. 重新登录后新会话可正常访问");
        } else {
            fail("3. 重新登录后新会话不可用", "HTTP=" + me.statusCode() + " " + me.body());
        }

        // ── 问题 4：连续两次登出（幂等性）────────────────────────
        post(client, "/logout", "");
        HttpResponse<String> secondLogout = post(client, "/logout", "");
        if (secondLogout.statusCode() == 200 && extractCode(secondLogout.body()) == 0) {
            pass("4. 重复退出是幂等的（未登录时退出也返回成功）");
        } else {
            fail("4. 重复退出不幂等",
                    "HTTP=" + secondLogout.statusCode() + " " + secondLogout.body());
        }

        System.out.println("\n══════════ 结果 ══════════");
        System.out.println("通过: " + passed + "   失败: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static final String BASE = "http://localhost:8080/api/auth";

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
        return c.send(HttpRequest.newBuilder()
                .uri(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> getWithCookie(HttpClient c, String path, String cookie) throws Exception {
        return c.send(HttpRequest.newBuilder()
                .uri(URI.create(BASE + path))
                .header("Cookie", cookie)
                .timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

    private static void pass(String n) { passed++; System.out.println("  [PASS] " + n); }

    private static void fail(String n, String d) { failed++; System.out.println("  [FAIL] " + n + " —— " + d); }
}
