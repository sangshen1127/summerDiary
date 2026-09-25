import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * ============================================================
 * 模块 2-3 接口演示：把真实请求与真实响应原样打印出来
 * ============================================================
 *
 * <h2>这个程序和 TestDiaryApi 有什么区别</h2>
 *
 * <p>{@code TestDiaryApi} 用断言回答"对不对"（77 条 PASS/FAIL）；
 * 本程序回答"长什么样" —— 它把每一步的
 * <b>请求方法 + 路径 + 请求体</b> 和 <b>HTTP 状态 + 响应体</b> 原样打印，
 * 让你能拿着输出跟 Apifox 里的结果逐条对照。
 *
 * <p>存在的理由：断言通过只说明"我按自己的理解测了"，
 * 而验收需要你能独立核对 —— 看到实际的 JSON 字段名、
 * 实际的错误码、实际的 null，才能判断是否符合预期。
 *
 * <h2>用法</h2>
 * <pre>
 * 1. 起 MySQL 容器
 * 2. 起后端：cd backend ; mvn -o -DskipTests spring-boot:run
 * 3. 运行本程序（见文件末尾注释或 README）
 * </pre>
 *
 * <h2>⚠️ 会写库</h2>
 *
 * <p>与 TestDiaryApi 一样会真实创建数据，用唯一前缀 {@code ZZDEMO<时间戳>}，
 * 跑完打印清理 SQL。
 */
public class DemoDiaryApi {

    private static final String BASE = "http://localhost:8080/api";
    private static final String TAG = "ZZDEMO" + (System.currentTimeMillis() % 100000);

    public static void main(String[] args) throws Exception {
        // ⚠️ 输出编码处理：把结果【直接写文件】，不依赖 shell 重定向。
        //
        // 踩过的坑（试了两次才绕过）：
        //   1) `java ... > file.txt` 在 Windows PowerShell 下按【GBK】写文件，
        //      Java 输出的 UTF-8 中文全乱码，甚至被判定为二进制文件读不出来；
        //   2) 用 System.setOut(UTF-8) 也没用 —— 因为 `>` 重定向发生在
        //      Java 进程之外的 shell 层，程序内部的编码设置管不到它。
        //
        // 最终做法：程序自己开 FileOutputStream 写文件，编码完全由自己控制。
        // 这同时也说明了一个通用经验：
        //   **当编码问题反复出现时，缩短链路比逐层调试更有效。**
        String outPath = args.length > 0 ? args[0] : "_demo_out.txt";
        java.io.PrintStream fileOut = new java.io.PrintStream(
                new java.io.FileOutputStream(outPath), true, StandardCharsets.UTF_8);
        System.setOut(fileOut);

        System.out.println("======================================================================");
        System.out.println(" 模块 2-3 接口演示 （测试数据前缀: " + TAG + "）");
        System.out.println("======================================================================");

        Session a = new Session("【A】");
        Session b = new Session("【B】");
        Session anon = new Session("【未登录】");

        step("第 0 步", "确认后端可用");
        anon.show("GET", "/health", null, anon.get("/health"));

        step("第 0 步", "未登录访问受保护接口 —— 应该 401 + 40101");
        anon.show("GET", "/diaries", null, anon.get("/diaries"));

        step("第 1 步", "注册两个用户（注意：注册【不】下发 Cookie）");
        String ua = TAG.toLowerCase() + "_a";
        String ub = TAG.toLowerCase() + "_b";
        Resp ra = a.post("/auth/register",
                "{\"username\":\"" + ua + "\",\"password\":\"pw123456\",\"nickname\":\"甲\"}");
        a.show("POST", "/auth/register", "{\"username\":\"" + ua + "\",...}", ra);
        System.out.println("    >>> 注意看：Set-Cookie = " + (ra.setCookie == null ? "无（正确！注册不登录）" : ra.setCookie));
        Resp rb = b.post("/auth/register",
                "{\"username\":\"" + ub + "\",\"password\":\"pw123456\",\"nickname\":\"乙\"}");
        b.show("POST", "/auth/register", "{\"username\":\"" + ub + "\",...}", rb);

        step("第 1 步", "登录 —— 这时才下发 Cookie（每次登录会话 ID 都会变）");
        Resp la = a.post("/auth/login", "{\"username\":\"" + ua + "\",\"password\":\"pw123456\"}");
        a.show("POST", "/auth/login", "{\"username\":\"" + ua + "\",...}", la);
        String cookieA = extractCookieValue(la.setCookie);
        System.out.println("    >>> A 的会话 ID = " + cookieA);
        Resp lb = b.post("/auth/login", "{\"username\":\"" + ub + "\",\"password\":\"pw123456\"}");
        b.show("POST", "/auth/login", "{\"username\":\"" + ub + "\",...}", lb);
        String cookieB = extractCookieValue(lb.setCookie);
        System.out.println("    >>> B 的会话 ID = " + cookieB);
        System.out.println("    >>> 两人会话 ID " + (cookieA.equals(cookieB) ? "相同（异常！）" : "不同（正确，互相隔离）"));

        step("第 2 步", "创建日记 —— 注意 content 返回【明文】、created_at 带 Z、analysis_status 为 null");
        String titleA = TAG + " 海边的傍晚";
        Resp created = a.post("/diaries",
                "{\"title\":\"" + titleA + "\",\"content\":\"今天去海边走了走。\\n第二行。\","
                        + "\"mood\":\"平静\",\"weather\":\"晴\",\"location\":\"海边\",\"tag_ids\":[]}");
        a.show("POST", "/diaries", "{title/content/mood/weather/location/tag_ids}", created);
        long diaryA = extractLong(created.body, "id");

        step("第 2 步", "详情 —— content 是解密后的明文");
        a.show("GET", "/diaries/" + diaryA, null, a.get("/diaries/" + diaryA));

        step("第 2 步", "列表 —— 注意 items[].content 是 null（列表刻意不解密正文）");
        Resp listA = a.get("/diaries?page=0&size=20");
        a.show("GET", "/diaries?page=0&size=20", null, listA);

        step("第 2 步", "PUT 整体替换 —— 只传 title/content，weather/location 会被清空");
        a.show("PUT", "/diaries/" + diaryA,
                "{\"title\":\"改过的标题\",\"content\":\"改过的正文\",\"mood\":\"开心\"}",
                a.put("/diaries/" + diaryA,
                        "{\"title\":\"" + TAG + " 改过的标题\",\"content\":\"改过的正文\",\"mood\":\"开心\"}"));

        step("第 3 步【重点】", "跨用户越权①：B 读 A 的日记 —— 必须 404 + 40401，且响应里没有 A 的数据");
        b.show("GET", "/diaries/" + diaryA + "  (A 的日记)", null, b.get("/diaries/" + diaryA));

        step("第 3 步【重点】", "跨用户越权②：B 改 A 的日记");
        b.show("PUT", "/diaries/" + diaryA,
                "{\"title\":\"B篡改\",\"content\":\"B篡改\"}",
                b.put("/diaries/" + diaryA, "{\"title\":\"B篡改\",\"content\":\"B篡改\"}"));

        step("第 3 步【重点】", "跨用户越权③：B 删 A 的日记");
        b.show("DELETE", "/diaries/" + diaryA, null, b.delete("/diaries/" + diaryA));

        step("第 3 步", "复核：A 的日记还在、内容没被改");
        a.show("GET", "/diaries/" + diaryA, null, a.get("/diaries/" + diaryA));

        step("第 4 步", "建标签 + 同名复用（第二次必须返回同一个 id，不是 409）");
        Resp t1 = a.post("/tags", "{\"name\":\"" + TAG + "_读书\"}");
        a.show("POST", "/tags", "{\"name\":\"" + TAG + "_读书\"}", t1);
        long tagA = extractLong(t1.body, "id");
        Resp t2 = a.post("/tags", "{\"name\":\"" + TAG + "_读书\"}");
        a.show("POST", "/tags", "{\"name\":\"" + TAG + "_读书\"}  (第二次，同名)", t2);
        System.out.println("    >>> 第一次 id=" + tagA + "，第二次 id=" + extractLong(t2.body, "id")
                + " —— " + (tagA == extractLong(t2.body, "id") ? "相同（正确，幂等复用）" : "不同（异常！）"));

        step("第 4 步", "标签列表");
        a.show("GET", "/tags", null, a.get("/tags"));

        step("第 4 步【重点】", "★ tag_id 筛选（就是刚修好的那个 bug）");
        System.out.println("    先建一篇带标签的日记：");
        a.show("POST", "/diaries",
                "{title: \"...筛选目标\", tag_ids: [" + tagA + "]}",
                a.post("/diaries", "{\"title\":\"" + TAG + " 筛选命中目标\",\"content\":\"x\","
                        + "\"tag_ids\":[" + tagA + "]}"));
        System.out.println("    然后用 tag_id=" + tagA + " 筛选 —— 必须只返回那一篇，不能返回全部：");
        a.show("GET", "/diaries?tag_id=" + tagA, null, a.get("/diaries?page=0&size=20&tag_id=" + tagA));

        System.out.println("    对照组：用一个【没有关联日记】的标签筛选 —— 应该 items 为空：");
        long spare = extractLong(a.post("/tags", "{\"name\":\"" + TAG + "_无关联\"}").body, "id");
        a.show("GET", "/diaries?tag_id=" + spare, null, a.get("/diaries?page=0&size=20&tag_id=" + spare));

        step("第 4 步", "边界值：size 超限 / page 负数 / 非法时间格式");
        a.show("GET", "/diaries?page=0&size=101", null, a.get("/diaries?page=0&size=101"));
        a.show("GET", "/diaries?page=-1&size=20", null, a.get("/diaries?page=-1&size=20"));
        a.show("GET", "/diaries?from=not-a-date", null, a.get("/diaries?page=0&size=20&from=not-a-date"));
        a.show("GET", "/diaries?page=0&size=100", "（边界值 100，应 200）", a.get("/diaries?page=0&size=100"));

        step("第 4 步", "keyword 只搜标题：用正文里的词搜应该搜不到");
        a.show("GET", "/diaries?keyword=海边走了走  (正文里的词)", null,
                a.get("/diaries?page=0&size=20&keyword=" + enc("海边走了走")));
        a.show("GET", "/diaries?keyword=改过的标题  (标题里的词)", null,
                a.get("/diaries?page=0&size=20&keyword=" + enc("改过的标题")));

        step("第 4 步", "删除正在被使用的标签 —— 必须 409 + 40901 + 篇数");
        a.show("DELETE", "/tags/" + tagA + "  (正在被使用)", null, a.delete("/tags/" + tagA));

        step("第 4 步", "删除未被使用的标签 —— 应该 200");
        a.show("DELETE", "/tags/" + spare + "  (未被使用)", null, a.delete("/tags/" + spare));

        step("第 4 步", "跨用户标签：A 删 B 的标签");
        long tagB = extractLong(b.post("/tags", "{\"name\":\"" + TAG + "_B专属\"}").body, "id");
        a.show("DELETE", "/tags/" + tagB + "  (B 的标签)", null, a.delete("/tags/" + tagB));

        step("第 4 步", "删除日记（软删除）+ 重复删除");
        a.show("DELETE", "/diaries/" + diaryA, null, a.delete("/diaries/" + diaryA));
        a.show("GET", "/diaries/" + diaryA + "  (删后查)", null, a.get("/diaries/" + diaryA));
        a.show("DELETE", "/diaries/" + diaryA + "  (重复删)", null, a.delete("/diaries/" + diaryA));

        System.out.println();
        System.out.println("======================================================================");
        System.out.println(" 演示结束。测试数据前缀: " + TAG);
        System.out.println(" 如需清理（在 MySQL 客户端执行）：");
        System.out.println("   DELETE dt FROM diary_tag dt JOIN diary d ON d.id=dt.diary_id"
                + " WHERE d.title LIKE '" + TAG + "%';");
        System.out.println("   DELETE FROM diary WHERE title LIKE '" + TAG + "%';");
        System.out.println("   DELETE FROM tag WHERE name LIKE '" + TAG + "%';");
        System.out.println("   DELETE FROM `user` WHERE username LIKE '" + TAG.toLowerCase() + "%';");
        System.out.println("======================================================================");

        fileOut.flush();
        fileOut.close();
    }

    // ══════════════════════════════════════════════════════════
    private static void step(String phase, String desc) {
        System.out.println();
        System.out.println("──────────────────────────────────────────────────────────────────────");
        System.out.println("▶ " + phase + "：" + desc);
        System.out.println("──────────────────────────────────────────────────────────────────────");
    }

    /** 一个独立会话（自己的 Cookie 罐），模拟一个"浏览器"。 */
    static class Session {
        private final HttpClient client;
        private final String label;

        Session(String label) {
            this.label = label;
            this.client = HttpClient.newBuilder()
                    .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                    .version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        }

        void show(String method, String path, String reqBody, Resp r) {
            System.out.println("  " + label + " " + method + " " + BASE + path);
            if (reqBody != null) {
                System.out.println("    请求体: " + reqBody);
            }
            System.out.println("    ← HTTP " + r.status);
            if (r.setCookie != null && r.setCookie.contains("AI_DIARY_SESSION=")) {
                System.out.println("    ← Set-Cookie: " + shorten(r.setCookie));
            }
            System.out.println("    ← 响应: " + r.body);
        }

        Resp get(String path) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(BASE + path))
                    .header("Accept", "application/json").GET().build());
        }

        Resp post(String path, String json) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(BASE + path))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build());
        }

        Resp put(String path, String json) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(BASE + path))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build());
        }

        Resp delete(String path) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(BASE + path)).DELETE().build());
        }

        private Resp send(HttpRequest request) throws Exception {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Resp(response.statusCode(), response.body(),
                    response.headers().firstValue("Set-Cookie").orElse(null));
        }
    }

    record Resp(int status, String body, String setCookie) {
    }

    private static String shorten(String cookie) {
        return cookie.length() > 80 ? cookie.substring(0, 80) + "..." : cookie;
    }

    private static String extractCookieValue(String setCookie) {
        if (setCookie == null) {
            return "(无)";
        }
        int start = setCookie.indexOf("AI_DIARY_SESSION=");
        if (start < 0) {
            return "(无)";
        }
        start += "AI_DIARY_SESSION=".length();
        int end = setCookie.indexOf(';', start);
        return end < 0 ? setCookie.substring(start) : setCookie.substring(start, end);
    }

    private static long extractLong(String json, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(field) + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    private static String enc(String v) {
        return java.net.URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
