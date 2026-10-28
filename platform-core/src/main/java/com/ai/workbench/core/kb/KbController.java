package com.ai.workbench.core.kb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ai.workbench.core.auth.CorePrincipal;
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
 *   POST   /api/kb/spaces/{id}/search            检索测试（bigram 关键词召回打分）
 *
 * MVP 诚实边界：txt/md/csv 真实提取、真实切片；pdf/docx 上传后如实标 FAILED
 * （文本提取引擎第二期接入）；检索是可解释的关键词召回，向量语义检索待
 * embedding 接入后在 kb_chunk 平滑升级。空间状态由文档状态实时派生。
 */
@RestController
@RequestMapping("/api/kb")
public class KbController {

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

    private static final Set<String> TEXT_TYPES = Set.of("txt", "md", "csv");
    private static final Set<String> ALL_TYPES = Set.of("txt", "md", "csv", "pdf", "docx");
    private static final Set<String> AGENT_KEYS = Set.of("bank", "knowledge", "interview");
    private static final int MAX_DOCS_PER_UPLOAD = 10;

    private final JdbcTemplate jdbc;
    private final TextIndexer indexer;

    public KbController(JdbcTemplate jdbc, TextIndexer indexer) {
        this.jdbc = jdbc;
        this.indexer = indexer;
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
                        + "owner, created_at, updated_at FROM kb_space ORDER BY updated_at DESC",
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
                        + "owner, created_at, updated_at FROM kb_space WHERE id = ?",
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
        int deleted = jdbc.update("DELETE FROM kb_space WHERE id = ?", id);
        if (deleted == 0) {
            return badRequest("知识库不存在");
        }
        jdbc.update("DELETE FROM kb_document WHERE space_id = ?", id);
        jdbc.update("DELETE FROM kb_chunk WHERE space_id = ?", id);
        jdbc.update("DELETE FROM kb_binding WHERE space_id = ?", id);
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
        SpaceConf conf = confOf(id);
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
                if (TEXT_TYPES.contains(ext)) {
                    String content = new String(bytes, StandardCharsets.UTF_8);
                    insertDocument(id, title, ext, sizeKb, content, conf, principal.username());
                    ready++;
                } else {
                    jdbc.update("""
                            INSERT INTO kb_document
                                (space_id, title, doc_type, size_kb, status, error_msg, owner)
                            VALUES (?, ?, ?, ?, 'FAILED', ?, ?)
                            """, id, title, ext, sizeKb,
                            "文本提取引擎未接入（当前支持 txt/md/csv），第二期支持 PDF/Word", principal.username());
                    failed++;
                }
            } catch (IOException e) {
                errors.add(original + "：读取失败");
            }
        }
        recalcSpace(id);
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
        recalcSpace(id);
        return ResponseEntity.ok(Map.of("message", req.enabled() ? "文档已启用，参与检索" : "文档已禁用，不参与检索"));
    }

    @DeleteMapping("/spaces/{id}/documents/{docId}")
    public ResponseEntity<?> deleteDoc(@PathVariable long id, @PathVariable long docId) {
        int deleted = jdbc.update("DELETE FROM kb_document WHERE id = ? AND space_id = ?", docId, id);
        if (deleted == 0) {
            return badRequest("文档不存在");
        }
        jdbc.update("DELETE FROM kb_chunk WHERE doc_id = ?", docId);
        recalcSpace(id);
        return ResponseEntity.ok(Map.of("message", "文档及切片已删除"));
    }

    // ---------- 重建索引 / 检索测试 ----------

    /** 从提取出的纯文本全量重切：解析失败的文档跳过（没有 content 可切） */
    @PostMapping("/spaces/{id}/rebuild")
    public ResponseEntity<?> rebuild(@PathVariable long id) {
        SpaceConf conf = confOf(id);
        List<long[]> docs = jdbc.query(
                "SELECT id, words FROM kb_document WHERE space_id = ? AND status = 'READY'",
                (rs, i) -> new long[]{rs.getLong("id"), rs.getInt("words")}, id);
        jdbc.update("DELETE FROM kb_chunk WHERE space_id = ?", id);
        int total = 0;
        for (long[] doc : docs) {
            total += rechunkDoc(id, doc[0], conf);
        }
        recalcSpace(id);
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
        List<HitView> hits = indexer.search(question, candidates, topN).stream()
                .map(h -> new HitView(h.docId(), h.docTitle(), h.seq(), h.content(), h.score()))
                .toList();
        int took = (int) (System.currentTimeMillis() - start);
        return ResponseEntity.ok(new SearchResponse(question, took,
                "关键词召回（bigram 词面打分）；向量语义检索待 embedding 接入后启用", hits));
    }

    // ---------- 内部：配置 / 解析 / 计数 ----------

    private record SpaceConf(int chunkSize, int chunkOverlap) {
    }

    private SpaceConf confOf(long spaceId) {
        return jdbc.queryForObject(
                "SELECT chunk_size, chunk_overlap FROM kb_space WHERE id = ?",
                (rs, i) -> new SpaceConf(rs.getInt("chunk_size"), rs.getInt("chunk_overlap")), spaceId);
    }

    private void insertDocument(long spaceId, String title, String ext, int sizeKb,
                                String content, SpaceConf conf, String owner) {
        List<TextIndexer.Chunk> chunks = indexer.chunk(content, conf.chunkSize(), conf.chunkOverlap());
        int words = content.length();
        jdbc.update("""
                INSERT INTO kb_document
                    (space_id, title, doc_type, size_kb, words, chunks, status, enabled, content, owner)
                VALUES (?, ?, ?, ?, ?, ?, 'READY', 1, ?, ?)
                """, spaceId, title, ext, sizeKb, words, chunks.size(), content, owner);
        Long docId = jdbc.queryForObject(
                "SELECT id FROM kb_document WHERE space_id = ? ORDER BY id DESC LIMIT 1", Long.class, spaceId);
        insertChunks(spaceId, docId, chunks);
    }

    /** 重建索引用：文档行已存在，删旧切片后按当前配置重切 */
    private int rechunkDoc(long spaceId, long docId, SpaceConf conf) {
        List<String> contents = jdbc.query("SELECT content FROM kb_document WHERE id = ?",
                (rs, i) -> rs.getString("content"), docId);
        String content = contents.isEmpty() ? null : contents.getFirst();
        if (content == null || content.isBlank()) {
            return 0;
        }
        jdbc.update("DELETE FROM kb_chunk WHERE doc_id = ?", docId);
        List<TextIndexer.Chunk> chunks = indexer.chunk(content, conf.chunkSize(), conf.chunkOverlap());
        insertChunks(spaceId, docId, chunks);
        jdbc.update("UPDATE kb_document SET chunks = ?, words = ? WHERE id = ?",
                chunks.size(), content.length(), docId);
        return chunks.size();
    }

    private void insertChunks(long spaceId, Long docId, List<TextIndexer.Chunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO kb_chunk (space_id, doc_id, seq, content, words) VALUES (?, ?, ?, ?, ?)",
                chunks.stream()
                        .map(c -> new Object[]{spaceId, docId, c.seq(), c.content(), c.words()})
                        .toList());
    }

    /** 空间计数与状态由文档实时派生：无文档=BUILDING，有失败无可用=FAILED，否则 READY */
    private void recalcSpace(long spaceId) {
        jdbc.update("""
                UPDATE kb_space s SET
                    s.doc_count = (SELECT COUNT(*) FROM kb_document d WHERE d.space_id = s.id),
                    s.chunk_total = (SELECT COALESCE(SUM(d.chunks), 0) FROM kb_document d
                                     WHERE d.space_id = s.id AND d.enabled = 1),
                    s.status = CASE
                        WHEN (SELECT COUNT(*) FROM kb_document d WHERE d.space_id = s.id) = 0 THEN 'BUILDING'
                        WHEN (SELECT COUNT(*) FROM kb_document d WHERE d.space_id = s.id AND d.status = 'READY'
                              AND d.enabled = 1) > 0 THEN 'READY'
                        ELSE 'FAILED'
                    END
                WHERE s.id = ?
                """, spaceId);
    }

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
