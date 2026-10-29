package com.ai.workbench.knowledge.admin;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import com.ai.workbench.core.kb.HybridSearchService;
import com.ai.workbench.core.kb.KbDocService;
import com.ai.workbench.core.kb.TextIndexer;
import com.ai.workbench.knowledge.api.PersonalKbController;
import com.ai.workbench.knowledge.auth.KnowledgeUser;
import com.ai.workbench.knowledge.support.KnowledgeTestDb;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 个人知识库管理后台集成测试：直连真实 MySQL，不启 Spring 容器（同 PersonalKbToolsIT 策略），
 * Service/Controller 手工装配；embedding 相关 ObjectProvider 桩成空，向量路自动退化。
 *
 * Web 层角色判定（拦截器对 /api/kb/admin 的 403）不在此覆盖——那是 HandlerInterceptor
 * 语义，在 KnowledgeAuthInterceptorTest 用 Mock 请求直接验证；本类只验管理与配额逻辑。
 * 直调方法时 @ExceptionHandler 不会触发，因此「被拒」断言在服务层验异常类型、
 * 在异常映射方法上验 HTTP 状态码，两层合起来等价于容器内的完整行为。
 */
class KbAdminControllerIT {

    private static final KnowledgeUser ADMIN = new KnowledgeUser("admin", "管理员", "ADMIN");

    private static JdbcTemplate jdbc;
    private static KbDocService docService;
    private static KbAdminQueryService query;
    private static KbAdminController controller;

    /** 空的 ObjectProvider：Bean 未装配的等价物（向量路关闭），同 PersonalKbToolsIT */
    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                throw new UnsupportedOperationException();
            }

            @Override
            public T getIfAvailable() {
                return null;
            }

            @Override
            public T getIfUnique() {
                return null;
            }
        };
    }

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(KnowledgeTestDb.available(),
                "MySQL 不可用，跳过：" + KnowledgeTestDb.describeTarget());
        jdbc = KnowledgeTestDb.jdbc();
        ensurePlatformUserTable();
        var hybrid = new HybridSearchService(emptyProvider(), emptyProvider(), new TextIndexer());
        docService = new KbDocService(jdbc, new TextIndexer(), hybrid);
        query = new KbAdminQueryService(jdbc, docService);
        controller = new KbAdminController(query);
    }

    /**
     * platform_user 由 app-bank 的 V2 迁移在共享开发库创建，而 app-knowledge 测试类路径
     * 只有 platform-core 迁移（见 KnowledgeTestDb 类注释）；libraries() 的 display_name
     * 要 JOIN 这张表，IT 里按 V2 的列形状补建，生产代码不感知测试库差异。
     */
    private static void ensurePlatformUserTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS platform_user (
                    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
                    username      VARCHAR(64)  NOT NULL,
                    password_hash VARCHAR(100) NOT NULL,
                    display_name  VARCHAR(64)  NOT NULL,
                    platform_role VARCHAR(16)  NOT NULL DEFAULT 'USER',
                    identity_id   VARCHAR(64)  NOT NULL,
                    status        TINYINT(1)   NOT NULL DEFAULT 1,
                    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_platform_user_username (username)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """);
    }

    @BeforeEach
    void reset() {
        KnowledgeTestDb.reset();
        // 治理 SQL 触及 kb_* 之外的两张表，清掉历史运行残留，计数断言才可复现
        jdbc.update("DELETE FROM platform_user");
        jdbc.update("DELETE FROM platform_event_log WHERE action = 'searchMyKnowledge'");
    }

    // ---------- 播种辅助 ----------

    private static void seedUser(String username, String displayName) {
        jdbc.update("""
                INSERT INTO platform_user (username, password_hash, display_name, platform_role, identity_id)
                VALUES (?, 'it-not-a-real-password', ?, 'USER', 'RETAIL')
                """, username, displayName);
    }

    private static void seedEvent(String app, String action, LocalDate day) {
        jdbc.update("""
                INSERT INTO platform_event_log (app, category, action, actor, detail, result, created_at)
                VALUES (?, 'search', ?, 'alice', 'kb 检索：测试问题', 'SUCCESS', ?)
                """, app, action, Timestamp.valueOf(day.atTime(10, 0)));
    }

    /** 解析失败的文档留痕行（直插，不走 KbDocService——夹具要确定性最小状态） */
    private static void seedFailedDoc(long spaceId, String title, String owner) {
        jdbc.update("""
                INSERT INTO kb_document (space_id, title, doc_type, size_kb, status, error_msg, owner)
                VALUES (?, ?, 'pdf', 5, 'FAILED', '解析失败', ?)
                """, spaceId, title, owner);
    }

    private static void setDocSize(long docId, int sizeKb) {
        jdbc.update("UPDATE kb_document SET size_kb = ? WHERE id = ?", sizeKb, docId);
    }

    // ---------- ① 总览统计 ----------

    @Test
    void overviewAggregatesPersonalSpacesOnly() {
        seedUser("alice", "爱丽丝");
        long aliceSpace = KnowledgeTestDb.insertSpace("我的知识库（alice）", "alice", "PERSONAL");
        long docA = KnowledgeTestDb.insertDocWithChunk(aliceSpace, "alice 笔记一", "alice", "甲内容甲内容");
        setDocSize(docA, 1024);
        seedFailedDoc(aliceSpace, "扫描件", "alice");
        long bobSpace = KnowledgeTestDb.insertSpace("我的知识库（bob）", "bob", "PERSONAL");
        long docC = KnowledgeTestDb.insertDocWithChunk(bobSpace, "bob 笔记", "bob", "乙内容乙内容乙");
        setDocSize(docC, 2048);
        long platformSpace = KnowledgeTestDb.insertSpace("平台制度库", "admin", "PLATFORM");
        KnowledgeTestDb.insertDocWithChunk(platformSpace, "平台文档", "admin", "平台内容不应计入个人统计");

        LocalDate today = LocalDate.now();
        seedEvent("knowledge", "searchMyKnowledge", today);
        seedEvent("knowledge", "searchMyKnowledge", today);
        seedEvent("knowledge", "searchMyKnowledge", today.minusDays(1));
        // 噪声数据：他应用的同名动作、本应用的其他动作，都不该计入 kb 检索口径
        seedEvent("bank", "searchMyKnowledge", today);
        seedEvent("knowledge", "agent.chat", today);

        KbAdminQueryService.Overview overview = query.overview();
        assertThat(overview.personalSpaces()).isEqualTo(2);
        assertThat(overview.readySpaces()).isEqualTo(2);
        assertThat(overview.totalDocs()).isEqualTo(3);
        assertThat(overview.failedDocs()).isEqualTo(1);
        assertThat(overview.totalSizeMb()).isEqualTo(Math.round((1024 + 5 + 2048) / 1024.0));
        assertThat(overview.totalWords()).isEqualTo(6 + 7); // FAILED 文档 words=0
        assertThat(overview.totalChunks()).isEqualTo(2);
        // 趋势：7 天补零连续，今天的检索次数只数 knowledge+searchMyKnowledge 口径
        assertThat(overview.trend()).hasSize(7);
        assertThat(overview.trend().get(6).date())
                .isEqualTo(today.format(DateTimeFormatter.ofPattern("MM-dd")));
        assertThat(overview.trend().get(6).count()).isEqualTo(2);
    }

    // ---------- ② 库列表：keyword 过滤与分页 ----------

    @Test
    void librariesFilterAndPaging() {
        seedUser("alice", "爱丽丝");
        seedUser("bob", "鲍勃");
        long aliceSpace = KnowledgeTestDb.insertSpace("我的知识库（alice）", "alice", "PERSONAL");
        long docA = KnowledgeTestDb.insertDocWithChunk(aliceSpace, "alice 文档", "alice", "内容");
        setDocSize(docA, 1024);
        KnowledgeTestDb.insertSpace("我的知识库（bob）", "bob", "PERSONAL");
        KnowledgeTestDb.insertSpace("我的知识库（carol）", "carol", "PERSONAL");
        KnowledgeTestDb.insertSpace("平台制度库", "admin", "PLATFORM");

        // keyword 按 owner 命中：display_name 取用户昵称
        KbAdminQueryService.PageResult<KbAdminQueryService.LibraryItem> byOwner =
                query.libraries(1, 10, "alice");
        assertThat(byOwner.total()).isEqualTo(1);
        KbAdminQueryService.LibraryItem item = byOwner.items().getFirst();
        assertThat(item.spaceId()).isEqualTo(aliceSpace);
        assertThat(item.owner()).isEqualTo("alice");
        assertThat(item.displayName()).isEqualTo("爱丽丝");
        assertThat(item.name()).isEqualTo("我的知识库（alice）");
        assertThat(item.sizeKb()).isEqualTo(1024);

        // carol 没有用户表记录：displayName 回退 owner 本名，不出现 null
        KbAdminQueryService.PageResult<KbAdminQueryService.LibraryItem> byName =
                query.libraries(1, 10, "carol");
        assertThat(byName.total()).isEqualTo(1);
        assertThat(byName.items().getFirst().displayName()).isEqualTo("carol");

        // keyword 也匹配库名；分页 total 全量、items 按页裁剪；PLATFORM 空间不出现。
        // 顺带走一遍控制器方法，验证 principal 参数透传与分页上限裁剪的装配正确
        assertThat(controller.libraries(1, 10, "我的知识库", ADMIN).total()).isEqualTo(3);
        assertThat(controller.libraries(1, 2, "", ADMIN).items()).hasSize(2);
        assertThat(controller.libraries(2, 2, "", ADMIN).items()).hasSize(1);
        assertThat(controller.libraries(1, 2, "", ADMIN).total()).isEqualTo(3);
    }

    // ---------- ③ 非 PERSONAL 空间的文档操作被拒 ----------

    @Test
    void platformSpaceDocOpsRejected() {
        long platformSpace = KnowledgeTestDb.insertSpace("平台制度库", "admin", "PLATFORM");
        long docId = KnowledgeTestDb.insertDocWithChunk(platformSpace, "平台文档", "admin", "平台内容");

        assertThatThrownBy(() -> query.personalDocuments(platformSpace))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.setDocEnabled(docId, false))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> query.deletePersonalDoc(docId))
                .isInstanceOf(IllegalStateException.class);
        // 异常到 HTTP 的映射契约：查无/不可达 → 404，状态不允许 → 400
        assertThat(controller.notFound(new IllegalArgumentException("个人知识库不存在"))
                .getStatusCode().value()).isEqualTo(404);
        assertThat(controller.badRequest(new IllegalStateException("文档不属于个人库"))
                .getStatusCode().value()).isEqualTo(400);
        // 拒绝的同时平台库文档没有被误改
        assertThat(jdbc.queryForObject("SELECT enabled FROM kb_document WHERE id = ?",
                Integer.class, docId)).isEqualTo(1);
    }

    // ---------- ④ 删除级联 ----------

    @Test
    void deletePersonalDocCascadesChunks() {
        long space = KnowledgeTestDb.insertSpace("我的知识库（alice）", "alice", "PERSONAL");
        long docId = KnowledgeTestDb.insertDocWithChunk(space, "待删文档", "alice", "正文内容");

        query.deletePersonalDoc(docId);

        assertThat(jdbc.query("SELECT id FROM kb_document WHERE id = ?",
                (rs, i) -> rs.getLong(1), docId)).isEmpty();
        // 级联：切片必须一起消失，否则关键词检索会残留幽灵片段
        assertThat(jdbc.query("SELECT id FROM kb_chunk WHERE doc_id = ?",
                (rs, i) -> rs.getLong(1), docId)).isEmpty();
        // 再删一次：JOIN 已找不到行，按治理口径拒绝而非静默成功
        assertThatThrownBy(() -> query.deletePersonalDoc(docId))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------- ⑤ 绑定：个人库拒绝、幂等 ----------

    @Test
    void bindRequiresPlatformSpaceAndIsIdempotent() {
        long personal = KnowledgeTestDb.insertSpace("我的知识库（alice）", "alice", "PERSONAL");
        long platform = KnowledgeTestDb.insertSpace("平台制度库", "admin", "PLATFORM");

        // 个人库不允许绑定（防个人内容变成共享语料）；空间不存在单独报错
        assertThatThrownBy(() -> query.bind(personal)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> query.bind(99999L)).isInstanceOf(IllegalArgumentException.class);

        query.bind(platform);
        query.bind(platform); // 重复绑定：uk 冲突按已绑定幂等处理，不报错
        List<KbAdminQueryService.BindingItem> bindings = query.bindings();
        assertThat(bindings).hasSize(1);
        assertThat(bindings.getFirst().spaceId()).isEqualTo(platform);
        assertThat(bindings.getFirst().spaceName()).isEqualTo("平台制度库");

        query.unbind(platform);
        query.unbind(platform); // 重复解绑：删 0 行也是成功
        assertThat(query.bindings()).isEmpty();
        // 个人库始终没被绑上
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_binding WHERE space_id = ?", Long.class, personal)).isZero();
    }

    // ---------- ⑥ 配额 ----------

    @Test
    void quotaBlocksUploadAndUrlFetch() {
        // 上传路：max-docs=1 时第二篇被拒（直调控制器，配额字段用反射注入代替 @Value）
        PersonalKbController docLimited = new PersonalKbController(jdbc, docService, new com.ai.workbench.core.kb.DocumentExtractor());
        ReflectionTestUtils.setField(docLimited, "maxDocs", 1);
        KnowledgeUser alice = new KnowledgeUser("alice", "爱丽丝", "USER");
        ResponseEntity<?> first = docLimited.upload(
                List.of(new MockMultipartFile("files", "a.txt", "text/plain", "第一篇".getBytes(StandardCharsets.UTF_8))),
                alice);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        ResponseEntity<?> second = docLimited.upload(
                List.of(new MockMultipartFile("files", "b.txt", "text/plain", "第二篇".getBytes(StandardCharsets.UTF_8))),
                alice);
        assertThat(second.getStatusCode().value()).isEqualTo(400);
        assertThat(((Map<?, ?>) second.getBody()).get("message"))
                .asString().contains("企业配额").contains("文档");

        // URL 抓取路：max-total-mb=1 且库里已有 1MB 时，配额在发起网络抓取前就拒绝
        seedUser("bob", "鲍勃");
        long bobSpace = KnowledgeTestDb.insertSpace("我的知识库（bob）", "bob", "PERSONAL");
        long big = KnowledgeTestDb.insertDocWithChunk(bobSpace, "大文档", "bob", "正文");
        setDocSize(big, 1024);
        PersonalKbController sizeLimited = new PersonalKbController(jdbc, docService, new com.ai.workbench.core.kb.DocumentExtractor());
        ReflectionTestUtils.setField(sizeLimited, "maxTotalMb", 1L);
        ResponseEntity<?> urlResp = sizeLimited.saveUrl(
                new PersonalKbController.UrlRequest("https://example.com/page"),
                new KnowledgeUser("bob", "鲍勃", "USER"));
        assertThat(urlResp.getStatusCode().value()).isEqualTo(400);
        assertThat(((Map<?, ?>) urlResp.getBody()).get("message"))
                .asString().contains("企业配额").contains("存储");
    }
}
