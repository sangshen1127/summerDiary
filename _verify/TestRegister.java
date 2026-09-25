import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 模块 1-2 注册接口的验收测试。
 *
 * 用 Java 的 HttpClient 而不是 curl / PowerShell Invoke-WebRequest：
 *   - Invoke-WebRequest 在部分环境下会把响应体当字节数组返回，中文易乱码
 *   - curl 在 Windows 上不一定存在
 *   - Java 是项目本来就有的工具链，零额外依赖
 */
public class TestRegister {

    private static final String BASE = "http://localhost:8080/api/auth/register";
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("══════════ 模块 1-2 注册接口验收 ══════════\n");

        String unique = "test_" + System.currentTimeMillis() % 100000;

        // 1. 正常注册
        check("1. 正常注册", 201, 0,
                post("{\"username\":\"" + unique + "\",\"password\":\"abc12345\",\"nickname\":\"测试用户\"}"));

        // 2. 重复用户名（同大小写）
        check("2. 重复用户名", 409, 40901,
                post("{\"username\":\"" + unique + "\",\"password\":\"abc12345\"}"));

        // 3. 重复用户名（不同大小写 —— 验证 ci 排序规则生效）
        String upper = unique.substring(0, 1).toUpperCase() + unique.substring(1);
        check("3. 重复用户名(大小写不同)", 409, 40901,
                post("{\"username\":\"" + upper + "\",\"password\":\"abc12345\"}"));

        // 4. 用户名为空
        check("4. 用户名为空", 400, 40001,
                post("{\"username\":\"\",\"password\":\"abc12345\"}"));

        // 5. 用户名太短
        check("5. 用户名太短", 400, 40001,
                post("{\"username\":\"ab\",\"password\":\"abc12345\"}"));

        // 6. 用户名含非法字符
        check("6. 用户名含非法字符", 400, 40001,
                post("{\"username\":\"ab cd!\",\"password\":\"abc12345\"}"));

        // 7. 密码太短
        check("7. 密码太短", 400, 40001,
                post("{\"username\":\"" + unique + "a\",\"password\":\"ab1\"}"));

        // 8. 密码只有字母（缺数字）
        check("8. 密码缺数字", 400, 40001,
                post("{\"username\":\"" + unique + "b\",\"password\":\"abcdefgh\"}"));

        // 9. 密码只有数字（缺字母）
        check("9. 密码缺字母", 400, 40001,
                post("{\"username\":\"" + unique + "c\",\"password\":\"12345678\"}"));

        // 10. 非法 JSON
        check("10. 非法 JSON", 400, 40001,
                post("{not json}"));

        // 11. 昵称可选 —— 不带 nickname 字段应成功
        check("11. 不带昵称", 201, 0,
                post("{\"username\":\"" + unique + "d\",\"password\":\"abc12345\"}"));

        // 12. 昵称为空串 —— 应成功且归一化为 null
        check("12. 昵称为空串", 201, 0,
                post("{\"username\":\"" + unique + "e\",\"password\":\"abc12345\",\"nickname\":\"\"}"));

        // 13. 昵称为纯空白 —— 应被拒绝
        check("13. 昵称纯空白", 400, 40001,
                post("{\"username\":\"" + unique + "f\",\"password\":\"abc12345\",\"nickname\":\"   \"}"));

        // 14. 汉字用户名
        check("14. 汉字用户名", 201, 0,
                post("{\"username\":\"测试用户" + (System.currentTimeMillis() % 10000) + "\",\"password\":\"abc12345\"}"));

        System.out.println("\n══════════ 结果 ══════════");
        System.out.println("通过: " + passed + "   失败: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(String name, int expectHttp, int expectCode, HttpResponse<String> resp) {
        int http = resp.statusCode();
        String body = resp.body();
        int code = extractCode(body);

        boolean httpOk = http == expectHttp;
        boolean codeOk = code == expectCode;
        boolean ok = httpOk && codeOk;

        if (ok) {
            passed++;
            System.out.printf("  [PASS] %-28s HTTP=%d code=%d%n", name, http, code);
        } else {
            failed++;
            System.out.printf("  [FAIL] %-28s 期望 HTTP=%d/code=%d，实际 HTTP=%d/code=%d%n",
                    name, expectHttp, expectCode, http, code);
        }
        // 打印响应体便于人工核对字段结构（截断到 220 字符）
        String shown = body.length() > 220 ? body.substring(0, 220) + "..." : body;
        System.out.println("         " + shown);
    }

    private static int extractCode(String body) {
        int i = body.indexOf("\"code\":");
        if (i < 0) {
            return Integer.MIN_VALUE;
        }
        int start = i + 7;
        int end = start;
        while (end < body.length() && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '-')) {
            end++;
        }
        try {
            return Integer.parseInt(body.substring(start, end));
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    private static HttpResponse<String> post(String json) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
