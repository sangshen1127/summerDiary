import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ============================================================
 * 维护工具：清理历次验收留下的测试账号
 * ============================================================
 *
 * <h2>为什么需要它</h2>
 *
 * <p>Phase 2 的验收测试（一键验收的步骤 4 / 5 / 7：{@code TestDiaryApi}、
 * {@code TestDiaryE2E}、Phase 1 回归、{@code browser-check.mjs}）**不清理**
 * 自己造的数据。每跑一次一键验收就会新增约 17 个测试账号，
 * 跑几次之后数据库里就分不清哪些是测试、哪些是用户的真实账号。
 * 详见开发文档 §11.6.4。
 *
 * <p>Phase 3 之后新增的三个测试（{@code TestAiTaskData} / {@code TestAiTaskApi} /
 * {@code TestAiTaskFailure}）**自带清理**，不在本工具的职责范围内。
 *
 * <h2>安全性：按前缀匹配，而不是靠手写 id 列表</h2>
 *
 * <p>只匹配测试脚本生成的前缀。五个真实账号
 * （{@code zhangsan} / {@code sangshen} / {@code sangshen02} /
 * {@code apiuserA1} / {@code apiuserB1}，后两个是用户在 Apifox 里自己注册的）
 * 不可能匹配这些前缀，所以是**结构上安全**，而不是靠人工核对。
 *
 * <p>本 schema **没有外键**（刻意的设计），所以子表必须显式先删。
 *
 * <h2>用法</h2>
 *
 * <pre>
 *   cd D:\summerDiary
 *   java -cp .m2repo\com\mysql\mysql-connector-j\8.3.0\mysql-connector-j-8.3.0.jar ^
 *        _tools\CleanTestData.java
 * </pre>
 *
 * <p>它会先列出将要删除的账号，再删除，最后打印剩余账号供核对。
 *
 * <h2>⚠️ 一个已经踩过的坑：中文前缀必须写成 unicode 转义</h2>
 *
 * <p>{@code java File.java}（单文件源码模式）用**平台默认字符集**编译
 * —— 中文 Windows 上是 GBK，不是 UTF-8。所以这个 UTF-8 文件里的中文字面量
 * 会被错误解码，{@code LIKE} 匹配不到任何东西，而且**不报错**。
 * 本工具第一次运行就是这样：删掉了 42 个账号、报告成功，
 * 却静默漏掉了 3 个中文名账号（{@code 测试用户xxxx}）。
 * 下面改用 unicode 转义（反斜杠 + u + 四位十六进制）写中文，与源文件编码彻底解耦。
 * ⚠️ 这段说明里刻意**不写出**那个转义前缀本身：Java 的 unicode 转义在词法分析
 * **之前**处理，所以**注释里**出现一个不合法的转义序列同样会让编译直接失败
 * （本文件第一次就是这么挂的，报错行号还会指向别处）。
 */
public class CleanTestData {

    private static final String URL = "jdbc:mysql://localhost:3307/ai_diary"
            + "?useUnicode=true&characterEncoding=UTF-8"
            + "&connectionCollation=utf8mb4_0900_ai_ci"
            + "&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";

    /**
     * 测试脚本生成的前缀：
     * {@code zzai}（Phase 3）、{@code zzapi} / {@code zze2e} / {@code zzfe}（Phase 2）、
     * Phase 1 的各脚本、以及注册测试用的中文用户名。
     *
     * <p>0x6D4B 0x8BD5 0x7528 0x6237 = "测试用户"（见类注释，不要改回字面量）。
     */
    private static final String TEST_USERS =
            "username REGEXP '^(zzai|zzapi|zze2e|zzfe|test_|sec_|sess_|lo_|authz_)'"
          + " OR username LIKE '\u6D4B\u8BD5\u7528\u6237%'";

    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "ai_diary", "ai_diary_dev_pw")) {
            c.setAutoCommit(false);

            List<Long> ids = new ArrayList<>();
            System.out.println("--- accounts matched as test-generated ---");
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT id, username FROM user WHERE " + TEST_USERS + " ORDER BY id")) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                    System.out.println("  user " + rs.getLong(1) + " | " + rs.getString(2));
                }
            }

            if (ids.isEmpty()) {
                System.out.println("nothing to delete");
                return;
            }
            String in = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
            System.out.println("--- deleting " + ids.size() + " accounts (children first) ---");

            try (Statement st = c.createStatement()) {
                System.out.println("diary_analysis   = " + st.executeUpdate(
                        "DELETE FROM diary_analysis WHERE user_id IN (" + in + ")"));
                System.out.println("ai_task          = " + st.executeUpdate(
                        "DELETE FROM ai_task WHERE user_id IN (" + in + ")"));
                System.out.println("diary_tag(diary) = " + st.executeUpdate(
                        "DELETE FROM diary_tag WHERE diary_id IN"
                      + " (SELECT id FROM diary WHERE user_id IN (" + in + "))"));
                System.out.println("diary_tag(tag)   = " + st.executeUpdate(
                        "DELETE FROM diary_tag WHERE tag_id IN"
                      + " (SELECT id FROM tag WHERE user_id IN (" + in + "))"));
                System.out.println("diary            = " + st.executeUpdate(
                        "DELETE FROM diary WHERE user_id IN (" + in + ")"));
                System.out.println("tag              = " + st.executeUpdate(
                        "DELETE FROM tag WHERE user_id IN (" + in + ")"));
                System.out.println("user             = " + st.executeUpdate(
                        "DELETE FROM user WHERE id IN (" + in + ")"));
            }
            c.commit();

            System.out.println("--- after cleanup ---");
            for (String t : new String[]{"user", "diary", "tag", "diary_tag", "ai_task", "diary_analysis"}) {
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM `" + t + "`")) {
                    rs.next();
                    System.out.println("count " + t + " = " + rs.getInt(1));
                }
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, username FROM user ORDER BY id")) {
                System.out.println("--- remaining accounts (must be the 5 real ones) ---");
                while (rs.next()) {
                    System.out.println("  user " + rs.getLong(1) + " | " + rs.getString(2));
                }
            }
        }
    }
}
