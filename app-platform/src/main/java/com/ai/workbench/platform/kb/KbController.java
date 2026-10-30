package com.ai.workbench.platform.kb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ai.workbench.core.auth.CorePrincipal;
import com.ai.workbench.core.kb.*;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 平台级知识库管理（登录即可用）：
 *   GET    /api/kb/spaces                        空间列表（含绑定 Agent）
 *   POST   /api/kb/spaces                        新建（基本信息 + 高级切片配置 + 绑定）
 *   PUT    /api/kb/spaces/{id}                   编辑（信息 / 配置 / 绑定）
 *   DELETE /api/kb/spaces/{id}                   删除（级联文档、切片、绑定）
 *   GET    /api/kb/spaces/{id}                   详情（空间 + 文档清单）
 *   POST   /api/kb/spaces/{id}/documents         上传文档（multipart，同步解析切片）
 *   PUT    /api/kb/spaces/{id}/documents/{docId} 启用/禁用文档
 *   DELETE /api/kb/spaces/{id}/documents/{docId} 删除文档
 *   POST   /api/kb/spaces/{id}/rebuild           重建索引（全量重切）
 *   POST   /api/kb/spaces/{id}/search            检索测试（关键词 + 向量语义混合召回）
 *
 * MVP 诚实边界：txt/md/csv/pdf/doc/docx/html/htm 全部经 Tika 真实提取、真实切片；
 * 扫描版 PDF 等提不出正文的如实标 FAILED（不造假）；检索默认关键词召回，
 * embedding 打开时自动升级为混合检索（HybridSearchService 双路 RRF 融合）。
 * 空间状态由文档状态实时派生。入库/重切/级联删除的持久化逻辑在 KbDocService，
 * 与个人知识库（app-knowledge 批次）共用同一套链路。
 */
@RestController
@RequestMapping("/api/kb")
public class KbController {

    /** 混合检索入口：embedding 未启用时内部退化为纯关键词，本控制器不感知差异 */
    private final HybridSearchService hybrid;

    /** 文档抽取引擎（Tika）：多格式统一提正文 */
    private final DocumentExtractor extractor;

    /** 文档持久化与索引同步：上传/删除/重建都委托它，个人库复用同一实现 */
    private final KbDocService docService;

    public record SpaceView(long id, String name, String description, String status,
                            int chunkSize, int chunkOverlap, int docCount, int chunkTotal,
                            String owner, String createdAt, String updatedAt, List<String> agents) {
    }

    public record DocView(long id, String title, String docType, int sizeKb, int words, int chunks,
                          String status, boolean enabled, String errorMsg, String createdAt) {
    }

    public record SpaceDetail(SpaceView space, List<DocView> documents) {
    }

    public record CreateRequest(String name, String description, Integer chunkSize, Integer chunkOverlap,
                                List<String> agents) {
    }

    public record HitView(long docId, String docTitle, int seq, String content, double score) {
    }

    public record SearchResponse(String question, int tookMs, String note, List<HitView> hits) {
    }

    public record UpdateEnabledRequest(boolean enabled) {
    }

    /**
     * 上传允许的扩展名：与 DocumentExtractor.SUPPORTED 保持一致（txt/md/csv 直读，
     * 其余 Tika 解析）。doc/html/htm 是本次新增允许项。
     */
    private static final Set<String> ALL_TYPES = Set.of("txt", "md", "csv", "pdf", "doc", "docx", "html", "htm");
    private static final Set<String> AGENT_KEYS = Set.of("bank", "knowledge", "interview");
    private static final int MAX_DOCS_PER_UPLOAD = 10;

    private final JdbcTemplate jdbc;

    public KbController(JdbcTemplate jdbc, KbDocService docService, DocumentExtractor extractor,
                        HybridSearchService hybrid) {
        this.jdbc = jdbc;
        this.docService = docService;
        this.extractor = extractor;
        this.hybrid = hybrid;
    }

    // ---------- 空间：列表 / 详情 ----------

    @GetMapping("/spaces")
    public List<SpaceView> list() {
        Map<Long, List<String>> agents = new LinkedHashMap<>();
        jdbc.query("SELECT space_id, agent_key FROM kb_binding ORDER BY id", rs -> {
            agents.computeIfAbsent(rs.getLong("space_id"), k -> new ArrayList<>()).add(rs.getString("agent_key"));
        });
        return jdbc.query(
                "SELECT id, name, description, status, chunk_size, chunk_overlap, doc_count, chunk_total, "
                        // 个人库（space_type='PERSONAL'）不出现在平台管理页，防他人个人库标题泄露
                        + "owner, created_at, updated_at FROM kb_space WHERE space_type = 'PLATFORM' "
                        + "ORDER BY updated_at DESC",
                (rs, i) -> new SpaceView(rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                        rs.getString("status"), rs.getInt("chunk_size"), rs.getInt("chunk_overlap"),
                        rs.getInt("doc_count"), rs.getInt("chunk_total"), rs.getString("owner"),
                        rs.getString("created_at"), rs.getString("updated_at"),
                        agents.getOrDefault(rs.getLong("id"), List.of())));
    }

    @GetMapping("/spaces/{id}")
    public ResponseEntity<?> detail(@PathVariable long id) {
        List<SpaceView> spaces = jdbc.query(
                "SELECT id, name, description, status, chunk_size, chunk_overlap, doc_count, chunk_total, "
                        // 与 list() 同理：个人库对平台管理页不可见（含直链探测详情，统一 404 语义）
                        + "owner, created_at, updated_at FROM kb_space WHERE id = ? AND space_type = 'PLATFORM'",
                (rs, i) -> new SpaceView(rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                        rs.getString("status"), rs.getInt("chunk_size"), rs.getInt("chunk_overlap"),
                        rs.getInt("doc_count"), rs.getInt("chunk_total"), rs.getString("owner"),
                        rs.getString("created_at"), rs.getString("updated_at"),
                        agentsOf(id)), id);
        if (spaces.isEmpty()) {
            return badRequest("知识库不存在");
        }
        List<DocView> documents = jdbc.query(
                "SELECT id, title, doc_type, size_kb, words, chunks, status, enabled, error_msg, created_at "
                        + "FROM kb_document WHERE space_id = ? ORDER BY id",
                (rs, i) -> new DocView(rs.getLong("id"), rs.getString("title"), rs.getString("doc_type"),
                        rs.getInt("size_kb"), rs.getInt("words"), rs.getInt("chunks"), rs.getString("status"),
                        rs.getBoolean("enabled"), rs.getString("error_msg"), rs.getString("created_at")), id);
        return ResponseEntity.ok(new SpaceDetail(spaces.getFirst(), documents));
    }

    private List<String> agentsOf(long spaceId) {
        return jdbc.query("SELECT agent_key FROM kb_binding WHERE space_id = ? ORDER BY id",
                (rs, i) -> rs.getString("agent_key"), spaceId);
    }

    // ---------- 空间：创建 / 编辑 / 删除 ----------

    @PostMapping("/spaces")
    public ResponseEntity<?> create(@RequestBody CreateRequest req,
                                    @RequestAttribute(CorePrincipal.ATTR_PRINCIPAL) CorePrincipal principal) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            return badRequest("知识库名称不能为空");
        }
        if (req.name().length() > 100) {
            return badRequest("知识库名称不能超过 100 字");
        }
        if (req.agents() != null && !AGENT_KEYS.containsAll(req.agents())) {
            return badRequest("绑定的 Agent 无效");
        }
        int chunkSize = clamp(req.chunkSize(), 200, 2000, 500);
        int chunkOverlap = clamp(req.chunkOverlap(), 0, 500, 50);
        try {
            jdbc.update("""
                    INSERT INTO kb_space (name, description, status, chunk_size, chunk_overlap, owner)
                    VALUES (?, ?, 'BUILDING', ?, ?, ?)
                    """, req.name().trim(), req.description(), chunkSize, chunkOverlap, principal.username());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return ResponseEntity.status(409).body(Map.of("message", "已存在同名知识库"));
        }
        Long id = jdbc.queryForObject("SELECT id FROM kb_space WHERE name = ?", Long.class, req.name().trim());
        saveAgents(id, req.agents());
        return ResponseEntity.ok(Map.of("id", id, "message", "知识库已创建，上传文档后即可检索"));
    }

    @PutMapping("/spaces/{id}")
    public ResponseEntity<?> update(@PathVariable long id, @RequestBody CreateRequest req) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM kb_space WHERE id = ?", Integer.class, id);
        if (exists == null || exists == 0) {
            return badRequest("知识库不存在");
        }
        if (req == null || req.name() == null || req.name().isBlank()) {
            return badRequest("知识库名称不能为空");
        }
        if (req.agents() != null && !AGENT_KEYS.containsAll(req.agents())) {
            return badRequest("绑定的 Agent 无效");
        }
        int chunkSize = clamp(req.chunkSize(), 200, 2000, 500);
        int chunkOverlap = clamp(req.chunkOverlap(), 0, 500, 50);
        try {
            jdbc.update("""
                    UPDATE kb_space SET name = ?, description = ?, chunk_size = ?, chunk_overlap = ?
                    WHERE id = ?
                    """, req.name().trim(), req.description(), chunkSize, chunkOverlap, id);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return ResponseEntity.status(409).body(Map.of("message", "已存在同名知识库"));
        }
        jdbc.update("DELETE FROM kb_binding WHERE space_id = ?", id);
        saveAgents(id, req.agents());
        // 切片配置变化只影响之后上传/重建的文档，已有切片保持不动（要生效请重建索引）
        return ResponseEntity.ok(Map.of("message", "知识库已更新；切片配置变更后请执行「重建索引」生效"));
    }

    @DeleteMapping("/spaces/{id}")
    public ResponseEntity<?> delete(@PathVariable long id) {
        // 级联（文档/切片/绑定/向量索引）统一在 KbDocService，个人库删除复用同一实现
        if (!docService.removeSpace(id)) {
            return badRequest("知识库不存在");
        }
        return ResponseEntity.ok(Map.of("message", "知识库及全部文档已删除"));
    }

    // ---------- 文档：上传 / 启禁 / 删除 ----------

    /**
     * 同步解析：MVP 文档量小（演示场景），上传即完成切片入库，
     * 空间计数与状态当场重算；将来文档量大再改成异步任务 + PARSING 中间态。
     */
    @PostMapping("/spaces/{id}/documents")
    public ResponseEntity<?> upload(@PathVariable long id,
                                    @RequestPart("files") List<MultipartFile> files,
                                    @RequestAttribute(CorePrincipal.ATTR_PRINCIPAL) CorePrincipal principal) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM kb_space WHERE id = ?", Integer.class, id);
        if (exists == null || exists == 0) {
            return badRequest("知识库不存在");
        }
        if (files == null || files.isEmpty()) {
            return badRequest("请选择要上传的文件");
        }
        if (files.size() > MAX_DOCS_PER_UPLOAD) {
            return badRequest("单次最多上传 " + MAX_DOCS_PER_UPLOAD + " 个文件");
        }
        int ready = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        for (MultipartFile file : files) {
            String original = file.getOriginalFilename() == null ? "未命名" : file.getOriginalFilename();
            String ext = extOf(original);
            if (!ALL_TYPES.contains(ext)) {
                errors.add(original + "：不支持的类型");
                continue;
            }
            try {
                byte[] bytes = file.getBytes();
                int sizeKb = Math.max(bytes.length / 1024, 1);
                String title = original.length() > 200 ? original.substring(0, 200) : original;
                try {
                    // 统一走 DocumentExtractor：txt/md/csv 直读，pdf/doc/docx/html/htm 交 Tika
                    String content = extractor.extract(bytes, ext);
                    docService.insertDocument(id, title, ext, sizeKb, content, principal.username(), null);
                    ready++;
                } catch (IllegalArgumentException | IllegalStateException e) {
                    // 提不出正文（扫描版 PDF、超 2MB 文本、损坏文件）如实标 FAILED，不造假
                    docService.insertFailedDocument(id, title, ext, sizeKb, failMsg(e), principal.username());
                    failed++;
                }
            } catch (IOException e) {
                errors.add(original + "：读取失败");
            }
        }
        docService.recalcSpace(id);
        String message = "成功解析 " + ready + " 篇"
                + (failed > 0 ? "，" + failed + " 篇解析失败" : "")
                + (errors.isEmpty() ? "" : "（" + String.join("；", errors) + "）");
        return ResponseEntity.ok(Map.of("message", message));
    }

    @PutMapping("/spaces/{id}/documents/{docId}")
    public ResponseEntity<?> updateEnabled(@PathVariable long id, @PathVariable long docId,
                                           @RequestBody UpdateEnabledRequest req) {
        int updated = jdbc.update("UPDATE kb_document SET enabled = ? WHERE id = ? AND space_id = ?",
                req.enabled(), docId, id);
        if (updated == 0) {
            return badRequest("文档不存在");
        }
        // 向量索引同步：禁用的文档不再参与语义召回（关键词路靠 enabled 过滤，向量路必须删）
        if (req.enabled()) {
            hybrid.reindexDoc(jdbc, docId);
        } else {
            hybrid.removeDoc(docId);
        }
        docService.recalcSpace(id);
        return ResponseEntity.ok(Map.of("message", req.enabled() ? "文档已启用，参与检索" : "文档已禁用，不参与检索"));
    }

    @DeleteMapping("/spaces/{id}/documents/{docId}")
    public ResponseEntity<?> deleteDoc(@PathVariable long id, @PathVariable long docId) {
        // 切片/向量/计数派生的级联都在 KbDocService.removeDoc 里，与个人库共用
        if (!docService.removeDoc(id, docId)) {
            return badRequest("文档不存在");
        }
        return ResponseEntity.ok(Map.of("message", "文档及切片已删除"));
    }

    // ---------- 重建索引 / 检索测试 ----------

    /** 从提取出的纯文本全量重切：解析失败的文档跳过（没有 content 可切） */
    @PostMapping("/spaces/{id}/rebuild")
    public ResponseEntity<?> rebuild(@PathVariable long id) {
        KbDocService.SpaceConf conf = docService.confOf(id);
        List<long[]> docs = jdbc.query(
                "SELECT id, words FROM kb_document WHERE space_id = ? AND status = 'READY'",
                (rs, i) -> new long[]{rs.getLong("id"), rs.getInt("words")}, id);
        hybrid.removeSpace(id);
        jdbc.update("DELETE FROM kb_chunk WHERE space_id = ?", id);
        int total = 0;
        for (long[] doc : docs) {
            total += docService.rechunkDoc(id, doc[0], conf);
        }
        // 重切后统一重建向量索引：从 MySQL 读权威数据，向量库丢了也能一键恢复
        hybrid.reindexSpace(jdbc, id);
        docService.recalcSpace(id);
        return ResponseEntity.ok(Map.of("message", "重建完成：" + docs.size() + " 篇文档，共 " + total + " 个切片"));
    }

    @PostMapping("/spaces/{id}/search")
    public ResponseEntity<?> search(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Object questionObj = body.get("question");
        if (questionObj == null || String.valueOf(questionObj).isBlank()) {
            return badRequest("请输入要测试的问题");
        }
        String question = String.valueOf(questionObj).trim();
        int topN = 5;
        Object topNObj = body.get("topN");
        if (topNObj instanceof Number n && n.intValue() > 0 && n.intValue() <= 20) {
            topN = n.intValue();
        }
        long start = System.currentTimeMillis();
        List<TextIndexer.Candidate> candidates = jdbc.query("""
                SELECT c.doc_id, d.title AS doc_title, c.seq, c.content, d.enabled
                FROM kb_chunk c JOIN kb_document d ON d.id = c.doc_id
                WHERE c.space_id = ?
                """, (rs, i) -> new TextIndexer.Candidate(rs.getLong("doc_id"), rs.getString("doc_title"),
                rs.getInt("seq"), rs.getString("content"), rs.getBoolean("enabled")), id);
        List<HitView> hits = hybrid.search(question, candidates, Set.of(id), topN).stream()
                .map(h -> new HitView(h.docId(), h.docTitle(), h.seq(), h.content(), h.score()))
                .toList();
        int took = (int) (System.currentTimeMillis() - start);
        return ResponseEntity.ok(new SearchResponse(question, took,
                hybrid.vectorEnabled()
                        ? "混合召回：关键词词面打分 + 向量语义检索（RRF 融合）"
                        : "关键词召回（bigram 词面打分）；配置 ai.embedding/ai.rag 后自动升级混合检索",
                hits));
    }

    // ---------- 内部：通用小工具 ----------

    private void saveAgents(Long spaceId, List<String> agents) {
        if (spaceId == null || agents == null || agents.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT IGNORE INTO kb_binding (space_id, agent_key) VALUES (?, ?)",
                agents.stream().distinct().map(a -> new Object[]{spaceId, a}).toList());
    }

    private String extOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
    }

    /** error_msg 列是 VARCHAR(255)：异常信息截断到列宽内，避免严格模式下写库报错 */
    private String failMsg(Exception e) {
        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String msg = "解析失败：" + reason;
        return msg.length() > 255 ? msg.substring(0, 255) : msg;
    }

    private int clamp(Integer value, int min, int max, int fallback) {
        if (value == null) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
}
