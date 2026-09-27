package com.ai.workbench.bank.auth;

import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 测试账号种子：platform_user 表为空时写入三个演示账号。
 * 密码在启动期用 BCrypt 现场加密，避免在 SQL 里硬编码哈希。
 * 老库升级：若种子账号的口令与当前演示口令不符（如早期版本是 123456），
 * 启动时统一重置，保证登录页标注的口令始终可用。
 */
@Component
public class PlatformUserSeeder implements ApplicationRunner {

    /** 演示口令：与登录页"测试账号"文案保持一致 */
    private static final String DEMO_PASSWORD = "Demo@2026";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public PlatformUserSeeder(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureStatusColumn();
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM platform_user", Integer.class);
        if (count == null || count == 0) {
            String hash = encoder.encode(DEMO_PASSWORD);
            insertUser("zhangsan", "张三", "ADMIN", "zhangsan", hash);
            insertUser("staff001", "小陈", "USER", "staff001", hash);
            insertUser("corp001", "星辰科技", "USER", "corp001", hash);
            return;
        }
        // 口令策略变更后同步老库：仅重置三个种子账号，不影响其他用户
        for (String username : List.of("zhangsan", "staff001", "corp001")) {
            String hash = jdbc.queryForObject(
                    "SELECT password_hash FROM platform_user WHERE username = ?",
                    String.class, username);
            if (hash == null || !encoder.matches(DEMO_PASSWORD, hash)) {
                jdbc.update("UPDATE platform_user SET password_hash = ? WHERE username = ?",
                        encoder.encode(DEMO_PASSWORD), username);
            }
        }
    }

    /** 老库升级：早期版本没有 status 列，缺列时补上（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS） */
    private void ensureStatusColumn() {
        Integer cols = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_user' AND COLUMN_NAME = 'status'",
                Integer.class);
        if (cols == null || cols == 0) {
            jdbc.update("ALTER TABLE platform_user ADD COLUMN status TINYINT(1) NOT NULL DEFAULT 1 "
                    + "AFTER identity_id");
        }
    }

    private void insertUser(String username, String displayName, String role, String identityId, String hash) {
        jdbc.update(
                "INSERT INTO platform_user (username, password_hash, display_name, platform_role, identity_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                username, hash, displayName, role, identityId);
    }
}
