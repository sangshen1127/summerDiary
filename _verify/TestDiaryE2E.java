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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ============================================================
 * 模块 2-5 端到端验收：**经 Vite 代理**走完日记闭环
 * ============================================================
 *
 * <h2>为什么不复用 TestFrontendFlow</h2>
 *
 * <p>{@code TestFrontendFlow}（Phase 1）聚焦<b>认证链路</b>：
 * 注册 / 登录 / Cookie 穿过代理 / 会话保持 / 退出。
 * 本文件聚焦<b>日记业务闭环</b>：创建 → 列表 → 详情 → 修改 → 删除，
 * 以及最关键的**跨用户越权**。
 *
 * <p>两者职责不同、可以独立运行，所以分成两个文件。
 * 合并的话，任何一边失败都要重跑另一边的全部断言。
 *
 * <h2>为什么必须走 5173（Vite 代理）而不是直连 8080</h2>
 *
 * <p>浏览器实际访问的是 Vite（5173），由它转发到后端（8080）。
 * 这条链路上任何一环出问题都会让日记功能失效：
 * <ul>
 *   <li>代理没配 → 请求 404</li>
 *   <li>Cookie 的 Path/Domain 被代理改坏 → 后续请求不带会话</li>
 *   <li>响应体被代理破坏 → JSON 解析失败</li>
 * </ul>
 * 直连 8080 测不出这些问题。
 *
 * <h2>⚠️ 必须用 IPv6 回环 [::1]</h2>
 *
 * <p>Vite 默认只监听 IPv6（{@code [::1]:5173}）。Java 的 HttpClient 把
 * {@code localhost} 解析为 IPv4 且<b>不会</b>回退尝试 IPv6，会抛
 * ConnectException。浏览器会自动回退，所以人工访问没这个问题。
 * 详见 {@code TestFrontendFlow} 里的同类说明。
 *
 * <h2>运行前置</h2>
 * <pre>
 * 1. MySQL 容器跑着
 * 2. 后端：cd backend ; mvn -o -DskipTests spring-boot:run
 * 3. 前端：cd frontend ; npm run preview   （或 npm run dev）
 * </pre>
 *
 * <h2>数据清理</h2>
 *
 * <p>经 HTTP 创建的数据<b>不会</b>自动回滚（事务边界在服务端）。
 * 全部用前缀 {@code ZZE2E<时间戳>} 标记，跑完打印清理 SQL。
 */
public class TestDiaryE2E {

    /** ⚠️ 必须 IPv6，理由见类注释。 */
    private static final String FRONT = "http://[::1]:5173";
    private static final String BASE = FRONT + "/api";

    private static final String RUN = "ZZE2E" + (System.currentTimeMillis() % 100000);

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        // ⚠️ 支持把输出同时写进文件。
        //
        // 为什么需要：Windows PowerShell 的 `> file.txt` 重定向会按 **GBK**
        // 写文件，Java 输出的 UTF-8 中文会变成乱码，甚至被当成二进制读不出来。
        // 用 `java TestDiaryE2E out.txt` 让程序自己控制编码，就绕开了 shell。
        //
        // 不传参数时只输出到控制台（IDEA 里跑就很方便）。
        String outFile = args.length > 0 ? args[0] : null;
        java.io.FileOutputStream fos = null;
        java.io.PrintStream console = System.out;
        if (outFile != null) {
            fos = new java.io.FileOutputStream(outFile);
            java.io.PrintStream fileOut =
                    new java.io.PrintStream(fos, true, StandardCharsets.UTF_8);
            // 同时写控制台和文件：既能看到实时进度，又能留下可读日志
            System.setOut(new java.io.PrintStream(new TeeStream(console, fileOut), true,
                    StandardCharsets.UTF_8));
        }

        System.out.println("══════════ 模块 2-5 日记闭环端到端验收（经 Vite 代理）══════════");
        System.out.println("测试数据前缀: " + RUN + "\n");

        // 两个独立客户端 = 两个独立会话（模拟两个浏览器）
        Session alice = new Session();
        Session bob = new Session();

        try {
            section("一、前端可访问性（SPA 路由）");
            testSpaRoutes();

            section("二、准备两个用户");
            prepare(alice, bob);

            section("三、创建日记");
            long aliceDiary = testCreate(alice);

            section("四、列表（经代理返回分页结构）");
            testList(alice);

            section("五、详情（正文解密）");
            testDetail(alice, aliceDiary);

            section("六、修改（PUT 整体替换）");
            testUpdate(alice, aliceDiary);

            section("七、★ 跨用户越权（经代理）");
            testCrossUser(alice, bob);

            section("八、标签闭环");
            testTags(alice, bob);

            section("九、软删除");
            testDelete(alice);
        } finally {
            System.out.println();
            System.out.println("══════════ 清理 SQL（在 MySQL 客户端执行）══════════");
            System.out.println("DELETE dt FROM diary_tag dt JOIN diary d ON d.id=dt.diary_id"
                    + " JOIN `user` u ON u.id=d.user_id WHERE u.username LIKE '"
                    + RUN.toLowerCase() + "%';");
            System.out.println("DELETE d FROM diary d JOIN `user` u ON u.id=d.user_id"
                    + " WHERE u.username LIKE '" + RUN.toLowerCase() + "%';");
            System.out.println("DELETE t FROM tag t JOIN `user` u ON u.id=t.user_id"
                    + " WHERE u.username LIKE '" + RUN.toLowerCase() + "%';");
            System.out.println("DELETE FROM `user` WHERE username LIKE '" + RUN.toLowerCase() + "%';");
        }

        System.out.println();
        System.out.println("══════════ 结果 ══════════");
        System.out.println("通过: " + passed + "   失败: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ══════════════════════════════════════════════════════════
    // 一、SPA 路由：确认前端真的能服务这几个页面
    // ══════════════════════════════════════════════════════════
    private static void testSpaRoutes() throws Exception {
        // 日记相关页面都是 history 模式路由，必须返回 SPA 入口（index.html）。
        // 如果 Vite 没配 history fallback，直接刷新 /diaries 会 404 ——
        // 这是"点进去能用、一刷新就白屏"类问题的根源。
        String[] routes = {"/diaries", "/diaries/new", "/diaries/1", "/diaries/1/edit"};
        int okCount = 0;
        for (String route : routes) {
            HttpResponse<String> r = rawGet(FRONT + route);
            if (r.statusCode() == 200 && r.body().contains("id=\"app\"")) {
                okCount++;
            } else {
                fail("SPA 路由 " + route, "HTTP=" + r.statusCode());
            }
        }
        if (okCount == routes.length) {
            pass("1. 4 个日记相关路由都返回 SPA 入口（history 模式 fallback 正常）");
        }

        // 未登录访问受保护页面 → 前端路由守卫会跳登录页。
        // ⚠️ 这一条测的是"服务端仍返回 index.html"（因为守卫是客户端行为），
        //    真正的重定向由浏览器执行，属于 browser-check.mjs 的范围。
        HttpResponse<String> home = rawGet(FRONT + "/home");
        if (home.statusCode() == 200) {
            pass("2. /home 返回 SPA 入口（是否跳登录由前端守卫决定，见 browser-check）");
        } else {
            fail("2. /home 不可访问", "HTTP=" + home.statusCode());
        }
    }

    // ══════════════════════════════════════════════════════════
    private static void prepare(Session a, Session b) throws Exception {
        String ua = RUN.toLowerCase() + "_a";
        String ub = RUN.toLowerCase() + "_b";
        String pwd = "pw123456";

        expect("2.1 注册 A → 201/0", 201, 0,
                a.post("/auth/register", "{\"username\":\"" + ua + "\",\"password\":\"" + pwd
                        + "\",\"nickname\":\"甲\"}"));
        expect("2.2 注册 B → 201/0", 201, 0,
                b.post("/auth/register", "{\"username\":\"" + ub + "\",\"password\":\"" + pwd
                        + "\",\"nickname\":\"乙\"}"));

        expect("2.3 登录 A → 200/0", 200, 0,
                a.post("/auth/login", "{\"username\":\"" + ua + "\",\"password\":\"" + pwd + "\"}"));
        expect("2.4 登录 B → 200/0", 200, 0,
                b.post("/auth/login", "{\"username\":\"" + ub + "\",\"password\":\"" + pwd + "\"}"));

        // ⚠️ 两个会话必须不同 —— 否则"跨用户"测试是假的
        String ca = a.sessionValue();
        String cb = b.sessionValue();
        if (ca != null && cb != null && !ca.equals(cb)) {
            pass("2.5 两个会话 ID 不同（跨用户测试的前提成立）");
        } else {
            fail("2.5 两个会话 ID 相同或缺失", "A=" + shorten(ca) + " B=" + shorten(cb));
        }
    }

    // ══════════════════════════════════════════════════════════
    private static long testCreate(Session s) throws Exception {
        String title = RUN + " 海边的傍晚";
        HttpResponse<String> r = s.post("/diaries",
                "{\"title\":\"" + title + "\",\"content\":\"今天去海边走了走。\\n第二行。\","
                        + "\"mood\":\"平静\",\"weather\":\"晴\",\"location\":\"海边\",\"tag_ids\":[]}");

        expect("3.1 创建日记 → 201/0", 201, 0, r);
        long id = extractLong(r.body(), "id");
        check("3.2 响应含新建日记 id", id > 0, "id=" + id);
        check("3.3 ★ 正文以【明文】返回（解密链路通）",
                r.body().contains("今天去海边走了走"), snippet(r.body()));
        check("3.4 created_at 是带 Z 的 ISO-8601",
                r.body().matches("(?s).*\"created_at\"\\s*:\\s*\"[^\"]*Z\".*"), snippet(r.body()));
        check("3.5 ★ analysis_status 存在且为 null（Phase 2 占位字段）",
                r.body().contains("\"analysis_status\":null"), snippet(r.body()));
        check("3.6 响应不含内部字段（userId / contentCiphertext / deleted）",
                !r.body().contains("contentCiphertext") && !r.body().contains("deleted"),
                snippet(r.body()));

        // 参数校验
        expect("3.7 标题为空 → 400/40001", 400, 40001,
                s.post("/diaries", "{\"title\":\"\",\"content\":\"x\"}"));
        expect("3.8 缺 content 字段 → 400/40001", 400, 40001,
                s.post("/diaries", "{\"title\":\"只有标题\"}"));
        // 只写标题（content 为空串）是合法的
        expect("3.9 content 为空串是合法的 → 201/0", 201, 0,
                s.post("/diaries", "{\"title\":\"" + RUN + " 只有标题\",\"content\":\"\"}"));

        return id;
    }

    // ══════════════════════════════════════════════════════════
    private static void testList(Session s) throws Exception {
        HttpResponse<String> r = s.get("/diaries?page=0&size=20");
        expect("4.1 列表 → 200/0", 200, 0, r);

        check("4.2 分页结构字段齐全（items/page/size/total/hasNext）",
                r.body().contains("\"items\"") && r.body().contains("\"hasNext\"")
                        && r.body().contains("\"total\""), snippet(r.body()));
        check("4.3 ★★ 列表项的 content 为 null（列表刻意不解密正文）",
                r.body().contains("\"content\":null"),
                "若列表返回明文，说明安全取舍失效。" + snippet(r.body()));
        check("4.4 列表里有刚创建的日记", r.body().contains("海边的傍晚"), snippet(r.body()));

        // 分页上限由 Service 强制
        expect("4.5 size=101 超限 → 400/40001", 400, 40001, s.get("/diaries?page=0&size=101"));
        expect("4.6 size=0 → 400/40001", 400, 40001, s.get("/diaries?page=0&size=0"));
        expect("4.7 page=-1 → 400/40001", 400, 40001, s.get("/diaries?page=-1&size=20"));
        expect("4.8 size=100 边界值 → 200/0", 200, 0, s.get("/diaries?page=0&size=100"));
        expect("4.9 非法时间格式 → 400/40001", 400, 40001,
                s.get("/diaries?page=0&size=20&from=not-a-date"));

        // ★ keyword 只搜标题：用正文里的词搜不到
        HttpResponse<String> byContent = s.get("/diaries?page=0&size=20&keyword="
                + enc("海边走了走"));
        check("4.10 ★★ 用正文里的词搜不到（keyword 只搜标题）",
                !byContent.body().contains("海边的傍晚"),
                "搜到了，说明正文可被检索（与加密设计冲突）。" + snippet(byContent.body()));

        HttpResponse<String> byTitle = s.get("/diaries?page=0&size=20&keyword="
                + enc("海边的傍晚"));
        check("4.11 用标题里的词搜得到（对照组：证明 4.10 不是搜索坏了）",
                byTitle.body().contains("海边的傍晚"), snippet(byTitle.body()));
    }

    // ══════════════════════════════════════════════════════════
    private static void testDetail(Session s, long id) throws Exception {
        HttpResponse<String> r = s.get("/diaries/" + id);
        expect("5.1 详情 → 200/0", 200, 0, r);
        check("5.2 ★ 正文解密后与写入一致（含换行）",
                r.body().contains("今天去海边走了走") && r.body().contains("第二行"),
                snippet(r.body()));
        check("5.3 标签是数组（无标签时为空数组，不是 null）",
                r.body().contains("\"tags\":[]"), snippet(r.body()));

        expect("5.4 不存在的日记 → 404/40401", 404, 40401, s.get("/diaries/999999999"));
        expect("5.5 非数字 ID → 400/40001（不是 500）", 400, 40001, s.get("/diaries/abc"));
    }

    // ══════════════════════════════════════════════════════════
    private static void testUpdate(Session s, long id) throws Exception {
        HttpResponse<String> r = s.put("/diaries/" + id,
                "{\"title\":\"" + RUN + " 改过的标题\",\"content\":\"改过的正文\",\"mood\":\"开心\"}");
        expect("6.1 修改 → 200/0", 200, 0, r);
        check("6.2 标题与正文都已更新",
                r.body().contains("改过的标题") && r.body().contains("改过的正文"),
                snippet(r.body()));
        check("6.3 ★ PUT 整体替换：未传的 weather/location 被清空为 null",
                r.body().contains("\"weather\":null") && r.body().contains("\"location\":null"),
                snippet(r.body()));

        // 重新查一次确认落库
        HttpResponse<String> again = s.get("/diaries/" + id);
        check("6.4 重新查询确认修改已持久化",
                again.body().contains("改过的正文"), snippet(again.body()));

        expect("6.5 修改不存在的日记 → 404/40401", 404, 40401,
                s.put("/diaries/999999999", "{\"title\":\"x\",\"content\":\"y\"}"));
    }

    // ══════════════════════════════════════════════════════════
    private static void testCrossUser(Session alice, Session bob) throws Exception {
        // B 建自己的日记
        HttpResponse<String> created = bob.post("/diaries",
                "{\"title\":\"" + RUN + " B的日记\",\"content\":\"B 的私密正文\"}");
        long bobDiary = extractLong(created.body(), "id");
        check("7.0 B 创建自己的日记成功", bobDiary > 0, "id=" + bobDiary);

        // ★ A 读 B 的
        HttpResponse<String> read = alice.get("/diaries/" + bobDiary);
        expect("7.1 ★★ A 读 B 的日记 → 404/40401（不用 403，防枚举）", 404, 40401, read);
        check("7.2 ★★ 越权响应里不含 B 的正文/标题",
                !read.body().contains("B 的私密正文") && !read.body().contains("B的日记"),
                "泄露了 B 的数据！" + snippet(read.body()));

        // ★ A 改 B 的
        expect("7.3 ★★ A 改 B 的日记 → 404/40401", 404, 40401,
                alice.put("/diaries/" + bobDiary,
                        "{\"title\":\"A篡改\",\"content\":\"A篡改\"}"));

        // 复核 B 的日记没被改
        HttpResponse<String> bobCheck = bob.get("/diaries/" + bobDiary);
        check("7.4 ★ A 的篡改未生效（B 的内容未变）",
                bobCheck.body().contains("B 的私密正文"), snippet(bobCheck.body()));

        // ★ A 删 B 的
        expect("7.5 ★★ A 删 B 的日记 → 404/40401", 404, 40401,
                alice.delete("/diaries/" + bobDiary));
        HttpResponse<String> still = bob.get("/diaries/" + bobDiary);
        check("7.6 ★ B 的日记还在（越权删除未成功）", still.statusCode() == 200,
                "HTTP=" + still.statusCode() + " —— 若 404 说明越权删除成功了！");

        // ★ A 的列表里不应有 B 的日记
        HttpResponse<String> listA = alice.get("/diaries?page=0&size=100");
        check("7.7 ★★ A 的列表里不含 B 的日记",
                !listA.body().contains(RUN + " B的日记"), "列表里出现了别人的日记！");
    }

    // ══════════════════════════════════════════════════════════
    private static void testTags(Session alice, Session bob) throws Exception {
        HttpResponse<String> t1 = alice.post("/tags", "{\"name\":\"" + RUN + "_读书\"}");
        expect("8.1 创建标签 → 201/0", 201, 0, t1);
        long tagA = extractLong(t1.body(), "id");

        // ★ 同名复用：必须返回同一个 id（幂等，不报 409）
        HttpResponse<String> t2 = alice.post("/tags", "{\"name\":\"" + RUN + "_读书\"}");
        long tagA2 = extractLong(t2.body(), "id");
        check("8.2 ★★ 同名标签再创建 → 复用同一个 id（幂等，不报 409）",
                tagA2 == tagA && t2.statusCode() == 201,
                "第一次=" + tagA + " 第二次=" + tagA2 + " HTTP=" + t2.statusCode());

        expect("8.3 标签列表 → 200/0", 200, 0, alice.get("/tags"));

        // ★ B 可以创建同名标签（唯一键是 user_id+name）
        HttpResponse<String> tb = bob.post("/tags", "{\"name\":\"" + RUN + "_读书\"}");
        long tagB = extractLong(tb.body(), "id");
        check("8.4 ★★ B 可以创建同名标签（标签按用户隔离）",
                tagB > 0 && tagB != tagA, "A=" + tagA + " B=" + tagB);

        // 带标签建日记
        HttpResponse<String> withTag = alice.post("/diaries",
                "{\"title\":\"" + RUN + " 带标签\",\"content\":\"正文\",\"tag_ids\":[" + tagA + "]}");
        expect("8.5 创建带标签的日记 → 201/0", 201, 0, withTag);
        check("8.6 ★ 详情里能看到关联的标签", withTag.body().contains("_读书"),
                snippet(withTag.body()));

        // ★★ tag_id 筛选（这是模块 2-3 修过的静默失效 bug，必须回归）
        HttpResponse<String> byTag = alice.get("/diaries?page=0&size=20&tag_id=" + tagA);
        check("8.7 ★★ 用【自己的】tag_id 筛选 → 筛得到那一篇",
                byTag.body().contains("带标签"),
                "若返回全部或找不到目标，说明 tag_id 参数没被绑定（静默失效）。"
                        + snippet(byTag.body()));

        // 没有关联日记的标签 → 结果为空
        long spare = extractLong(
                alice.post("/tags", "{\"name\":\"" + RUN + "_无关联\"}").body(), "id");
        HttpResponse<String> bySpare = alice.get("/diaries?page=0&size=20&tag_id=" + spare);
        check("8.8 用无关联的 tag_id 筛选 → items 为空（不是返回全部）",
                bySpare.body().contains("\"items\":[]"), snippet(bySpare.body()));

        // ★ A 用 B 的 tagId 建日记 → 40401
        expect("8.9 ★★ A 用 B 的 tagId 建日记 → 404/40401", 404, 40401,
                alice.post("/diaries", "{\"title\":\"" + RUN + " 越权标签\",\"content\":\"x\","
                        + "\"tag_ids\":[" + tagB + "]}"));

        // ★ 重复 tagId 不应 500（Service 去重）
        HttpResponse<String> dup = alice.post("/diaries",
                "{\"title\":\"" + RUN + " 重复标签\",\"content\":\"x\",\"tag_ids\":["
                        + tagA + "," + tagA + "]}");
        check("8.10 ★★ 重复的 tagId → 201（Service 去重，不是 500）",
                dup.statusCode() == 201,
                "HTTP=" + dup.statusCode() + " —— 500 说明重复 ID 撞了 diary_tag 联合主键");

        // ★ 删除正在被使用的标签 → 40901
        HttpResponse<String> delUsed = alice.delete("/tags/" + tagA);
        check("8.11 ★★ 删除正在被使用的标签 → 409/40901 + 提示篇数",
                delUsed.statusCode() == 409 && delUsed.body().contains("40901")
                        && delUsed.body().contains("篇日记使用"),
                "HTTP=" + delUsed.statusCode() + " " + snippet(delUsed.body()));

        // ★ A 删 B 的标签 → 40401
        expect("8.12 ★★ A 删 B 的标签 → 404/40401", 404, 40401, alice.delete("/tags/" + tagB));
        check("8.13 ★ B 的标签还在", bob.get("/tags").body().contains("_读书"),
                "B 的标签被删掉了！");

        // 未被使用的标签可删
        expect("8.14 删除未被使用的标签 → 200/0", 200, 0, alice.delete("/tags/" + spare));
    }

    // ══════════════════════════════════════════════════════════
    private static void testDelete(Session s) throws Exception {
        long id = extractLong(s.post("/diaries",
                "{\"title\":\"" + RUN + " 待删除\",\"content\":\"待删除的正文\"}").body(), "id");

        expect("9.1 删除自己的日记 → 200/0", 200, 0, s.delete("/diaries/" + id));
        expect("9.2 删除后详情 → 404/40401", 404, 40401, s.get("/diaries/" + id));
        // 重复删除：诚实反映"已不存在"，而不是假装成功
        expect("9.3 重复删除 → 404/40401", 404, 40401, s.delete("/diaries/" + id));

        check("9.4 已删除的日记不再出现在列表里",
                !s.get("/diaries?page=0&size=100").body().contains("待删除"),
                "列表里还有已删除的日记");
    }

    // ══════════════════════════════════════════════════════════
    // 会话封装
    // ══════════════════════════════════════════════════════════

    /** 一个独立会话（自己的 Cookie 罐）= 一个"浏览器"。 */
    static class Session {
        private final HttpClient client;
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

        Session() {
            this.client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .version(HttpClient.Version.HTTP_1_1)
                    .cookieHandler(cookies)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        }

        /** 取本会话的会话 ID（用于确认两个会话确实不同）。 */
        String sessionValue() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(c -> c.getName().contains("SESSION"))
                    .map(HttpCookie::getValue)
                    .findFirst().orElse(null);
        }

        HttpResponse<String> get(String path) throws Exception {
            return client.send(HttpRequest.newBuilder()
                            .uri(URI.create(BASE + path))
                            .timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        HttpResponse<String> post(String path, String json) throws Exception {
            return send("POST", path, json);
        }

        HttpResponse<String> put(String path, String json) throws Exception {
            return send("PUT", path, json);
        }

        HttpResponse<String> delete(String path) throws Exception {
            return client.send(HttpRequest.newBuilder()
                            .uri(URI.create(BASE + path))
                            .timeout(Duration.ofSeconds(20)).DELETE().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        private HttpResponse<String> send(String method, String path, String json) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + path))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(20));
            if (json == null || json.isEmpty()) {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                b.method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            }
            return client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }

    // ══════════════════════════════════════════════════════════
    // 工具
    // ══════════════════════════════════════════════════════════

    /**
     * 把输出同时写到两个流（控制台 + 文件）。
     *
     * <p>这样"边跑边看"和"留下 UTF-8 日志"两个需求都能满足，
     * 而不需要像早期的 DemoDiaryApi 那样"只写文件、控制台什么都看不到"。
     */
    static class TeeStream extends java.io.OutputStream {
        private final java.io.OutputStream a;
        private final java.io.OutputStream b;

        TeeStream(java.io.OutputStream a, java.io.OutputStream b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public void write(int c) throws java.io.IOException {
            a.write(c);
            b.write(c);
        }

        @Override
        public void write(byte[] buf, int off, int len) throws java.io.IOException {
            a.write(buf, off, len);
            b.write(buf, off, len);
        }

        @Override
        public void flush() throws java.io.IOException {
            a.flush();
            b.flush();
        }
    }

    /** 从 JSON 里取第一个 {@code "field":123}。 */
    private static long extractLong(String json, String field) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    private static String enc(String v) {
        return java.net.URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static String shorten(String s) {
        return s == null ? "(无)" : s.substring(0, Math.min(8, s.length()));
    }

    private static String snippet(String s) {
        if (s == null) return "(空)";
        return s.length() > 170 ? s.substring(0, 170) + "..." : s;
    }

    private static void expect(String name, int http, int code, HttpResponse<String> resp) {
        int ahttp = resp.statusCode();
        int acode = extractCode(resp.body());
        if (ahttp == http && acode == code) {
            pass(name + "  [HTTP=" + ahttp + "]");
        } else {
            fail(name, "期望 HTTP=" + http + "/code=" + code + "，实际 HTTP=" + ahttp
                    + "/code=" + acode + "  body=" + snippet(resp.body()));
        }
    }

    private static int extractCode(String body) {
        int i = body.indexOf("\"code\":");
        if (i < 0) return Integer.MIN_VALUE;
        int start = i + 7, end = start;
        while (end < body.length()
                && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '-')) end++;
        try {
            return Integer.parseInt(body.substring(start, end));
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    private static HttpResponse<String> rawGet(String url) throws Exception {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build()
                .send(HttpRequest.newBuilder().uri(URI.create(url))
                                .timeout(Duration.ofSeconds(15)).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void section(String title) {
        System.out.println("\n── " + title + " ──");
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass(name);
        } else {
            fail(name, detail);
        }
    }

    private static void pass(String n) {
        passed++;
        System.out.println("  [PASS] " + n);
    }

    private static void fail(String n, String d) {
        failed++;
        System.out.println("  [FAIL] " + n + " —— " + d);
    }
}
