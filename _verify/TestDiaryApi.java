import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ============================================================
 * 模块 2-3（Diary Service + API）验收测试
 * ============================================================
 *
 * <h2>这个测试验什么</h2>
 *
 * <p>模块 2-1 是纯函数（单元测试够用），2-2 是 SQL（JDBC + MyBatis 够用），
 * 而 2-3 是<b>HTTP 接口</b> —— 只有真正发请求才能验证：
 *
 * <ul>
 *   <li><b>状态码与业务码是否正确配对</b>（40001→400、40401→404、40901→409）
 *       —— 只看代码很容易把业务码写对、HTTP 状态码写错</li>
 *   <li><b>Cookie 会话是否真的起作用</b>（跨请求保持登录）</li>
 *   <li><b>跨用户越权是否真的被挡住</b> —— 这是 Phase 1 做不了、
 *       Phase 2 必须补上的验收（见开发文档 §4.7）</li>
 *   <li><b>JSON 字段名是否与前端一致</b>（snake_case、带 Z 的时间）</li>
 *   <li><b>密码学决定的行为</b>：keyword 搜不到正文（因为正文是密文）</li>
 * </ul>
 *
 * <h2>⚠️ 前置条件</h2>
 * <pre>
 * 1. MySQL 容器跑着（3307）
 * 2. 后端跑着：cd backend ; mvn -o -DskipTests spring-boot:run
 * </pre>
 *
 * <h2>运行方式</h2>
 * <pre>
 * cd D:\summerDiary\_verify
 * $env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
 * &amp; "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 TestDiaryApi.java
 * &amp; "$env:JAVA_HOME\bin\java.exe" '-Dfile.encoding=UTF-8' TestDiaryApi
 * </pre>
 *
 * <h2>数据清理</h2>
 *
 * <p>本测试通过<b>真实的 HTTP 接口</b>创建数据，所以<b>会留下数据</b>
 * （不能像 2-2 那样用事务回滚 —— 事务边界在服务端）。
 *
 * <p>处理方式：所有测试数据用唯一前缀 {@code ZZ_API_<时间戳>}，
 * 跑完打印清理用的 SQL，需要时手动执行。
 * 测试账号也用带时间戳的用户名，不与你的账号冲突。
 */
public class TestDiaryApi {

    private static final String BASE = "http://localhost:8080/api";

    /** 本次运行的唯一标识，用于避免与库里已有数据冲突 */
    private static final String RUN = "ZZAPI" + (System.currentTimeMillis() % 100000);

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("本次运行标识: " + RUN);
        System.out.println("（测试数据用此前缀，可据此清理）");

        // 两个独立会话，用于跨用户越权测试
        Session alice = new Session();
        Session bob = new Session();

        try {
            section("零、前置检查");
            check("0.1 后端在 8080 上可用", healthOk());

            section("一、准备两个用户（跨用户测试的前提）");
            prepareUsers(alice, bob);

            section("二、创建日记");
            long aliceDiary = testCreate(alice);

            section("三、详情（含解密）");
            testDetail(alice, aliceDiary);

            section("四、分页与筛选");
            testList(alice);

            section("五、keyword 只搜标题（密码学决定的）");
            testKeywordOnlyTitle(alice);

            section("六、修改（PUT 整体替换语义）");
            testUpdate(alice, aliceDiary);

            section("七、★重点★ 跨用户越权：A 碰 B 的日记一律 404");
            testCrossUserDiary(alice, bob);

            section("八、标签：创建、复用、列表");
            testTags(alice, bob);

            section("九、★重点★ 跨用户越权：标签相关");
            testCrossUserTags(alice, bob);

            section("十、删除（软删除）");
            testDelete(alice, bob);
        } finally {
            System.out.println();
            System.out.println("本次产生的测试数据标识: " + RUN);
            System.out.println("如需清理，执行：");
            System.out.println("  DELETE dt FROM diary_tag dt JOIN diary d ON d.id=dt.diary_id "
                    + "WHERE d.title LIKE '" + RUN + "%';");
            System.out.println("  DELETE FROM diary WHERE title LIKE '" + RUN + "%';");
            System.out.println("  DELETE FROM tag WHERE name LIKE '" + RUN + "%';");
            System.out.println("  DELETE FROM `user` WHERE username LIKE '" + RUN.toLowerCase() + "%';");
        }

        System.out.println();
        System.out.println("通过: " + passed + "   失败: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ══════════════════════════════════════════════════════════
    private static void prepareUsers(Session alice, Session bob) throws Exception {
        String a = RUN.toLowerCase() + "_a";
        String b = RUN.toLowerCase() + "_b";

        Resp ra = alice.post("/auth/register",
                "{\"username\":\"" + a + "\",\"password\":\"pw123456\",\"nickname\":\"甲\"}");
        check("1.1 注册用户 A 成功（201）", ra.status == 201, "HTTP=" + ra.status + " body=" + ra.shortBody());

        Resp rb = bob.post("/auth/register",
                "{\"username\":\"" + b + "\",\"password\":\"pw123456\",\"nickname\":\"乙\"}");
        check("1.2 注册用户 B 成功（201）", rb.status == 201, "HTTP=" + rb.status + " body=" + rb.shortBody());

        Resp la = alice.post("/auth/login",
                "{\"username\":\"" + a + "\",\"password\":\"pw123456\"}");
        check("1.3 用户 A 登录成功并拿到会话 Cookie",
                la.status == 200 && la.setCookie != null,
                "HTTP=" + la.status + " setCookie=" + (la.setCookie != null));

        // ★ 回归断言：/auth/me 的时间字段必须带 Z
        //
        // 模块 2-3 发现 UserResponse 用的是无时区 LocalDateTime，
        // 序列化成 "2026-09-23T10:58:36"（少 Z），
        // 而 DiaryResponse 用 Instant 输出 "2026-09-23T10:58:36Z"。
        // 同一份 API 里两种格式 —— 前端必须为前者写额外容错。
        // 已把 UserResponse 也改成 Instant，这条断言防止回归。
        Resp meForTz = alice.get("/auth/me");
        check("1.5b ★★ /auth/me 的 created_at 带 Z 后缀（与日记接口格式一致）",
                containsZTimestamp(meForTz.body, "created_at"),
                "body=" + meForTz.shortBody()
                        + " —— 少了 Z 会让不写容错的客户端把 UTC 当本地时间，偏移 8 小时且不报错");

        Resp lb = bob.post("/auth/login",
                "{\"username\":\"" + b + "\",\"password\":\"pw123456\"}");
        check("1.4 用户 B 登录成功", lb.status == 200 && lb.setCookie != null,
                "HTTP=" + lb.status);

        // 确认会话生效
        Resp meA = alice.get("/auth/me");
        check("1.5 A 的会话有效（/auth/me 返回 200）",
                meA.status == 200 && meA.body.contains("\"code\":0"), "HTTP=" + meA.status);

        // 未登录访问日记接口必须 401（SecurityConfig 的 authenticated 规则）
        Session anon = new Session();
        Resp anonList = anon.get("/diaries");
        check("1.6 ★ 未登录访问 /api/diaries → 401 + code=40101",
                anonList.status == 401 && anonList.body.contains("40101"),
                "HTTP=" + anonList.status + " body=" + anonList.shortBody());
    }

    // ══════════════════════════════════════════════════════════
    private static long testCreate(Session s) throws Exception {
        String title = RUN + " 海边的傍晚";
        String content = "今天去海边走了走，风很大。\n第二行内容，用来验证换行与中文都能原样存取。";

        String json = """
                {
                  "title": "%s",
                  "content": "%s",
                  "mood": "平静",
                  "weather": "晴",
                  "location": "海边",
                  "tag_ids": []
                }
                """.formatted(title, content.replace("\n", "\\n"));

        Resp r = s.post("/diaries", json);
        check("2.1 创建日记返回 201", r.status == 201, "HTTP=" + r.status + " body=" + r.shortBody());
        check("2.2 业务码 code=0", r.body.contains("\"code\":0"));

        long id = extractLong(r.body, "id");
        check("2.3 响应含新建日记的 id", id > 0, "id=" + id);

        check("2.4 ★ 响应里的 content 是【明文】（自动解密生效）",
                r.body.contains("今天去海边走了走"),
                "如果这里是密文或 null，说明解密链路没接上");
        check("2.5 标题原样返回",
                r.body.contains("海边的傍晚"));
        check("2.6 ★ created_at 带 Z 后缀（ISO-8601 UTC，符合契约）",
                containsZTimestamp(r.body, "created_at"),
                "实际响应片段: " + r.snippet());
        check("2.7 ★ analysis_status 存在且为 null（Phase 2 约定的占位字段）",
                r.body.contains("\"analysis_status\":null"),
                "实际响应片段: " + r.snippet());
        check("2.8 响应不含内部字段 userId / deleted / contentCiphertext",
                !r.body.contains("userId") && !r.body.contains("contentCiphertext")
                        && !r.body.contains("deleted"),
                "泄露了内部字段");

        // 标题为空 → 40001
        Resp bad = s.post("/diaries",
                "{\"title\":\"\",\"content\":\"x\"}");
        check("2.9 标题为空 → 400 + code=40001",
                bad.status == 400 && bad.body.contains("40001"),
                "HTTP=" + bad.status + " body=" + bad.shortBody());

        // 标题超长（>200）→ 40001
        Resp tooLong = s.post("/diaries",
                "{\"title\":\"" + "z".repeat(201) + "\",\"content\":\"x\"}");
        check("2.10 标题超 200 字 → 400 + code=40001",
                tooLong.status == 400 && tooLong.body.contains("40001"),
                "HTTP=" + tooLong.status);

        // content 字段缺失 → 40001（@NotNull）
        Resp noContent = s.post("/diaries", "{\"title\":\"只有标题\"}");
        check("2.11 缺少 content 字段 → 400 + code=40001",
                noContent.status == 400 && noContent.body.contains("40001"),
                "HTTP=" + noContent.status + " body=" + noContent.shortBody());

        // 只写标题、content 为空串 → 合法
        Resp emptyContent = s.post("/diaries",
                "{\"title\":\"" + RUN + " 只有标题\",\"content\":\"\"}");
        check("2.12 ★ content 为空串是合法的（只记心情不写正文）→ 201",
                emptyContent.status == 201,
                "HTTP=" + emptyContent.status + " body=" + emptyContent.shortBody());

        return id;
    }

    // ══════════════════════════════════════════════════════════
    private static void testDetail(Session s, long id) throws Exception {
        Resp r = s.get("/diaries/" + id);
        check("3.1 详情返回 200", r.status == 200, "HTTP=" + r.status);
        check("3.2 ★ 正文解密后与写入一致（含换行）",
                r.body.contains("今天去海边走了走") && r.body.contains("第二行内容"),
                "body=" + r.shortBody());
        check("3.3 心情/天气/地点原样返回",
                r.body.contains("\"mood\":\"平静\"")
                        && r.body.contains("\"weather\":\"晴\"")
                        && r.body.contains("\"location\":\"海边\""),
                "body=" + r.shortBody());
        check("3.4 tags 是数组（无标签时为空数组，不是 null）",
                r.body.contains("\"tags\":[]"), "body=" + r.shortBody());

        Resp notFound = s.get("/diaries/999999999");
        check("3.5 不存在的日记 → 404 + code=40401",
                notFound.status == 404 && notFound.body.contains("40401"),
                "HTTP=" + notFound.status + " body=" + notFound.shortBody());

        Resp badType = s.get("/diaries/abc");
        check("3.6 非数字 ID → 400 + code=40001（不是 500）",
                badType.status == 400 && badType.body.contains("40001"),
                "HTTP=" + badType.status + " body=" + badType.shortBody());
    }

    // ══════════════════════════════════════════════════════════
    private static void testList(Session s) throws Exception {
        Resp r = s.get("/diaries?page=0&size=20");
        check("4.1 列表返回 200", r.status == 200, "HTTP=" + r.status);
        check("4.2 分页结构字段齐全（items/page/size/total/hasNext）",
                r.body.contains("\"items\"") && r.body.contains("\"page\"")
                        && r.body.contains("\"size\"") && r.body.contains("\"total\"")
                        && r.body.contains("\"hasNext\""),
                "body=" + r.shortBody());
        check("4.3 ★ 列表项的 content 为 null（列表不解密正文）",
                r.body.contains("\"content\":null"),
                "如果列表返回了明文正文，说明安全取舍没生效。body=" + r.shortBody());

        // size 超限 → 40001（Service 强制）
        Resp oversize = s.get("/diaries?page=0&size=101");
        check("4.4 ★ size=101 超过上限 → 400 + code=40001",
                oversize.status == 400 && oversize.body.contains("40001"),
                "HTTP=" + oversize.status + " body=" + oversize.shortBody());

        Resp zeroSize = s.get("/diaries?page=0&size=0");
        check("4.5 size=0 → 400 + code=40001",
                zeroSize.status == 400 && zeroSize.body.contains("40001"),
                "HTTP=" + zeroSize.status);

        Resp negPage = s.get("/diaries?page=-1&size=20");
        check("4.6 page=-1 → 400 + code=40001",
                negPage.status == 400 && negPage.body.contains("40001"),
                "HTTP=" + negPage.status);

        // size=100 边界值应通过
        Resp boundary = s.get("/diaries?page=0&size=100");
        check("4.7 size=100（边界值）→ 200（上限是闭区间）",
                boundary.status == 200, "HTTP=" + boundary.status);

        // 心情筛选
        Resp byMood = s.get("/diaries?page=0&size=20&mood=" + enc("平静"));
        check("4.8 按心情筛选返回 200 且命中",
                byMood.status == 200 && byMood.body.contains("海边的傍晚"),
                "HTTP=" + byMood.status);

        // 时间范围筛选
        Resp byRange = s.get("/diaries?page=0&size=20&from=2000-01-01T00:00:00&to=2099-12-31T23:59:59");
        check("4.9 时间范围筛选返回 200", byRange.status == 200, "HTTP=" + byRange.status);

        // 非法时间格式 → 40001
        Resp badTime = s.get("/diaries?page=0&size=20&from=not-a-date");
        check("4.10 非法时间格式 → 400 + code=40001",
                badTime.status == 400 && badTime.body.contains("40001"),
                "HTTP=" + badTime.status + " body=" + badTime.shortBody());

        check("4.11 ★ 列表里没有出现别人的日记（仅自己数据）",
                !byRange.body.contains("ZZAPI") || !byRange.body.contains("\"title\":\"ZZAPI_b"),
                "疑似包含他人数据");
    }

    // ══════════════════════════════════════════════════════════
    private static void testKeywordOnlyTitle(Session s) throws Exception {
        // 用正文里的词搜 → 应该搜不到（正文是密文，LIKE 匹配不到）
        Resp byContent = s.get("/diaries?page=0&size=20&keyword=" + enc("风很大"));
        boolean noContentHit = !byContent.body.contains("海边的傍晚");
        check("5.1 ★★ 用正文里的词搜 → 搜不到（证明 keyword 只搜标题）",
                noContentHit,
                "搜到了。若真能搜到正文，说明正文被明文存储了 —— 那是严重的安全回归。"
                        + "body=" + byContent.shortBody());

        // 用标题里的词搜 → 应该搜得到
        Resp byTitle = s.get("/diaries?page=0&size=20&keyword=" + enc("海边的傍晚"));
        check("5.2 用标题里的词搜 → 搜得到（对照组，证明 5.1 不是搜索本身坏了）",
                byTitle.body.contains("海边的傍晚"),
                "body=" + byTitle.shortBody());
    }

    // ══════════════════════════════════════════════════════════
    private static void testUpdate(Session s, long id) throws Exception {
        String json = """
                {
                  "title": "%s 改过的标题",
                  "content": "改过的正文内容",
                  "mood": "开心"
                }
                """.formatted(RUN);

        Resp r = s.put("/diaries/" + id, json);
        check("6.1 修改返回 200", r.status == 200, "HTTP=" + r.status + " body=" + r.shortBody());
        check("6.2 标题已更新", r.body.contains("改过的标题"), "body=" + r.shortBody());
        check("6.3 ★ 正文已更新且为新明文", r.body.contains("改过的正文内容"),
                "body=" + r.shortBody());
        check("6.4 ★ PUT 整体替换：未传的 weather/location 被清空为 null",
                r.body.contains("\"weather\":null") && r.body.contains("\"location\":null"),
                "body=" + r.shortBody());

        // 确认落库（重新查一次）
        Resp detail = s.get("/diaries/" + id);
        check("6.5 重新查询确认修改已持久化",
                detail.body.contains("改过的标题") && detail.body.contains("改过的正文内容"),
                "body=" + detail.shortBody());

        Resp notFound = s.put("/diaries/999999999",
                "{\"title\":\"x\",\"content\":\"y\"}");
        check("6.6 修改不存在的日记 → 404 + code=40401",
                notFound.status == 404 && notFound.body.contains("40401"),
                "HTTP=" + notFound.status);
    }

    // ══════════════════════════════════════════════════════════
    private static void testCrossUserDiary(Session alice, Session bob) throws Exception {
        // B 先创建自己的日记
        Resp created = bob.post("/diaries",
                "{\"title\":\"" + RUN + " B的日记\",\"content\":\"B 的私密正文\"}");
        long bobDiary = extractLong(created.body, "id");
        check("7.0 B 成功创建自己的日记", bobDiary > 0, "id=" + bobDiary);

        // ★ A 读 B 的日记 → 404（不是 403！）
        Resp read = alice.get("/diaries/" + bobDiary);
        check("7.1 ★★ A 读 B 的日记 → 404 + code=40401（不用 403，防枚举）",
                read.status == 404 && read.body.contains("40401"),
                "HTTP=" + read.status + " body=" + read.shortBody());

        // ★ 404 响应里绝不能含 B 的正文
        check("7.2 ★★ 越权响应里不含 B 的正文或标题",
                !read.body.contains("B 的私密正文") && !read.body.contains("B的日记"),
                "泄露了 B 的数据！body=" + read.shortBody());

        // ★ A 改 B 的日记
        Resp update = alice.put("/diaries/" + bobDiary,
                "{\"title\":\"A 篡改的标题\",\"content\":\"A 篡改的正文\"}");
        check("7.3 ★★ A 改 B 的日记 → 404 + code=40401",
                update.status == 404 && update.body.contains("40401"),
                "HTTP=" + update.status);

        // 复核 B 的日记没被改
        Resp bobCheck = bob.get("/diaries/" + bobDiary);
        check("7.4 ★ A 的篡改没有生效（B 的日记内容未变）",
                bobCheck.body.contains("B的日记") && bobCheck.body.contains("B 的私密正文"),
                "body=" + bobCheck.shortBody());

        // ★ A 删 B 的日记
        Resp delete = alice.delete("/diaries/" + bobDiary);
        check("7.5 ★★ A 删 B 的日记 → 404 + code=40401",
                delete.status == 404 && delete.body.contains("40401"),
                "HTTP=" + delete.status);

        // 复核 B 的日记还在
        Resp stillThere = bob.get("/diaries/" + bobDiary);
        check("7.6 ★ B 的日记没有被删掉", stillThere.status == 200,
                "HTTP=" + stillThere.status + " —— 若为 404 说明越权删除成功了！");

        // ★ A 的列表里不应出现 B 的日记
        Resp listA = alice.get("/diaries?page=0&size=100");
        check("7.7 ★★ A 的列表里不含 B 的日记",
                !listA.body.contains("\"title\":\"" + RUN + " B的日记\""),
                "列表里出现了别人的日记！");
    }

    // ══════════════════════════════════════════════════════════
    private static void testTags(Session alice, Session bob) throws Exception {
        Resp create = alice.post("/tags", "{\"name\":\"" + RUN + "_读书\"}");
        check("8.1 创建标签返回 201", create.status == 201,
                "HTTP=" + create.status + " body=" + create.shortBody());
        long tagId = extractLong(create.body, "id");
        check("8.2 返回标签 id", tagId > 0, "id=" + tagId);

        // ★ 回归断言：创建标签时 created_at 不能为 null
        //
        // 这个缺陷逃过了当时全部 77 条断言 —— 因为没有任何一条检查过
        // created_at。是跑 DemoDiaryApi 打印真实响应时肉眼发现的：
        //   第一次创建 → "created_at":null          ← 错（insert 后直接用内存对象，
        //   第二次复用 → "created_at":"2026-...Z"     而 created_at 是数据库填的）
        //
        // 教训：**断言要覆盖响应里的每一个字段**，
        // 没被断言的字段就是"没人看的荒地"，坏了也没人知道。
        check("8.2b ★★ 创建标签时 created_at 不为 null（数据库默认值已回读）",
                containsZTimestamp(create.body, "created_at"),
                "body=" + create.shortBody()
                        + " —— created_at 为 null 说明 insert 后没回读，"
                        + "而 created_at 是数据库 DEFAULT CURRENT_TIMESTAMP 填的");

        // ★ 同名再创建 → 复用同一个 id（不是 409 冲突）
        Resp again = alice.post("/tags", "{\"name\":\"" + RUN + "_读书\"}");
        long sameId = extractLong(again.body, "id");
        check("8.3 ★★ 同名标签再创建 → 复用同一个 id（幂等，不报 409）",
                again.status == 201 && sameId == tagId,
                "第一次 id=" + tagId + " 第二次 id=" + sameId + " HTTP=" + again.status);

        // 列表
        Resp list = alice.get("/tags");
        check("8.4 标签列表返回 200 且含刚建的标签",
                list.status == 200 && list.body.contains("_读书"),
                "HTTP=" + list.status + " body=" + list.shortBody());
        check("8.5 ★ 标签响应不含 userId（内部字段不外泄）",
                !list.body.contains("userId"), "泄露了 userId");

        // 空标签名 → 40001
        Resp empty = alice.post("/tags", "{\"name\":\"\"}");
        check("8.6 标签名为空 → 400 + code=40001",
                empty.status == 400 && empty.body.contains("40001"),
                "HTTP=" + empty.status);

        // 非法字符 → 40001
        Resp illegal = alice.post("/tags", "{\"name\":\"a<b>c\"}");
        check("8.7 标签名含非法字符 → 400 + code=40001",
                illegal.status == 400 && illegal.body.contains("40001"),
                "HTTP=" + illegal.status);

        // 超长 → 40001
        Resp tooLong = alice.post("/tags", "{\"name\":\"" + "z".repeat(31) + "\"}");
        check("8.8 标签名超 30 字 → 400 + code=40001",
                tooLong.status == 400 && tooLong.body.contains("40001"),
                "HTTP=" + tooLong.status);

        // ★ B 可以创建同名标签（唯一键是 (user_id, name)）
        Resp bobSame = bob.post("/tags", "{\"name\":\"" + RUN + "_读书\"}");
        long bobTagId = extractLong(bobSame.body, "id");
        check("8.9 ★★ B 可以创建同名标签（标签按用户隔离）",
                bobSame.status == 201 && bobTagId > 0 && bobTagId != tagId,
                "A 的 id=" + tagId + " B 的 id=" + bobTagId);

        // 建一篇带标签的日记，验证标签真的关联上了
        Resp withTags = alice.post("/diaries",
                "{\"title\":\"" + RUN + " 带标签的日记\",\"content\":\"正文\",\"tag_ids\":["
                        + tagId + "]}");
        check("8.10 创建带标签的日记返回 201", withTags.status == 201,
                "HTTP=" + withTags.status + " body=" + withTags.shortBody());
        check("8.11 ★ 详情里能看到关联的标签",
                withTags.body.contains("_读书"), "body=" + withTags.shortBody());

        // ★ 用别人的 tagId 建日记 → 40401
        Resp crossTag = alice.post("/diaries",
                "{\"title\":\"" + RUN + " 用别人的标签\",\"content\":\"x\",\"tag_ids\":["
                        + bobTagId + "]}");
        check("8.12 ★★ A 用 B 的 tagId 建日记 → 404 + code=40401",
                crossTag.status == 404 && crossTag.body.contains("40401"),
                "HTTP=" + crossTag.status + " body=" + crossTag.shortBody());

        // ★ 重复 tagId 不应导致 500（去重生效）
        Resp dup = alice.post("/diaries",
                "{\"title\":\"" + RUN + " 重复标签\",\"content\":\"x\",\"tag_ids\":["
                        + tagId + "," + tagId + "]}");
        check("8.13 ★★ 重复的 tagId → 201（Service 去重，不是 500）",
                dup.status == 201,
                "HTTP=" + dup.status + " body=" + dup.shortBody()
                        + " —— 若为 500 说明重复 ID 撞了 diary_tag 联合主键");

        // ★ 被日记使用的标签不能删
        Resp delUsed = alice.delete("/tags/" + tagId);
        check("8.14 ★★ 删除正在被使用的标签 → 409 + code=40901 + 提示篇数",
                delUsed.status == 409 && delUsed.body.contains("40901")
                        && delUsed.body.contains("篇日记使用"),
                "HTTP=" + delUsed.status + " body=" + delUsed.shortBody());

        // 未被使用的标签可以删
        Resp spare = alice.post("/tags", "{\"name\":\"" + RUN + "_闲置标签\"}");
        long spareId = extractLong(spare.body, "id");
        Resp delSpare = alice.delete("/tags/" + spareId);
        check("8.15 ★ 删除未被使用的标签 → 200 + code=0",
                delSpare.status == 200 && delSpare.body.contains("\"code\":0"),
                "HTTP=" + delSpare.status + " body=" + delSpare.shortBody());
    }

    // ══════════════════════════════════════════════════════════
    private static void testCrossUserTags(Session alice, Session bob) throws Exception {
        // B 建一个自己的标签
        Resp bobTag = bob.post("/tags", "{\"name\":\"" + RUN + "_B专属标签\"}");
        long bobTagId = extractLong(bobTag.body, "id");

        // ★ A 删 B 的标签 → 40401
        Resp del = alice.delete("/tags/" + bobTagId);
        check("9.1 ★★ A 删 B 的标签 → 404 + code=40401",
                del.status == 404 && del.body.contains("40401"),
                "HTTP=" + del.status);

        // 复核 B 的标签还在
        Resp bobList = bob.get("/tags");
        check("9.2 ★ B 的标签没有被删掉", bobList.body.contains("_B专属标签"),
                "B 的标签消失了！");

        // ★ A 用 B 的 tagId 做筛选 → 不应筛出自己的日记
        Resp filter = alice.get("/diaries?page=0&size=20&tag_id=" + bobTagId);
        check("9.3 ★★ A 用 B 的 tagId 筛选 → 200 且结果不含 A 的日记",
                filter.status == 200 && !filter.body.contains(RUN + " 带标签的日记"),
                "HTTP=" + filter.status + " body=" + filter.shortBody());

        // ★★ 回归测试：用【自己的】tagId 筛选必须真的筛出结果
        //
        // 为什么专门加这一条：9.3 只验证了"不该出现的不出现"。
        // 如果 tag_id 参数压根没被绑定（曾经的真实 bug ——
        // @ModelAttribute 绑不上 snake_case 的 tag_id），
        // 筛选会静默失效、返回全部日记，9.3 依然会"通过"，
        // 因为 A 的日记里恰好没有 B 的标签 —— 假阴性。
        //
        // 只有"用合法 tagId 必须筛得到"这条断言能真正锁住筛选功能。
        Resp ownTag = alice.post("/tags", "{\"name\":\"" + RUN + "_筛选验证\"}");
        long ownTagId = extractLong(ownTag.body, "id");
        alice.post("/diaries",
                "{\"title\":\"" + RUN + " 筛选命中目标\",\"content\":\"x\",\"tag_ids\":["
                        + ownTagId + "]}");

        Resp byTag = alice.get("/diaries?page=0&size=20&tag_id=" + ownTagId);
        check("9.4 ★★ 用【自己的】tag_id 筛选 → 筛得到那一篇（tag_id 参数真的被绑定）",
                byTag.status == 200 && byTag.body.contains("筛选命中目标"),
                "HTTP=" + byTag.status + " body=" + byTag.shortBody()
                        + " —— 若返回全部日记或找不到目标，说明 tag_id 参数没被绑定，"
                        + "筛选静默失效");

        // 对照组：用自己另一个【没有关联任何日记】的标签筛选 → 应为空
        Resp unused = alice.post("/tags", "{\"name\":\"" + RUN + "_无关联\"}");
        long unusedTagId = extractLong(unused.body, "id");
        Resp byUnused = alice.get("/diaries?page=0&size=20&tag_id=" + unusedTagId);
        check("9.5 用没有关联日记的 tag_id 筛选 → 结果为空（不是返回全部）",
                byUnused.status == 200 && byUnused.body.contains("\"items\":[]"),
                "HTTP=" + byUnused.status + " body=" + byUnused.shortBody()
                        + " —— 若返回了内容，说明筛选条件没生效");
    }

    // ══════════════════════════════════════════════════════════
    private static void testDelete(Session alice, Session bob) throws Exception {
        // A 建一篇专门用来删的
        Resp created = alice.post("/diaries",
                "{\"title\":\"" + RUN + " 待删除\",\"content\":\"待删除的正文\"}");
        long toDelete = extractLong(created.body, "id");

        // ★ B 先试删 A 的 → 40401
        Resp bobDel = bob.delete("/diaries/" + toDelete);
        check("10.1 ★★ B 删 A 的日记 → 404 + code=40401",
                bobDel.status == 404, "HTTP=" + bobDel.status);

        // A 自己删 → 200
        Resp del = alice.delete("/diaries/" + toDelete);
        check("10.2 A 删自己的日记 → 200 + code=0",
                del.status == 200 && del.body.contains("\"code\":0"),
                "HTTP=" + del.status + " body=" + del.shortBody());

        // 删除后查不到
        Resp after = alice.get("/diaries/" + toDelete);
        check("10.3 删除后详情 → 404 + code=40401",
                after.status == 404 && after.body.contains("40401"),
                "HTTP=" + after.status);

        // 重复删除 → 404（幂等地表现为"已经不存在"）
        Resp again = alice.delete("/diaries/" + toDelete);
        check("10.4 重复删除 → 404 + code=40401（不是 200，诚实反映已不存在）",
                again.status == 404, "HTTP=" + again.status);

        // ★ 删除后标签应"释放"：B 的标签仍在使用中（B 的日记还没删），
        //    但 A 删掉那篇"带标签的日记"后，A 的标签就应该可删了
        Resp listAfterDelete = alice.get("/diaries?page=0&size=100");
        check("10.5 删除的日记不再出现在列表里",
                !listAfterDelete.body.contains("待删除"),
                "列表里还有已删除的日记");
    }

    // ══════════════════════════════════════════════════════════
    // HTTP 会话封装
    // ══════════════════════════════════════════════════════════

    /** 一个独立的浏览器会话（自己的 Cookie 罐）。 */
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
                    .header("Accept", "application/json")
                    .GET().build());
        }

        Resp post(String path, String json) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(BASE + path))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build());
        }

        Resp put(String path, String json) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(BASE + path))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build());
        }

        Resp delete(String path) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(BASE + path))
                    .DELETE().build());
        }

        private Resp send(HttpRequest request) throws Exception {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String setCookie = response.headers().firstValue("Set-Cookie").orElse(null);
            return new Resp(response.statusCode(), response.body(), setCookie);
        }
    }

    /** 一次响应。 */
    record Resp(int status, String body, String setCookie) {
        String shortBody() {
            String b = body == null ? "" : body;
            return b.length() > 300 ? b.substring(0, 300) + "..." : b;
        }

        String snippet() {
            String b = body == null ? "" : body;
            return b.length() > 160 ? b.substring(0, 160) + "..." : b;
        }
    }

    // ══════════════════════════════════════════════════════════
    // 工具
    // ══════════════════════════════════════════════════════════

    private static boolean healthOk() {
        try {
            HttpClient c = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1).build();
            HttpResponse<String> r = c.send(
                    HttpRequest.newBuilder(URI.create(BASE + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** 从 JSON 里取第一个 {@code "name":123} 形式的整数值。 */
    private static long extractLong(String json, String field) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /** 检查形如 {@code "created_at":"2026-...Z"} 的字段是否存在。 */
    private static boolean containsZTimestamp(String json, String field) {
        return Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"[^\"]*Z\"")
                .matcher(json).find();
    }

    /** URL 编码查询参数值。 */
    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("── " + title + " ──");
    }

    private static void check(String name, boolean ok) {
        check(name, ok, null);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name + (detail == null ? "" : "  → " + detail));
        }
    }
}
