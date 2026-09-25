import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 模块 1-2 补充安全断言：
 *   1. 注册响应体绝不能包含密码哈希
 *   2. 响应头不能有 Set-Cookie（注册不自动登录）
 *   3. HTTP 方法限制正确
 */
public class TestRegisterSecurity {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("══════════ 模块 1-2 安全断言 ══════════\n");

        String unique = "sec_" + System.currentTimeMillis() % 100000;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        // ── 注册一个用户，检查响应体与响应头 ──────────────────────
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8080/api/auth/register"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"" + unique + "\",\"password\":\"secret123\",\"nickname\":\"安全测试\"}",
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String body = resp.body();

        System.out.println("响应体:");
        System.out.println("  " + body);
        System.out.println();
        System.out.println("响应头:");
        resp.headers().map().forEach((k, v) -> System.out.println("  " + k + ": " + v));
        System.out.println();

        // ── 断言 1：响应体不含任何密码相关痕迹 ────────────────────
        assertNotContains("1. 响应体不含 password_hash 字段名", body, "password_hash");
        assertNotContains("2. 响应体不含 passwordHash 字段名", body, "passwordHash");
        assertNotContains("3. 响应体不含 BCrypt 前缀 $2a$", body, "$2a$");
        assertNotContains("4. 响应体不含明文密码", body, "secret123");

        // ── 断言 2：注册不自动登录，不应下发 Cookie ───────────────
        boolean hasSetCookie = resp.headers().firstValue("Set-Cookie").isPresent();
        if (hasSetCookie) {
            fail("5. 注册不应下发 Set-Cookie", "却发现了: " + resp.headers().firstValue("Set-Cookie").get());
        } else {
            pass("5. 注册不下发 Set-Cookie（不自动登录）");
        }

        // ── 断言 3：HTTP 方法限制 ─────────────────────────────────
        HttpRequest getReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8080/api/auth/register"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> getResp = client.send(getReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (getResp.statusCode() == 405) {
            pass("6. GET /register 返回 405（只允许 POST）");
        } else {
            fail("6. GET /register 应返回 405", "实际 " + getResp.statusCode());
        }
        System.out.println("         " + getResp.body());

        System.out.println("\n══════════ 结果 ══════════");
        System.out.println("通过: " + passed + "   失败: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void assertNotContains(String name, String body, String needle) {
        if (body.contains(needle)) {
            fail(name, "响应体中出现了 \"" + needle + "\"");
        } else {
            pass(name);
        }
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
