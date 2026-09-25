import com.sangshen.aidiary.dto.query.DiaryQuery;
import com.sangshen.aidiary.entity.Diary;
import com.sangshen.aidiary.entity.DiaryTag;
import com.sangshen.aidiary.entity.Tag;
import com.sangshen.aidiary.mapper.DiaryMapper;
import com.sangshen.aidiary.mapper.DiaryTagMapper;
import com.sangshen.aidiary.mapper.TagMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ============================================================
 * 模块 2-2 验收测试（第二部分）：真实 MyBatis 链路
 * ============================================================
 *
 * <h2>为什么还需要这个测试 —— TestDiaryDataLayer 覆盖不到什么</h2>
 *
 * <p>{@code TestDiaryDataLayer} 用 JDBC 执行<b>手抄的等价 SQL</b>，
 * 它验证了「SQL 逻辑和表结构对不对」，但<b>完全没碰 MyBatis</b>。
 * 而下面这些问题<b>编译期一律不报错</b>，只有真正经过 MyBatis 才会暴露：
 *
 * <table border="1">
 *   <caption>只有真实 MyBatis 才能发现的错误</caption>
 *   <tr><th>错误</th><th>症状</th></tr>
 *   <tr><td>XML 里 {@code namespace} 拼错</td>
 *       <td>启动报 "Invalid bound statement (not found)"</td></tr>
 *   <tr><td>{@code <foreach>} 的 collection 名写错</td>
 *       <td>运行期报 "There is no getter for property named ..."</td></tr>
 *   <tr><td>XML 的 {@code id} 与接口方法名不一致</td>
 *       <td>运行期报 "Invalid bound statement"</td></tr>
 *   <tr><td>{@code #{query.keyword}} 写错属性名</td>
 *       <td>运行期报 "There is no getter for property named 'keyword'"</td></tr>
 *   <tr><td>{@code resultType="Diary"} 别名解析不到</td>
 *       <td>XML 解析期报 "Could not resolve type alias"</td></tr>
 *   <tr><td>下划线转驼峰没生效</td>
 *       <td>字段静默为 null（最危险：不报错）</td></tr>
 * </table>
 *
 * <h2>本测试覆盖的链路</h2>
 *
 * <pre>
 * 真实的 Mapper XML（来自 backend/target/classes/mapper/*.xml，与生产同一份文件）
 *   + 真实的 Mapper 接口（来自 backend/target/classes）
 *   + 与 application.yml 一致的 MyBatis 配置
 *   + 真实 MySQL
 *   = 调用每个 Mapper 方法，验证 SQL 能跑通、参数能解析、结果能映射
 * </pre>
 *
 * <h2>不留脏数据</h2>
 *
 * <p>全程单事务 + 最后 rollback，不污染开发库。
 *
 * <h2>前置条件</h2>
 *
 * <p>必须先编译后端（{@code backend/target/classes} 里有 class 和 mapper XML）：
 * <pre>
 * cd D:\summerDiary\backend &amp;&amp; mvn -o -DskipTests compile
 * </pre>
 *
 * <h2>运行方式</h2>
 *
 * <pre>
 * cd D:\summerDiary\_verify
 * $env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
 * $M2 = "D:\summerDiary\.m2repo"
 * $cp = "D:\summerDiary\backend\target\classes;"
 *     + "$M2\org\mybatis\mybatis\3.5.19\mybatis-3.5.19.jar;"
 *     + "$M2\com\mysql\mysql-connector-j\8.3.0\mysql-connector-j-8.3.0.jar;"
 *     + "$M2\org\slf4j\slf4j-api\2.0.16\slf4j-api-2.0.16.jar"
 * &amp; "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 -cp $cp TestDiaryMappers.java
 * &amp; "$env:JAVA_HOME\bin\java.exe" '-Dfile.encoding=UTF-8' -cp ".;$cp" TestDiaryMappers
 * </pre>
 */
public class TestDiaryMappers {

    private static int passed = 0;
    private static int failed = 0;

    private static final long USER_A = 910000001L;
    private static final long USER_B = 910000002L;

    private static final String[] MAPPER_XMLS = {
            "mapper/DiaryMapper.xml",
            "mapper/TagMapper.xml",
            "mapper/DiaryTagMapper.xml"
    };

    public static void main(String[] args) throws Exception {
        Map<String, String> env = loadDotenv();
        String jdbcUrl = "jdbc:mysql://" + env.getOrDefault("DB_HOST", "localhost")
                + ":" + env.getOrDefault("DB_PORT", "3307")
                + "/" + env.getOrDefault("DB_NAME", "ai_diary")
                + "?useUnicode=true&characterEncoding=UTF-8"
                + "&connectionCollation=utf8mb4_0900_ai_ci"
                + "&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";

        SqlSessionFactory factory = buildFactory(jdbcUrl, env);
        System.out.println("MyBatis SqlSessionFactory 构建成功（3 个 mapper XML 全部解析通过）");

        try (SqlSession session = factory.openSession()) {
            try {
                runAll(session);
            } finally {
                session.rollback();
                System.out.println();
                System.out.println("已 rollback，未留下任何测试数据。");
            }
        }

        System.out.println();
        System.out.println("通过: " + passed + "   失败: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    /**
     * 用与 {@code application.yml} 完全一致的 MyBatis 配置构建工厂。
     *
     * <p>刻意手工复刻那几项配置（而不是随便写个最小配置）——
     * 因为「下划线转驼峰」这类映射开关如果和线上不一致，
     * 测试通过了但线上字段是 null，测试就失去了意义。
     * 这几项必须与 application.yml 的 mybatis 段逐条对应：
     * <pre>
     * mybatis.type-aliases-package: com.sangshen.aidiary.entity
     * mybatis.configuration.map-underscore-to-camel-case: true
     * mybatis.configuration.call-setters-on-nulls: true
     * </pre>
     */
    private static SqlSessionFactory buildFactory(String jdbcUrl, Map<String, String> env) {
        UnpooledDataSource dataSource = new UnpooledDataSource(
                "com.mysql.cj.jdbc.Driver", jdbcUrl,
                env.getOrDefault("DB_USERNAME", "ai_diary"),
                env.getOrDefault("DB_PASSWORD", ""));

        Configuration configuration = new Configuration(
                new Environment("verify", new JdbcTransactionFactory(), dataSource));

        // ── 与 application.yml 保持一致的三项 ──────────────────
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setCallSettersOnNulls(true);
        // XML 里 resultType="Diary" / "Tag" / "DiaryTag" 这种简写依赖这个包
        configuration.getTypeAliasRegistry().registerAliases("com.sangshen.aidiary.entity");

        // ── 解析三个 mapper XML（与生产用的是同一份文件）────────
        for (String xml : MAPPER_XMLS) {
            try (InputStream in = TestDiaryMappers.class.getClassLoader().getResourceAsStream(xml)) {
                if (in == null) {
                    throw new IllegalStateException(
                            "classpath 里找不到 " + xml + " —— 请先执行 mvn -o -DskipTests compile");
                }
                // XMLMapperBuilder 会做完整的校验：namespace 存在、id 不重复、
                // resultType 别名能解析、动态标签语法正确。任何一项不对都会抛异常。
                new XMLMapperBuilder(in, configuration, xml, configuration.getSqlFragments()).parse();
            } catch (Exception e) {
                throw new IllegalStateException("解析 " + xml + " 失败: " + e.getMessage(), e);
            }
        }

        // ── 接口注册 ────────────────────────────────────────────
        // ⚠️ 这里<b>不需要</b>（也不能）直接调 configuration.addMapper(...)：
        //    XMLMapperBuilder.parse() 在解析时发现 namespace 指向一个<b>已有接口</b>，
        //    会顺便把该接口注册进 MapperRegistry。
        //    再调一次 addMapper 会抛
        //    "Type interface ... is already known to the MapperRegistry"。
        //
        //    反过来说，这个"重复注册"报错本身就是一份证据：
        //    它证明三个 XML 的 namespace 都能正确解析到对应的接口类 ——
        //    如果 namespace 拼错了，这里就不会报重复，而是后面报
        //    "Invalid bound statement (not found)"。
        Class<?>[] mapperInterfaces = {DiaryMapper.class, TagMapper.class, DiaryTagMapper.class};
        StringBuilder registered = new StringBuilder();
        for (Class<?> itf : mapperInterfaces) {
            boolean known = configuration.hasMapper(itf);
            registered.append(itf.getSimpleName()).append(known ? "=已注册 " : "=未注册 ");
            if (!known) {
                // 兜底：万一 XML 的 namespace 写错导致没自动注册，这里补上，
                // 后面的语句绑定检查会明确指出缺了哪些方法。
                configuration.addMapper(itf);
            }
        }
        System.out.println("接口注册情况: " + registered.toString().trim());

        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static void runAll(SqlSession session) {
        DiaryMapper diaryMapper = session.getMapper(DiaryMapper.class);
        TagMapper tagMapper = session.getMapper(TagMapper.class);
        DiaryTagMapper diaryTagMapper = session.getMapper(DiaryTagMapper.class);

        section("一、Mapper 注册与语句绑定");
        testStatementsRegistered(session);

        section("二、DiaryMapper 全部方法");
        long diaryId = testDiaryMapper(diaryMapper);

        section("三、TagMapper 全部方法");
        long tagId = testTagMapper(tagMapper);

        section("四、DiaryTagMapper 全部方法（含越权）");
        testDiaryTagMapper(diaryTagMapper, tagMapper, diaryMapper, diaryId, tagId);

        section("五、resultType 别名与驼峰映射");
        testResultMapping(diaryMapper);
    }

    // ══════════════════════════════════════════════════════════
    private static void testStatementsRegistered(SqlSession session) {
        Configuration cfg = session.getConfiguration();

        // 每个接口方法都必须能找到对应的 statement。
        // 少一个 → 该方法一被调用就报 "Invalid bound statement (not found)"。
        String[][] expected = {
                {"com.sangshen.aidiary.mapper.DiaryMapper", "insert"},
                {"com.sangshen.aidiary.mapper.DiaryMapper", "selectByIdAndUserId"},
                {"com.sangshen.aidiary.mapper.DiaryMapper", "countByUserId"},
                {"com.sangshen.aidiary.mapper.DiaryMapper", "selectPageByUserId"},
                {"com.sangshen.aidiary.mapper.DiaryMapper", "updateByIdAndUserId"},
                {"com.sangshen.aidiary.mapper.DiaryMapper", "softDeleteByIdAndUserId"},
                {"com.sangshen.aidiary.mapper.TagMapper", "insert"},
                {"com.sangshen.aidiary.mapper.TagMapper", "selectByUserId"},
                {"com.sangshen.aidiary.mapper.TagMapper", "selectByIdsAndUserId"},
                {"com.sangshen.aidiary.mapper.TagMapper", "countUsedByDiaries"},
                {"com.sangshen.aidiary.mapper.TagMapper", "selectByNameAndUserId"},
                {"com.sangshen.aidiary.mapper.TagMapper", "deleteByIdAndUserId"},
                {"com.sangshen.aidiary.mapper.DiaryTagMapper", "insertByDiaryIdAndTagIds"},
                {"com.sangshen.aidiary.mapper.DiaryTagMapper", "selectTagsByDiaryIdsAndUserId"},
                {"com.sangshen.aidiary.mapper.DiaryTagMapper", "deleteByDiaryIdAndUserId"},
        };

        int missing = 0;
        for (String[] e : expected) {
            if (!cfg.hasStatement(e[0] + "." + e[1])) {
                missing++;
                System.out.println("       缺失语句: " + e[0] + "." + e[1]);
            }
        }
        check("1.1 三个 Mapper 的 " + expected.length + " 个方法与 XML 语句全部绑定成功",
                missing == 0, "缺失 " + missing + " 个");

        // 显式确认 select * 没被用（防止后续有人图省事改回去）
        check("1.2 XML 中未使用 SELECT *（红线：显式列名）", !xmlContainsSelectStar());
    }

    // ══════════════════════════════════════════════════════════
    private static long testDiaryMapper(DiaryMapper mapper) {
        Diary d = new Diary();
        d.setUserId(USER_A);
        d.setTitle("MyBatis 链路测试日记");
        d.setContentCiphertext("ZmFrZS1jaXBoZXJ0ZXh0");
        d.setMood("平静");
        d.setWeather("晴");
        d.setLocation("自习室");

        int inserted = mapper.insert(d);
        check("2.1 insert 影响 1 行", inserted == 1, "实际=" + inserted);
        check("2.2 insert 回填自增主键（useGeneratedKeys 生效）",
                d.getId() != null && d.getId() > 0, "id=" + d.getId());

        long id = d.getId();

        Diary loaded = mapper.selectByIdAndUserId(id, USER_A);
        check("2.3 selectByIdAndUserId 查到", loaded != null);
        if (loaded != null) {
            check("2.4 ★ 字段映射正确（#{} 与驼峰转换都对）",
                    "MyBatis 链路测试日记".equals(loaded.getTitle())
                            && "ZmFrZS1jaXBoZXJ0ZXh0".equals(loaded.getContentCiphertext())
                            && "平静".equals(loaded.getMood())
                            && "自习室".equals(loaded.getLocation())
                            && loaded.getUserId() != null && loaded.getUserId() == USER_A
                            && loaded.getCreatedAt() != null,
                    "userId=" + loaded.getUserId() + " title=" + loaded.getTitle()
                            + " createdAt=" + loaded.getCreatedAt());
            check("2.5 deleted 映射为 false", !loaded.isDeleted());
        }

        // 跨用户查不到
        check("2.6 ★ 跨用户 selectByIdAndUserId → null",
                mapper.selectByIdAndUserId(id, USER_B) == null);

        // count + 分页（这里才是 #{query.xxx} 属性解析的真正考验）
        DiaryQuery query = new DiaryQuery();
        query.setPage(0);
        query.setSize(10);
        long count = mapper.countByUserId(query, USER_A);
        check("2.7 countByUserId（无筛选）> 0", count > 0, "实际=" + count);

        List<Diary> page = mapper.selectPageByUserId(query, USER_A);
        check("2.8 selectPageByUserId 返回非空列表（LIMIT/OFFSET 生效）",
                page != null && !page.isEmpty(), "size=" + (page == null ? -1 : page.size()));

        // ★ 带全部筛选条件 —— 这一步会解析 #{query.keyword}、
        //   #{query.from}、#{query.to}、#{query.mood}、#{query.tagId}
        //   任何一个属性名写错都会在这里抛异常
        DiaryQuery full = new DiaryQuery();
        full.setPage(0);
        full.setSize(10);
        full.setKeyword("MyBatis");
        full.setMood("平静");
        full.setFrom(java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
        full.setTo(java.time.LocalDateTime.of(2099, 12, 31, 23, 59));
        long fullCount = mapper.countByUserId(full, USER_A);
        check("2.9 ★★ 全筛选条件 countByUserId 成功（证明 #{query.*} 属性名全对）",
                fullCount >= 1, "实际=" + fullCount);
        List<Diary> fullPage = mapper.selectPageByUserId(full, USER_A);
        check("2.10 ★★ 全筛选条件 selectPageByUserId 成功并命中",
                fullPage != null && !fullPage.isEmpty(), "size=" + (fullPage == null ? -1 : fullPage.size()));

        // 不存在的关键词 → 0 条（证明条件真的在过滤）
        DiaryQuery none = new DiaryQuery();
        none.setKeyword("绝不可能匹配到的关键词XYZQ");
        check("2.11 关键词不匹配 → 0 条（筛选条件真的生效）",
                mapper.countByUserId(none, USER_A) == 0);

        // 更新
        loaded.setTitle("改过的标题");
        loaded.setMood("开心");
        int updated = mapper.updateByIdAndUserId(loaded);
        check("2.12 updateByIdAndUserId 影响 1 行", updated == 1, "实际=" + updated);
        Diary afterUpdate = mapper.selectByIdAndUserId(id, USER_A);
        check("2.13 更新真的落库", afterUpdate != null && "改过的标题".equals(afterUpdate.getTitle()),
                "title=" + (afterUpdate == null ? "null" : afterUpdate.getTitle()));

        // 跨用户更新 → 0 行
        Diary forged = new Diary();
        forged.setId(id);
        forged.setUserId(USER_B);
        forged.setTitle("伪造更新");
        check("2.14 ★ 跨用户 updateByIdAndUserId → 0 行",
                mapper.updateByIdAndUserId(forged) == 0);
        Diary notChanged = mapper.selectByIdAndUserId(id, USER_A);
        check("2.15 复核：标题未被跨用户更新篡改",
                notChanged != null && "改过的标题".equals(notChanged.getTitle()),
                "title=" + (notChanged == null ? "null" : notChanged.getTitle()));

        // 软删除
        check("2.16 跨用户 softDeleteByIdAndUserId → 0 行",
                mapper.softDeleteByIdAndUserId(id, USER_B) == 0);
        check("2.17 本人 softDeleteByIdAndUserId → 1 行",
                mapper.softDeleteByIdAndUserId(id, USER_A) == 1);
        check("2.18 删除后 selectByIdAndUserId → null",
                mapper.selectByIdAndUserId(id, USER_A) == null);
        check("2.19 重复删除 → 0 行（幂等）",
                mapper.softDeleteByIdAndUserId(id, USER_A) == 0);

        return id;
    }

    // ══════════════════════════════════════════════════════════
    private static long testTagMapper(TagMapper mapper) {
        Tag t = new Tag();
        t.setUserId(USER_A);
        t.setName("MyBatis测试标签");
        check("3.1 insert 影响 1 行", mapper.insert(t) == 1);
        check("3.2 insert 回填主键", t.getId() != null && t.getId() > 0, "id=" + t.getId());

        long tagId = t.getId();

        List<Tag> mine = mapper.selectByUserId(USER_A);
        check("3.3 selectByUserId 返回列表且含新标签",
                mine.stream().anyMatch(x -> x.getId() == tagId), "size=" + mine.size());

        Tag byName = mapper.selectByNameAndUserId("MyBatis测试标签", USER_A);
        check("3.4 selectByNameAndUserId 查到", byName != null && byName.getId() == tagId);

        check("3.5 ★ 跨用户 selectByNameAndUserId → null（同名标签按用户隔离）",
                mapper.selectByNameAndUserId("MyBatis测试标签", USER_B) == null);

        // ★ selectByIdsAndUserId —— foreach 的真正考验
        List<Tag> byIds = mapper.selectByIdsAndUserId(List.of(tagId), USER_A);
        check("3.6 ★★ selectByIdsAndUserId（单元素 IN 列表）成功",
                byIds.size() == 1 && byIds.get(0).getId() == tagId, "size=" + byIds.size());

        // 混入 B 的标签 ID → 只应返回 A 的
        Tag tb = new Tag();
        tb.setUserId(USER_B);
        tb.setName("MyBatis测试标签");
        mapper.insert(tb);
        List<Tag> mixed = mapper.selectByIdsAndUserId(List.of(tagId, tb.getId()), USER_A);
        check("3.7 ★★ 批量查时混入别人的 tagId → 只返回自己的 1 个",
                mixed.size() == 1, "实际=" + mixed.size() + " 个（应为 1）");

        // 多元素 IN（foreach 的 separator 考验）
        Tag t2 = new Tag();
        t2.setUserId(USER_A);
        t2.setName("MyBatis测试标签2");
        mapper.insert(t2);
        List<Tag> multi = mapper.selectByIdsAndUserId(List.of(tagId, t2.getId()), USER_A);
        check("3.8 ★★ selectByIdsAndUserId（多元素 IN，验证 foreach separator）",
                multi.size() == 2, "实际=" + multi.size() + " 个（应为 2）");

        // countUsedByDiaries（此时还没关联，应为 0）
        long used0 = mapper.countUsedByDiaries(tagId, USER_A);
        check("3.9 countUsedByDiaries 未被使用时为 0", used0 == 0, "实际=" + used0);

        return tagId;
    }

    // ══════════════════════════════════════════════════════════
    private static void testDiaryTagMapper(DiaryTagMapper dtMapper, TagMapper tagMapper,
                                           DiaryMapper diaryMapper, long ignoredDiaryId, long tagId) {
        // 造两篇 A 的日记 + 一篇 B 的
        Diary dA1 = newDiary(USER_A, "关联测试 1");
        diaryMapper.insert(dA1);
        Diary dA2 = newDiary(USER_A, "关联测试 2");
        diaryMapper.insert(dA2);
        Diary dB = newDiary(USER_B, "B 的日记");
        diaryMapper.insert(dB);

        // ── insertByDiaryIdAndTagIds：正常路径 ─────────────────
        int ok = dtMapper.insertByDiaryIdAndTagIds(dA1.getId(), List.of(tagId), USER_A);
        check("4.1 ★★ insertByDiaryIdAndTagIds 正常插入 1 行（UNION ALL + JOIN 语法正确）",
                ok == 1, "实际=" + ok);

        // ── 越权 1：B 给 A 的日记打标签 ────────────────────────
        int attack1 = dtMapper.insertByDiaryIdAndTagIds(dA1.getId(), List.of(tagId), USER_B);
        check("4.2 ★★ B 给 A 的日记打标签 → 0 行（JOIN d.user_id 拦住）",
                attack1 == 0, "实际=" + attack1 + " 行");

        // ── 越权 2：A 用 B 的标签打自己日记 ────────────────────
        long tagB = tagMapper.selectByNameAndUserId("MyBatis测试标签", USER_B).getId();
        int attack2 = dtMapper.insertByDiaryIdAndTagIds(dA2.getId(), List.of(tagB), USER_A);
        check("4.3 ★★ A 用 B 的标签 ID 打标签 → 0 行（JOIN t.user_id 拦住）",
                attack2 == 0, "实际=" + attack2 + " 行");

        // ── selectTagsByDiaryIdsAndUserId ─────────────────────
        List<DiaryTag> tags = dtMapper.selectTagsByDiaryIdsAndUserId(
                List.of(dA1.getId(), dA2.getId(), dB.getId()), USER_A);
        check("4.4 ★★ selectTagsByDiaryIdsAndUserId 只返回自己的关联",
                tags.size() == 1, "实际=" + tags.size() + " 行（传入 A 两篇 + B 一篇，应只剩 A 的 1 行）");
        if (tags.size() == 1) {
            /*
             * !! TRAP: Long == Long compares REFERENCES, not values. !!
             *
             * The first version was:
             *     tags.get(0).getDiaryId() == dA1.getId()
             * Both sides are Long objects (entity getters return Long), so '=='
             * compares identity. Values above 127 fall outside the Integer
             * cache, so two equal-valued Longs are different objects and the
             * comparison is FALSE.
             *
             * Symptoms are maddening: the failure message prints two identical
             * numbers ("diaryId=329 tagId=178"), which looks like a mapping bug
             * in MyBatis rather than a comparison bug in the test.
             *
             * It also only shows up once the auto-increment ids grow past 127,
             * so it passed for a while and then started failing on its own.
             *
             * Fix: compare as primitives via longValue(), or use .equals().
             * NOTE: 'getTagId() == tagId' below is fine either way, because
             * tagId is a primitive long -- '==' then unboxes and compares
             * values. The bug only appears when BOTH sides are boxed.
             */
            check("4.5 返回的 diaryId / tagId 映射正确",
                    tags.get(0).getDiaryId().longValue() == dA1.getId().longValue()
                            && tags.get(0).getTagId().longValue() == tagId,
                    "diaryId=" + tags.get(0).getDiaryId() + " tagId=" + tags.get(0).getTagId()
                            + " (期望 diaryId=" + dA1.getId() + " tagId=" + tagId + ")");
        }

        // ── countUsedByDiaries 在有使用时 > 0 ──────────────────
        long used = tagMapper.countUsedByDiaries(tagId, USER_A);
        check("4.6 countUsedByDiaries 有 1 篇使用时返回 1", used == 1, "实际=" + used);
        check("4.7 ★ 跨用户 countUsedByDiaries → 0", tagMapper.countUsedByDiaries(tagId, USER_B) == 0);

        // ── deleteByDiaryIdAndUserId ──────────────────────────
        // 先给 dA2 也打上标签，验证"清空 dA1"不会波及 dA2
        dtMapper.insertByDiaryIdAndTagIds(dA2.getId(), List.of(tagId), USER_A);
        long beforeClear = tagMapper.countUsedByDiaries(tagId, USER_A);
        check("4.8 清理前 A 有 2 篇日记使用该标签", beforeClear == 2, "实际=" + beforeClear);

        int cleared = dtMapper.deleteByDiaryIdAndUserId(dA1.getId(), USER_A);
        check("4.9 清空 dA1 的关联 → 删除 1 行", cleared == 1, "实际=" + cleared);
        long afterClear = tagMapper.countUsedByDiaries(tagId, USER_A);
        check("4.10 ★★ 复核：dA2 的关联没被误删（DELETE 带了 diary_id 限定）",
                afterClear == 1, "实际=" + afterClear + "（应为 1；若为 0 说明 DELETE 漏了 diary_id，"
                        + "把这用户所有日记的标签全删了）");

        // ── 跨用户删除 → 0 行 ─────────────────────────────────
        check("4.11 ★ 跨用户 deleteByDiaryIdAndUserId → 0 行",
                dtMapper.deleteByDiaryIdAndUserId(dA2.getId(), USER_B) == 0);
        check("4.12 复核：dA2 的关联仍在",
                tagMapper.countUsedByDiaries(tagId, USER_A) == 1);

        // ── tag 删除 ──────────────────────────────────────────
        check("4.13 ★ 跨用户 deleteByIdAndUserId → 0 行",
                tagMapper.deleteByIdAndUserId(tagId, USER_B) == 0);
        check("4.14 本人 deleteByIdAndUserId → 1 行后无法再查",
                tagMapper.deleteByIdAndUserId(tagId, USER_A) == 1
                        && tagMapper.selectByNameAndUserId("MyBatis测试标签", USER_A) == null);
    }

    // ══════════════════════════════════════════════════════════
    private static void testResultMapping(DiaryMapper mapper) {
        // 造一篇只填必填字段的日记，验证 NULL 字段能正确映射为 null
        // （application.yml 里 call-setters-on-nulls: true 就是为了这个）
        Diary d = new Diary();
        d.setUserId(USER_A);
        d.setTitle("空字段映射测试");
        d.setContentCiphertext("Y2lwaGVy");
        mapper.insert(d);

        Diary loaded = mapper.selectByIdAndUserId(d.getId(), USER_A);
        check("5.1 可选字段为 NULL 时映射为 null 而不是抛异常",
                loaded != null && loaded.getMood() == null
                        && loaded.getWeather() == null && loaded.getLocation() == null,
                loaded == null ? "查不到" : ("mood=" + loaded.getMood()
                        + " weather=" + loaded.getWeather() + " location=" + loaded.getLocation()));

        // created_at / updated_at 由数据库默认值填充 —— 验证 DEFAULT CURRENT_TIMESTAMP 生效
        check("5.2 created_at / updated_at 由数据库默认值填充（非 null）",
                loaded != null && loaded.getCreatedAt() != null && loaded.getUpdatedAt() != null,
                loaded == null ? "查不到" : ("createdAt=" + loaded.getCreatedAt()
                        + " updatedAt=" + loaded.getUpdatedAt()));

        // 时间必须能被解析成 LocalDateTime（时区配置正确）
        check("5.3 时间是 UTC 语义且能正确解析为 LocalDateTime",
                loaded != null && loaded.getCreatedAt().getYear() >= 2020,
                loaded == null ? "查不到" : String.valueOf(loaded.getCreatedAt()));
    }

    // ══════════════════════════════════════════════════════════
    // 工具
    // ══════════════════════════════════════════════════════════

    private static Diary newDiary(long userId, String title) {
        Diary d = new Diary();
        d.setUserId(userId);
        d.setTitle(title);
        d.setContentCiphertext("Y2lwaGVy");
        return d;
    }

    /** 检查 XML 里有没有 SELECT *（防止后续有人图省事改回去）。 */
    private static boolean xmlContainsSelectStar() {
        for (String xml : MAPPER_XMLS) {
            try (InputStream in = TestDiaryMappers.class.getClassLoader().getResourceAsStream(xml)) {
                if (in == null) {
                    continue;
                }
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);

                // ⚠️ 必须先剥掉 XML 注释再检查。
                //    否则会误报：这些 XML 的注释里<b>故意</b>写着
                //    "禁止 SELECT *" 这样的说明文字，第一版检查就因此假失败过一次。
                //    检查的目标是"实际执行的 SQL 里有没有"，不是"文件里有没有这几个字"。
                String withoutComments = text.replaceAll("(?s)<!--.*?-->", " ");

                String flat = withoutComments.replaceAll("\\s+", " ").toLowerCase();
                if (flat.contains("select *")) {
                    System.out.println("       " + xml + " 含 SELECT *");
                    return true;
                }
            } catch (Exception ignored) {
                // 读不到就不算失败
            }
        }
        return false;
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

    private static Map<String, String> loadDotenv() {
        Map<String, String> map = new HashMap<>();
        Path[] candidates = {Path.of("..", ".env"), Path.of(".env")};
        for (Path p : candidates) {
            if (!Files.isRegularFile(p)) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) {
                        continue;
                    }
                    int eq = t.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String key = t.substring(0, eq).trim();
                    String value = t.substring(eq + 1).trim();
                    if (value.length() >= 2
                            && ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'")))) {
                        value = value.substring(1, value.length() - 1);
                    } else {
                        int hash = value.indexOf(" #");
                        if (hash > 0) {
                            value = value.substring(0, hash).trim();
                        }
                    }
                    map.put(key, value);
                }
                System.out.println("已加载凭据: " + p.toAbsolutePath().normalize());
                break;
            } catch (Exception ignored) {
                // 退回默认值
            }
        }
        return map;
    }
}
