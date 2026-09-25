import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 生成 abc12345 的 BCrypt 哈希，供手工插数据用。
 *
 * 用项目完全相同的编码器（BCryptPasswordEncoder，强度 10），
 * 所以生成出来的哈希可以真实登录，而不只是个格式正确的假值。
 */
public class GenHash {
    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
        String raw = "abc12345";

        // 生成两个，直观展示「同一密码、不同哈希」
        for (int i = 0; i < 2; i++) {
            String hash = encoder.encode(raw);
            System.out.println(hash);
            // 自检：生成出来的哈希必须能验证通过
            if (!encoder.matches(raw, hash)) {
                throw new IllegalStateException("自检失败：生成的哈希无法验证");
            }
        }
        System.out.println("SELF-CHECK: OK");
    }
}
