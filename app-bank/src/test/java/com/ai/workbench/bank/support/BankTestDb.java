package com.ai.workbench.bank.support;

import java.math.BigDecimal;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 集成测试数据库脚手架：直连真实 MySQL，不启动 Spring 容器、不依赖 Docker 客户端。
 *
 * 设计取舍：
 *  - 走真实 MySQL（而不是 H2）是因为被测代码大量依赖 MySQL 语义：REPEATABLE READ 快照、
 *    SUM(CASE WHEN ...)、CURDATE()、INSERT IGNORE、DECIMAL 精度。用 H2 兼容模式测等于
 *    测了一个不存在的数据库。
 *  - 不引入 Testcontainers 是为了让测试在「本机已有 docker-compose 起的 MySQL」和 CI 的
 *    service 容器上都能跑，且不需要沙箱开放 Docker 命名管道。
 *  - 目标库是独立的 ai_workbench_test（与开发库 ai_workbench 隔离），可被随意清空。
 *  - MySQL 不可用时 {@link #available()} 返回 false，集成测试整体跳过而不是失败——
 *    保证「没有数据库的机器上 mvn test 依然绿」。
 *
 * 建表直接跑各模块真实的 Flyway 迁移（classpath:db/migration），因此迁移一旦写错，
 * 测试会立刻失败——而不是等到应用启动才炸。
 */
public final class BankTestDb {

    private static final String DEFAULT_URL = "jdbc:mysql://localhost:13306/ai_workbench_test"
            + "?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8"
            + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";

    private static final String URL = env("TEST_MYSQL_URL", DEFAULT_URL);
    private static final String USER = env("TEST_MYSQL_USER", "root");
    private static final String PASSWORD = env("TEST_MYSQL_PASSWORD", env("MYSQL_PASSWORD", "204512"));

    /** 夹具账户：零售（个人）、对公、普通收款人、黑名单收款人 */
    public static final String ACC_RETAIL = "62220001";
    public static final String ACC_CORPORATE = "82280001";
    public static final String ACC_PAYEE = "62220099";
    public static final String ACC_BLACKLISTED = "62220004";

    /** 夹具会话 ID：身份编码在 memoryId 中段（bank:{identityId}:{uuid}） */
    public static final String MEM_RETAIL = "bank:zhangsan:it-retail";
    public static final String MEM_STAFF = "bank:staff001:it-staff";
    public static final String MEM_CORPORATE = "bank:corp001:it-corp";

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static Boolean available;

    private BankTestDb() {
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 供跳过/失败提示使用：只暴露目标地址，不含口令 */
    public static String describeTarget() {
        return URL;
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
     * 用真实的 Flyway 迁移重建测试库结构。
     *
     * 为什么是 clean + migrate 而不是直接 migrate：
     *  - 测试要求每个 JVM 都从同一初始结构出发，单纯 migrate 会继承上一次运行留下的结构；
     *  - clean 会连 flyway_schema_history 一起清掉，下一次 migrate 才会把 V1~Vn 完整跑一遍，
     *    这样「迁移脚本本身跑不通」在测试里立刻暴露。
     *
     * clean 会 DROP 掉库里的所有对象，因此执行前必须先确认目标库以 _test 结尾，
     * 防止有人把 TEST_MYSQL_URL 指到开发库/生产库上时被清空。
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
                .cleanDisabled(false)   // Flyway 10+ 默认禁止 clean，测试需要它
                .load();
        flyway.clean();
        flyway.migrate();
    }

    /**
     * 清空全部业务表并写入固定夹具，让每个用例都从同一账实状态出发。
     * 夹具刻意不用 schema.sql 的演示造数：演示数据会变，测试断言不该跟着变。
     */
    public static void reset() {
        JdbcTemplate t = jdbc();
        t.execute("DELETE FROM bank_transaction");
        t.execute("DELETE FROM bank_transfer_order");
        t.execute("DELETE FROM bank_audit_log");
        t.execute("DELETE FROM bank_account");
        insertAccount(t, ACC_RETAIL, "张三", "10000.00");
        insertAccount(t, ACC_CORPORATE, "星辰科技", "500000.00");
        insertAccount(t, ACC_PAYEE, "测试收款人", "0.00");
        insertAccount(t, ACC_BLACKLISTED, "赵六", "100.00");
    }

    private static void insertAccount(JdbcTemplate t, String accountNo, String owner, String balance) {
        t.update("INSERT INTO bank_account (account_no, owner, balance) VALUES (?, ?, ?)",
                accountNo, owner, new BigDecimal(balance));
    }

    // ---------- 断言辅助 ----------

    public static BigDecimal balanceOf(String accountNo) {
        return jdbc().queryForObject(
                "SELECT balance FROM bank_account WHERE account_no = ?", BigDecimal.class, accountNo);
    }

    public static void setBalance(String accountNo, String balance) {
        jdbc().update("UPDATE bank_account SET balance = ? WHERE account_no = ?",
                new BigDecimal(balance), accountNo);
    }

    public static String statusOf(String confirmId) {
        return jdbc().queryForObject(
                "SELECT status FROM bank_transfer_order WHERE confirm_id = ?", String.class, confirmId);
    }

    /** 取某会话最新一张确认单的确认码（用例里建单后回查，避免依赖返回文案解析） */
    public static String latestConfirmId(String memoryId) {
        return jdbc().queryForObject(
                "SELECT confirm_id FROM bank_transfer_order WHERE memory_id = ? ORDER BY id DESC LIMIT 1",
                String.class, memoryId);
    }

    public static int countOrders(String memoryId) {
        Integer n = jdbc().queryForObject(
                "SELECT COUNT(*) FROM bank_transfer_order WHERE memory_id = ?", Integer.class, memoryId);
        return n == null ? 0 : n;
    }

    public static int countTransactions(String accountNo) {
        Integer n = jdbc().queryForObject(
                "SELECT COUNT(*) FROM bank_transaction WHERE account_no = ?", Integer.class, accountNo);
        return n == null ? 0 : n;
    }

    public static List<String> auditResults(String toolName) {
        return jdbc().queryForList(
                "SELECT result FROM bank_audit_log WHERE tool_name = ? ORDER BY id", String.class, toolName);
    }

    /** 直接把确认单创建时间往前推，用于构造「已过期」场景 */
    public static void backdateOrder(String confirmId, int minutes) {
        jdbc().update(
                "UPDATE bank_transfer_order SET created_at = DATE_SUB(NOW(), INTERVAL ? MINUTE) WHERE confirm_id = ?",
                minutes, confirmId);
    }
}
