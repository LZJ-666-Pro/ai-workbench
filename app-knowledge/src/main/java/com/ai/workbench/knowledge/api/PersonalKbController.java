package com.ai.workbench.knowledge.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ai.workbench.core.auth.CorePrincipal;
import com.ai.workbench.core.kb.DocumentExtractor;
import com.ai.workbench.core.kb.KbDocService;
import com.ai.workbench.knowledge.auth.KnowledgeUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 个人知识库 API（一人一库）：
 *   GET    /api/kb/personal/me                我的库概览（无则懒创建）
 *   GET    /api/kb/personal/me/documents      文档列表
 *   POST   /api/kb/personal/me/documents      上传文档（multipart 多文件）
 *   POST   /api/kb/personal/me/documents/url  抓取网页存为文档
 *   DELETE /api/kb/personal/me/documents/{id} 删除文档
 *
 * 归属模型：个人空间按 owner=登录名 + space_type='PERSONAL' 定位，所有读写都先
 * 经过这一定位，天然隔离他人数据；删除额外做一次 JOIN 校验，防止伪造 docId
 * 跨空间删除（文档 id 不含归属信息，必须回表确认）。持久化链路（提取→切片→
 * 落库→向量同步→计数派生）复用 KbDocService，与平台库保持同一实现。
 */
@RestController
@RequestMapping("/api/kb/personal")
public class PersonalKbController {

    /** 单次上传文件数上限：与平台库 KbController 的约定一致 */
    private static final int MAX_DOCS_PER_UPLOAD = 10;

    /** 单文件大小上限（20MB）：multipart 配置同值，这里是读入字节后的双保险 */
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    /**
     * 企业配额：每人文档数上限（0=不限制）。与 /api/kb/admin 管理后台同源的治理手段，
     * 上限在 application.yml 可调（改配置即生效，不用发版）。
     */
    @Value("${kb.personal.max-docs:200}")
    private int maxDocs;

    /** 企业配额：每人累计存储上限 MB（0=不限制），默认值与 application.yml 保持一致 */
    @Value("${kb.personal.max-total-mb:512}")
    private long maxTotalMb;

    private record SpaceRow(long id, String name, int docCount, int chunkTotal) {
    }

    public record SpaceMe(long id, String name, int docCount, int chunkTotal) {
    }

    public record PersonalDocView(long id, String title, String docType, int sizeKb, int words, int chunks,
                                  String status, boolean enabled, String sourceUrl, String createdAt) {
    }

    public record UploadCreated(long id, String title, String status) {
    }

    public record UploadFailed(String title, String message) {
    }

    public record UploadResponse(boolean ok, List<UploadCreated> created, List<UploadFailed> failed) {
    }

    public record UrlRequest(String url) {
    }

    public record UrlDocument(long id, String title, String status) {
    }

    public record UrlResponse(boolean ok, UrlDocument document) {
    }

    private final JdbcTemplate jdbc;
    private final KbDocService docService;
    private final DocumentExtractor extractor;

    public PersonalKbController(JdbcTemplate jdbc, KbDocService docService, DocumentExtractor extractor) {
        this.jdbc = jdbc;
        this.docService = docService;
        this.extractor = extractor;
    }

    // ---------- 我的库 ----------

    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestAttribute(CorePrincipal.ATTR_PRINCIPAL) KnowledgeUser user) {
        SpaceRow space = mySpace(user.username());
        return ResponseEntity.ok(new SpaceMe(space.id(), space.name(), space.docCount(), space.chunkTotal()));
    }

    @GetMapping("/me/documents")
    public ResponseEntity<?> documents(@RequestAttribute(CorePrincipal.ATTR_PRINCIPAL) KnowledgeUser user) {
        SpaceRow space = mySpace(user.username());
        // mySpace 按 owner 定位，space 必属本人；列表再按 space_id 查，不依赖前端传空间 id
        List<PersonalDocView> docs = jdbc.query("""
                SELECT id, title, doc_type, size_kb, words, chunks, status, enabled, source_url, created_at
                FROM kb_document WHERE space_id = ? ORDER BY id DESC
                """, (rs, i) -> new PersonalDocView(rs.getLong("id"), rs.getString("title"),
                rs.getString("doc_type"), rs.getInt("size_kb"), rs.getInt("words"), rs.getInt("chunks"),
                rs.getString("status"), rs.getBoolean("enabled"), rs.getString("source_url"),
                rs.getString("created_at")), space.id());
        return ResponseEntity.ok(docs);
    }

    // ---------- 上传（多文件） ----------

    /**
     * 同步解析：单文件成功进 created、失败进 failed，两种结果同列表返回——
     * 批量上传时部分失败不该让用户重传全部。ok 表示请求本身处理成功（200），
     * 逐文件结果看两个列表。
     */
    @PostMapping("/me/documents")
    public ResponseEntity<?> upload(@RequestPart("files") List<MultipartFile> files,
                                    @RequestAttribute(CorePrincipal.ATTR_PRINCIPAL) KnowledgeUser user) {
        if (files == null || files.isEmpty()) {
            return badRequest("请选择要上传的文件");
        }
        if (files.size() > MAX_DOCS_PER_UPLOAD) {
            return badRequest("单次最多上传 " + MAX_DOCS_PER_UPLOAD + " 个文件");
        }
        SpaceRow space = mySpace(user.username());
        // 配额闸门在循环外做一次：批量上传任一篇都不该越过企业上限，直接整单拒绝
        String quotaMsg = quotaExceeded(space.id());
        if (quotaMsg != null) {
            return badRequest(quotaMsg);
        }
        List<UploadCreated> created = new ArrayList<>();
        List<UploadFailed> failed = new ArrayList<>();
        for (MultipartFile file : files) {
            String original = file.getOriginalFilename() == null ? "未命名" : file.getOriginalFilename();
            String ext = extOf(original);
            String title = original.length() > 200 ? original.substring(0, 200) : original;
            if (!DocumentExtractor.SUPPORTED.contains(ext)) {
                failed.add(new UploadFailed(title,
                        "不支持的文档类型（支持 txt/md/csv/pdf/doc/docx/html/htm）"));
                continue;
            }
            if (file.getSize() > MAX_FILE_BYTES) {
                failed.add(new UploadFailed(title, "文件超过 20MB 上限"));
                continue;
            }
            try {
                byte[] bytes = file.getBytes();
                int sizeKb = Math.max(bytes.length / 1024, 1);
                try {
                    String content = extractor.extract(bytes, ext);
                    long docId = docService.insertDocument(space.id(), title, ext, sizeKb,
                            content, user.username(), null);
                    created.add(new UploadCreated(docId, title, "READY"));
                } catch (IllegalArgumentException | IllegalStateException e) {
                    // 提不出正文（扫描版 PDF、超 2MB 文本、损坏文件）如实标 FAILED，不造假
                    String message = failMsg(e);
                    docService.insertFailedDocument(space.id(), title, ext, sizeKb, message, user.username());
                    failed.add(new UploadFailed(title, message));
                }
            } catch (IOException e) {
                failed.add(new UploadFailed(title, "文件读取失败"));
            }
        }
        docService.recalcSpace(space.id());
        return ResponseEntity.ok(new UploadResponse(true, created, failed));
    }

    // ---------- 网页抓取 ----------

    /**
     * URL 入库：抓取 → 提取标题与正文 → html 文档 + source_url 回溯出处。
     * 失败（URL 非法、内网地址、超时、超 5MB）统一 400 带原因。
     */
    @PostMapping("/me/documents/url")
    public ResponseEntity<?> saveUrl(@RequestBody UrlRequest req,
                                     @RequestAttribute(CorePrincipal.ATTR_PRINCIPAL) KnowledgeUser user) {
        if (req == null || req.url() == null || req.url().isBlank()) {
            return badRequest("请输入网页链接");
        }
        String url = req.url().trim();
        SpaceRow space = mySpace(user.username());
        // 配额闸门放在抓取之前：超限就别再消耗外网 I/O，快速失败
        String quotaMsg = quotaExceeded(space.id());
        if (quotaMsg != null) {
            return badRequest(quotaMsg);
        }
        DocumentExtractor.Extracted page;
        try {
            page = extractor.fetchUrl(url);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return badRequest(e.getMessage() == null ? "网页抓取失败" : e.getMessage());
        }
        long docId = docService.insertDocument(space.id(), page.title(), "html",
                Math.max(page.text().length() / 1024, 1), page.text(), user.username(), url);
        docService.recalcSpace(space.id());
        return ResponseEntity.ok(new UrlResponse(true, new UrlDocument(docId, page.title(), "READY")));
    }

    // ---------- 删除 ----------

    @DeleteMapping("/me/documents/{docId}")
    public ResponseEntity<?> delete(@PathVariable long docId,
                                    @RequestAttribute(CorePrincipal.ATTR_PRINCIPAL) KnowledgeUser user) {
        // JOIN 确认文档挂在本人个人空间下：文档 id 可伪造，403 对「不存在」与「不是你的」
        // 返回同一措辞，不向探测者泄露文档是否存在
        List<Long> spaceIds = jdbc.query("""
                SELECT s.id FROM kb_document d JOIN kb_space s ON s.id = d.space_id
                WHERE d.id = ? AND s.owner = ? AND s.space_type = 'PERSONAL'
                """, (rs, i) -> rs.getLong(1), docId, user.username());
        if (spaceIds.isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("message", "无权删除该文档"));
        }
        if (!docService.removeDoc(spaceIds.getFirst(), docId)) {
            return badRequest("文档不存在");
        }
        return ResponseEntity.ok(Map.of("ok", true, "message", "文档已删除"));
    }

    // ---------- 内部：个人空间定位与懒创建 ----------

    /**
     * 定位当前用户的个人空间；没有则懒创建「我的知识库（{username}）」。
     * 一人一库靠固定命名 + kb_space 的 uk_kb_name 全局唯一索引保证：并发首访时
     * 后到者撞唯一键，回查复用先到者创建的空间，而不是报错。
     */
    private SpaceRow mySpace(String username) {
        List<SpaceRow> rows = jdbc.query("""
                SELECT id, name, doc_count, chunk_total FROM kb_space
                WHERE owner = ? AND space_type = 'PERSONAL'
                """, (rs, i) -> new SpaceRow(rs.getLong("id"), rs.getString("name"),
                rs.getInt("doc_count"), rs.getInt("chunk_total")), username);
        if (!rows.isEmpty()) {
            return rows.getFirst();
        }
        String name = "我的知识库（" + username + "）";
        try {
            jdbc.update("""
                    INSERT INTO kb_space (name, status, chunk_size, chunk_overlap, owner, space_type)
                    VALUES (?, 'READY', 500, 50, ?, 'PERSONAL')
                    """, name, username);
        } catch (DuplicateKeyException e) {
            // 并发撞 uk_kb_name：空间已被同用户的另一请求创建，回查复用
        }
        return jdbc.queryForObject("""
                SELECT id, name, doc_count, chunk_total FROM kb_space WHERE name = ?
                """, (rs, i) -> new SpaceRow(rs.getLong("id"), rs.getString("name"),
                rs.getInt("doc_count"), rs.getInt("chunk_total")), name);
    }

    private String extOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
    }

    /**
     * 企业配额校验（上传与 URL 抓取两条路共用，落库前调用）：文档数与累计存储
     * 任一超限即拒绝。0=不限制；提示里带上当前用量与上限，用户才知道是哪条
     * 配额满了、找管理员要调到多少。返回 null 表示放行。
     */
    private String quotaExceeded(long spaceId) {
        if (maxDocs <= 0 && maxTotalMb <= 0) {
            return null; // 两条都配 0 = 明确关闭配额
        }
        long[] usage = jdbc.queryForObject("""
                SELECT COUNT(*), COALESCE(SUM(size_kb), 0) FROM kb_document WHERE space_id = ?
                """, (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2)}, spaceId);
        boolean docLimited = maxDocs > 0 && usage[0] >= maxDocs;
        boolean sizeLimited = maxTotalMb > 0 && usage[1] >= maxTotalMb * 1024;
        if (!docLimited && !sizeLimited) {
            return null;
        }
        return "已达企业配额上限（文档 %d/%d 篇，存储 %dMB/%dMB），请联系管理员调整"
                .formatted(usage[0], maxDocs, usage[1] / 1024, maxTotalMb);
    }

    /** error_msg 列是 VARCHAR(255)：异常信息截断到列宽内，避免严格模式下写库报错 */
    private String failMsg(Exception e) {
        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String msg = "解析失败：" + reason;
        return msg.length() > 255 ? msg.substring(0, 255) : msg;
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
}
