package com.ai.workbench.core.kb;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 知识库文档服务：把 KbController 里的入库/重切/计数/级联删除逻辑下沉成公共服务。
 *
 * 为什么要抽：app-knowledge 批次的个人库 API（上传/URL 抓取/删除）要走完全相同的
 * 「提取 → 切片 → kb_document/kb_chunk 落库 → 向量同步 → 计数派生」链路，
 * 控制器私有方法无法复用。本服务只管持久化与索引同步，不做 HTTP 语义与归属校验
 * （归属校验是各控制器按自己的认证模型做的事）。
 */
@Service
public class KbDocService {

    /** 空间切片配置（chunk_size / chunk_overlap），rebuild 场景一次性读出复用 */
    public record SpaceConf(int chunkSize, int chunkOverlap) {
    }

    private final JdbcTemplate jdbc;
    private final TextIndexer indexer;
    private final HybridSearchService hybrid;

    public KbDocService(JdbcTemplate jdbc, TextIndexer indexer, HybridSearchService hybrid) {
        this.jdbc = jdbc;
        this.indexer = indexer;
        this.hybrid = hybrid;
    }

    public SpaceConf confOf(long spaceId) {
        return jdbc.queryForObject(
                "SELECT chunk_size, chunk_overlap FROM kb_space WHERE id = ?",
                (rs, i) -> new SpaceConf(rs.getInt("chunk_size"), rs.getInt("chunk_overlap")), spaceId);
    }

    /**
     * 解析成功的文档入库：正文 + 切片一并落库，状态 READY。
     *
     * @param sourceUrl 网页抓取文档的原始出处，文件上传传 null
     * @return 新文档 id（调用方删除/更新时定位用）
     */
    public long insertDocument(long spaceId, String title, String docType, int sizeKb,
                               String content, String owner, String sourceUrl) {
        SpaceConf conf = confOf(spaceId);
        List<TextIndexer.Chunk> chunks = indexer.chunk(content, conf.chunkSize(), conf.chunkOverlap());
        int words = content.length();
        jdbc.update("""
                INSERT INTO kb_document
                    (space_id, title, doc_type, size_kb, words, chunks, status, enabled, content, source_url, owner)
                VALUES (?, ?, ?, ?, ?, ?, 'READY', 1, ?, ?, ?)
                """, spaceId, title, docType, sizeKb, words, chunks.size(), content, sourceUrl, owner);
        Long docId = jdbc.queryForObject(
                "SELECT id FROM kb_document WHERE space_id = ? ORDER BY id DESC LIMIT 1", Long.class, spaceId);
        insertChunks(spaceId, docId, chunks);
        // 文档入库即向量化（embedding 未启用时 no-op）：原来控制器漏调这一步，
        // 开启 embedding 后新上传文档要等「重建索引」才进向量库，检索出现盲区
        hybrid.indexDoc(spaceId, docId, title, chunks);
        return docId;
    }

    /** 解析失败的文档也要留痕：状态 FAILED + error_msg，空间状态派生依赖这些行 */
    public void insertFailedDocument(long spaceId, String title, String docType, int sizeKb,
                                     String errorMsg, String owner) {
        jdbc.update("""
                INSERT INTO kb_document
                    (space_id, title, doc_type, size_kb, status, error_msg, owner)
                VALUES (?, ?, ?, ?, 'FAILED', ?, ?)
                """, spaceId, title, docType, sizeKb, errorMsg, owner);
    }

    /** 重建索引用：文档行已存在，删旧切片后按当前配置重切；返回新切片数（0=无正文跳过） */
    public int rechunkDoc(long spaceId, long docId, SpaceConf conf) {
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
        // 重切的切片同步向量索引（title 需要回查文档表）
        String title = jdbc.queryForObject("SELECT title FROM kb_document WHERE id = ?", String.class, docId);
        hybrid.indexDoc(spaceId, docId, title, chunks);
        return chunks.size();
    }

    /** 空间计数与状态由文档实时派生：无文档=BUILDING，有失败无可用=FAILED，否则 READY */
    public void recalcSpace(long spaceId) {
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

    /**
     * 删除单个文档：MySQL 切片/正文与 pgvector 向量同步清，防已删内容
     * 从向量路残留召回（幽灵切片）；返回 false 表示文档不存在（或不属于该空间）。
     */
    public boolean removeDoc(long spaceId, long docId) {
        int deleted = jdbc.update("DELETE FROM kb_document WHERE id = ? AND space_id = ?", docId, spaceId);
        if (deleted == 0) {
            return false;
        }
        jdbc.update("DELETE FROM kb_chunk WHERE doc_id = ?", docId);
        hybrid.removeDoc(docId);
        recalcSpace(spaceId);
        return true;
    }

    /**
     * 删除整个空间：级联文档、切片、绑定与向量索引；返回 false 表示空间不存在。
     * 个人库「注销/清空」与平台库删除共用此入口，保证级联语义只有一份。
     */
    public boolean removeSpace(long spaceId) {
        int deleted = jdbc.update("DELETE FROM kb_space WHERE id = ?", spaceId);
        if (deleted == 0) {
            return false;
        }
        jdbc.update("DELETE FROM kb_document WHERE space_id = ?", spaceId);
        jdbc.update("DELETE FROM kb_chunk WHERE space_id = ?", spaceId);
        jdbc.update("DELETE FROM kb_binding WHERE space_id = ?", spaceId);
        hybrid.removeSpace(spaceId);
        return true;
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
}
