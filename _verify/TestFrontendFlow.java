import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * 模块 1-5 端到端验收：**通过 Vite 代理**测完整认证流程。
 *
 * <h2>为什么必须走 5173 而不是直连 8080</h2>
 *
 * 浏览器实际访问的是 Vite 开发服务器（5173），由它代理转发到后端（8080）。
 * 这条链路上任何一环出问题都会让登录失效：
 *   - 代理没配 → 请求 404
 *   - Cookie 的 Path/Domain 不对 → 浏览器不保存或后续不发送
 *   - withCredentials 没开 → 跨域时不带 Cookie
 *
 * 所以这个测试<b>必须</b>走 5173，否则测不出真实问题。
 */
public class TestFrontendFlow {

    /**
     * ⚠️ 必须用 IPv6 回环地址 {@code [::1]}，不能写 {@code localhost} 或 {@code 127.0.0.1}。
     *
     * <p>实测发现：<b>Vite 开发服务器默认只监听 IPv6</b>（{@code [::1]:5173}），
     * 不监听 {@code 0.0.0.0}。
     *
     * <pre>
     * http://127.0.0.1:5173/  →  连不上（IPv4 无人监听）
     * http://[::1]:5173/      →  200 OK
     * http://localhost:5173/  →  200 OK（但取决于客户端是否回退到 IPv6）
     * </pre>
     *
     * <p>Java 的 HttpClient 把 {@code localhost} 解析为 IPv4，<b>不会</b>回退尝试 IPv6，
     * 所以会抛 {@code ConnectException / ClosedChannelException}。
     * 浏览器会自动回退，所以人工访问没这个问题 —— 但自动化测试必须写死 IPv6。
     */
    private static final String FRONT = "http://[::1]:5173";
    private static final String BASE = FRONT + "/api";
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("══════════ 模块 1-5 端到端验收（经 Vite 代理）══════════\n");

        String user = "fe_" + System.currentTimeMillis() % 100000;
        String pwd = "abc12345";

        CookieManager cm = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .cookieHandler(cm)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        // ── 1. 前端首页可访问（Vite 有没有起来）────────────────
        System.out.println("── 1. 前端可访问性 ──");
        HttpResponse<String> index = rawGet(FRONT + "/");
        if (index.statusCode() == 200 && index.body().contains("id=\"app\"")) {
            pass("1. 前端首页可访问（含 #app 挂载点）");
        } else {
            fail("1. 前端首页不可访问", "HTTP=" + index.statusCode());
        }

        // ── 2. 未登录访问 /me → 401（经过代理后仍是统一响应体）──
        System.out.println("\n── 2. 未登录状态 ──");
        HttpResponse<String> meAnon = get(client, "/auth/me");
        expect("2. 未登录 /auth/me → 401/40101", 401, 40101, meAnon);
        System.out.println("         " + meAnon.body());

        // ── 3. 注册（经代理）──────────────────────────────────
        System.out.println("\n── 3. 注册 ──");
        HttpResponse<String> reg = post(client, "/auth/register",
                "{\"username\":\"" + user + "\",\"password\":\"" + pwd + "\",\"nickname\":\"前端测试\"}");
        expect("3. 注册成功 → 201/0", 201, 0, reg);
        System.out.println("         " + reg.body());

        // ── 4. 注册不下发 Cookie（不自动登录）──────────────────
        // ⚠️ 这里不能检查 CookieManager 里"有没有会话 Cookie" ——
        //    CookieManager 是<b>跨请求累积</b>的，第 2 步的 401 响应
        //    可能已创建匿名会话并下发 Cookie。那是上一步的残留，
        //    与注册无关。必须只看<b>注册响应本身的响应头</b>。
        //    （第一版就是因为检查了累积状态而误判失败。）
        Optional<String> regCookie = reg.headers().firstValue("Set-Cookie");
        if (regCookie.isPresent() && regCookie.get().contains("SESSION=")) {
            fail("4. 注册不应下发会话 Cookie", "响应头里有: " + regCookie.get());
        } else {
            pass("4. 注册响应头无会话 Cookie（不自动登录）");
            System.out.println("         注册响应 Set-Cookie: " + regCookie.orElse("(无)"));
        }

        // ── 5. 登录（经代理）—— 关键：Cookie 能不能穿过代理 ────
        System.out.println("\n── 5. 登录（关键：Cookie 穿过 Vite 代理）──");
        HttpResponse<String> login = post(client, "/auth/login",
                "{\"username\":\"" + user + "\",\"password\":\"" + pwd + "\"}");
        expect("5. 登录成功 → 200/0", 200, 0, login);

        Optional<String> setCookie = login.headers().firstValue("Set-Cookie");
        System.out.println("         Set-Cookie: " + setCookie.orElse("(无)"));

        if (setCookie.isPresent()) {
            String cookie = setCookie.get();
            assertContains("6. 代理转发的 Cookie 带 HttpOnly", cookie, "HttpOnly");
            assertContains("7. 代理转发的 Cookie 带 SameSite=Lax", cookie, "SameSite=Lax");
            // 关键：代理不能把 Domain 改坏，否则浏览器不保存
            if (cookie.toLowerCase().contains("domain=")) {
                System.out.println("  [INFO] Cookie 含 Domain 属性: " + cookie);
            } else {
                pass("8. Cookie 未带 Domain（浏览器按当前主机保存，正确）");
            }
        } else {
            fail("6-8. 登录未下发 Cookie", "代理可能吞掉了 Set-Cookie 头");
        }

        // ── 9. CookieStore 里是否真的有这个 Cookie ──────────────
        long sessionCookies = cm.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().contains("SESSION"))
                .count();
        if (sessionCookies > 0) {
            HttpCookie c = cm.getCookieStore().getCookies().stream()
                    .filter(x -> x.getName().contains("SESSION")).findFirst().get();
            pass("9. Cookie 已被客户端保存: " + c.getName() + "（域=" + c.getDomain() + "）");
        } else {
            fail("9. Cookie 未被客户端保存", "可能是 Path/Domain/属性问题导致被拒绝");
        }

        // ── 10. 带 Cookie 访问 /me（会话保持）──────────────────
        System.out.println("\n── 10. 会话保持 ──");
        HttpResponse<String> meLogged = get(client, "/auth/me");
        expect("10. 带会话访问 /auth/me → 200/0", 200, 0, meLogged);
        System.out.println("         " + meLogged.body());

        // ── 11. 退出登录 ───────────────────────────────────────
        System.out.println("\n── 11. 退出登录 ──");
        HttpResponse<String> logout = post(client, "/auth/logout", "");
        expect("11. 退出成功 → 200/0", 200, 0, logout);
        System.out.println("         Set-Cookie: "
                + logout.headers().firstValue("Set-Cookie").orElse("(无)"));

        // ── 12. 退出后 /me 应 401 ──────────────────────────────
        HttpResponse<String> meAfter = get(client, "/auth/me");
        expect("12. 退出后 /auth/me → 401/40101", 401, 40101, meAfter);

        // ── 13. 前端 SPA 路由（/login 应返回 index.html）────────
        System.out.println("\n── 13. SPA 路由 ──");
        HttpResponse<String> loginPage = rawGet(FRONT + "/login");
        if (loginPage.statusCode() == 200 && loginPage.body().contains("id=\"app\"")) {
            pass("13. /login 返回 SPA 入口（history 模式路由正常）");
        } else {
            fail("13. /login 未返回 SPA 入口", "HTTP=" + loginPage.statusCode());
        }

        System.out.println("\n══════════ 结果 ══════════");
        System.out.println("通过: " + passed + "   失败: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ── 工具 ──────────────────────────────────────────────────────

    private static void assertContains(String name, String hay, String needle) {
        if (hay.contains(needle)) {
            pass(name);
        } else {
            fail(name, "Cookie 中未出现 \"" + needle + "\"：" + hay);
        }
    }

    private static void expect(String name, int http, int code, HttpResponse<String> resp) {
        int ahttp = resp.statusCode();
        int acode = extractCode(resp.body());
        if (ahttp == http && acode == code) {
            pass(name + "  [HTTP=" + ahttp + "]");
        } else {
            fail(name, "期望 HTTP=" + http + "/code=" + code
                    + "，实际 HTTP=" + ahttp + "/code=" + acode
                    + "  body=" + truncate(resp.body()));
        }
    }

    private static String truncate(String s) {
        return s.length() > 180 ? s.substring(0, 180) + "..." : s;
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

    private static HttpResponse<String> rawGet(String url) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).version(HttpClient.Version.HTTP_1_1).build()
                .send(HttpRequest.newBuilder().uri(URI.create(url))
                                .timeout(Duration.ofSeconds(15)).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void pass(String n) { passed++; System.out.println("  [PASS] " + n); }

    private static void fail(String n, String d) { failed++; System.out.println("  [FAIL] " + n + " —— " + d); }
}
