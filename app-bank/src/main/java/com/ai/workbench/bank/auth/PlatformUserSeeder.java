package com.ai.workbench.bank.auth;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 演示账号种子：platform_user 表为空时写入三个演示账号（密码统一 123456）。
 * 密码在启动期用 BCrypt 现场加密，避免在 SQL 里硬编码哈希；表非空则跳过，重启幂等。
 */
@Component
public class PlatformUserSeeder implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public PlatformUserSeeder(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM platform_user", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        String hash = encoder.encode("123456");
        jdbc.update(
                "INSERT INTO platform_user (username, password_hash, display_name, platform_role, identity_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                "zhangsan", hash, "张三", "ADMIN", "zhangsan");
        jdbc.update(
                "INSERT INTO platform_user (username, password_hash, display_name, platform_role, identity_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                "staff001", hash, "小陈", "USER", "staff001");
        jdbc.update(
                "INSERT INTO platform_user (username, password_hash, display_name, platform_role, identity_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                "corp001", hash, "星辰科技", "USER", "corp001");
    }
}
