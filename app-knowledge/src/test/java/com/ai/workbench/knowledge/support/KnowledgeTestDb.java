package com.ai.workbench.knowledge.support;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 集成测试数据库脚手架（app-knowledge 简化版，参照 app-bank 的 BankTestDb）：
 * 直连真实 MySQL，不启动 Spring 容器。测试库与开发库隔离（ai_workbench_test），
 * MySQL 不可用时 {@link #available()} 返回 false，集成测试整体跳过而不是失败。
 *
 * 与 BankTestDb 的差异：只清 kb_* 表——app-knowledge 的集成测试只触及知识库域，
 * classpath 上的迁移也仅有 platform-core 的（app-bank 的 V2 不在本模块测试类路径，
 * platform_user 表在测试库中不存在，本模块测试也不依赖它）。
 */
public final class KnowledgeTestDb {

    private static final String DEFAULT_URL = "jdbc:mysql://localhost:13306/ai_workbench_test"
            + "?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8"
            + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";

    private static final String URL = env("TEST_MYSQL_URL", DEFAULT_URL);
    private static final String USER = env("TEST_MYSQL_USER", "root");
    private static final String PASSWORD = env("TEST_MYSQL_PASSWORD", env("MYSQL_PASSWORD", "204512"));

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static Boolean available;

    private KnowledgeTestDb() {
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 数据库是否可用（连接 + 建表脚本都跑通）；结果只探测一次 */
    public static synchronized boolean available() {
        if (available == null) {
            try {
                initSchema();
                available = true;
            } catch (Exception e) {
                available = false;
            }
        }
        return available;
    }

    /** 供跳过/失败提示使用：只暴露目标地址，不含口令 */
    public static String describeTarget() {
        return URL;
    }

    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            dataSource = new DriverManagerDataSource(URL, USER, PASSWORD);
        }
        return dataSource;
    }

    public static synchronized JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource());
        }
        return jdbc;
    }

    /**
     * 用真实 Flyway 迁移重建测试库结构（clean + migrate，理由同 BankTestDb：
     * 每个 JVM 从同一初始结构出发，迁移写错立刻在测试里暴露）。
     * clean 前 校验库名以 _test 结尾，防止 TEST_MYSQL_URL 误指开发库被清空。
     */
    private static void initSchema() {
        JdbcTemplate template = jdbc();
        template.queryForObject("SELECT 1", Integer.class);
        String database = template.queryForObject("SELECT DATABASE()", String.class);
        if (database == null || !database.endsWith("_test")) {
            throw new IllegalStateException(
                    "拒绝在非测试库上重建表结构：DATABASE()=" + database + "（库名需以 _test 结尾）");
        }
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    /** 清空知识库域全部业务表，让每个用例从同一空状态出发（夹具由用例自行播种） */
    public static void reset() {
        JdbcTemplate t = jdbc();
        t.execute("DELETE FROM kb_chunk");
        t.execute("DELETE FROM kb_document");
        t.execute("DELETE FROM kb_binding");
        t.execute("DELETE FROM kb_space");
    }

    // ---------- 播种辅助 ----------

    /** 建一个知识库空间，返回 id */
    public static long insertSpace(String name, String owner, String spaceType) {
        jdbc().update("""
                INSERT INTO kb_space (name, status, chunk_size, chunk_overlap, owner, space_type)
                VALUES (?, 'READY', 500, 50, ?, ?)
                """, name, owner, spaceType);
        return jdbc().queryForObject("SELECT id FROM kb_space WHERE name = ?", Long.class, name);
    }

    /** 绑定空间给某个 Agent */
    public static void bindAgent(long spaceId, String agentKey) {
        jdbc().update("INSERT INTO kb_binding (space_id, agent_key) VALUES (?, ?)", spaceId, agentKey);
    }

    /**
     * 播种一篇 READY 文档并带一个切片：直接插 kb_document/kb_chunk，
     * 不走 KbDocService——夹具要的是确定性的最小状态，不引入切片器行为。
     * 返回文档 id。
     */
    public static long insertDocWithChunk(long spaceId, String title, String owner, String content) {
        jdbc().update("""
                INSERT INTO kb_document (space_id, title, doc_type, size_kb, words, chunks, status, enabled, content, owner)
                VALUES (?, ?, 'md', 1, ?, 1, 'READY', 1, ?, ?)
                """, spaceId, title, content.length(), content, owner);
        long docId = jdbc().queryForObject(
                "SELECT id FROM kb_document WHERE space_id = ? AND title = ?", Long.class, spaceId, title);
        jdbc().update("INSERT INTO kb_chunk (space_id, doc_id, seq, content, words) VALUES (?, ?, 0, ?, ?)",
                spaceId, docId, content, content.length());
        return docId;
    }
}
