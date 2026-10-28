package com.ai.workbench.core.kb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;

/**
 * 混合检索服务：关键词召回（词面）+ 向量召回（语义）双路融合。
 *
 * 为什么不只留关键词：bigram 打分是词面匹配，「提升到账时间」和「多久能提高限额」
 * 词面几乎不相交，纯关键词会漏；为什么不只留向量：制度条文里的金额、编号等精确
 * 词面必须硬命中，且向量链路依赖外部模型可用性。两路 RRF 融合互补。
 *
 * 降级设计：embedding/pgvector 是条件装配的 Bean（ai.embedding / ai.rag 开关），
 * 未启用时本服务退化为纯关键词——调用方无感知。向量路一旦出错（网关无权限、
 * pgvector 宕机）熔断到进程生命周期结束，避免每个问题都等一次超时。
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    /** RRF 常数：排名越靠前贡献越大，k 取业界常用值平滑排名差异 */
    private static final int RRF_K = 60;

    private final ObjectProvider<EmbeddingModel> embeddings;
    private final ObjectProvider<EmbeddingStore<TextSegment>> stores;
    private final TextIndexer indexer;

    /** 首次向量调用失败后置位：本进程内不再尝试（等重启，而非每个问题等超时） */
    private volatile boolean vectorDisabled = false;

    public HybridSearchService(ObjectProvider<EmbeddingModel> embeddings,
                               ObjectProvider<EmbeddingStore<TextSegment>> stores,
                               TextIndexer indexer) {
        this.embeddings = embeddings;
        this.stores = stores;
        this.indexer = indexer;
    }

    /** 向量路是否参与检索（Bean 未装配或已熔断时为 false） */
    public boolean vectorEnabled() {
        return !vectorDisabled && embeddings.getIfAvailable() != null && stores.getIfAvailable() != null;
    }

    /**
     * 混合检索：spaceIds 限定向量路与候选集的检索范围（身份过滤在调用方完成）。
     * candidates 是 MySQL 关键词索引的全量切片——既是关键词路的输入，
     * 也是向量命中回填元数据（标题、enabled）的字典：pgvector 只存了 id，
     * 展示所需的一切仍以 MySQL 为准，向量库丢了可随时重建。
     */
    public List<TextIndexer.Hit> search(String question, List<TextIndexer.Candidate> candidates,
                                        Set<Long> spaceIds, int topN) {
        List<TextIndexer.Hit> keywordHits = indexer.search(question, candidates, topN);
        if (!vectorEnabled()) {
            return keywordHits;
        }
        try {
            EmbeddingModel model = embeddings.getIfAvailable();
            EmbeddingStore<TextSegment> store = stores.getIfAvailable();
            Map<String, TextIndexer.Candidate> byKey = new HashMap<>();
            for (TextIndexer.Candidate c : candidates) {
                byKey.put(c.docId() + ":" + c.seq(), c);
            }
            List<EmbeddingMatch<TextSegment>> matches = store.search(EmbeddingSearchRequest.builder()
                    .queryEmbedding(model.embed(question).content())
                    .maxResults(topN * 3)
                    .filter(orFilter(spaceIds))
                    .build()).matches();
            List<TextIndexer.Hit> vectorHits = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> match : matches) {
                Metadata md = match.embedded().metadata();
                long docId = md.getLong("doc_id");
                // getLong 返回包装类 Long，直接强转会被编译器拒绝，走 intValue 拆箱
                int seq = md.getLong("seq").intValue();
                TextIndexer.Candidate c = byKey.get(docId + ":" + seq);
                // 向量库里查不到对应切片（被删除/禁用后未同步）时丢弃：展示与权限以 MySQL 为准
                if (c == null || !c.enabled()) {
                    continue;
                }
                vectorHits.add(new TextIndexer.Hit(docId, c.docTitle(), seq, c.content(), match.score()));
            }
            return rrfMerge(keywordHits, vectorHits, topN);
        } catch (Exception e) {
            vectorDisabled = true;
            log.warn("向量检索不可用，本次运行退化为纯关键词召回：{}", e.getMessage());
            return keywordHits;
        }
    }

    /** 文档入库时同步向量化（embedding 未启用时 no-op，等 ai.embedding 打开 + 重建索引补齐） */
    public void indexDoc(long spaceId, long docId, String docTitle, List<TextIndexer.Chunk> chunks) {
        if (!vectorEnabled()) {
            return;
        }
        try {
            EmbeddingModel model = embeddings.getIfAvailable();
            EmbeddingStore<TextSegment> store = stores.getIfAvailable();
            for (TextIndexer.Chunk c : chunks) {
                Metadata md = new Metadata()
                        .put("space_id", spaceId)
                        .put("doc_id", docId)
                        .put("seq", (long) c.seq())
                        .put("title", docTitle);
                store.add(model.embed(c.content()).content(), TextSegment.from(c.content(), md));
            }
        } catch (Exception e) {
            log.warn("文档向量索引失败（docId={}）：{}", docId, e.getMessage());
        }
    }

    /** 删除文档向量（文档删除/禁用时同步，避免向量库留下检索得到的幽灵切片） */
    public void removeDoc(long docId) {
        if (!vectorEnabled()) {
            return;
        }
        try {
            stores.getIfAvailable().removeAll(MetadataFilterBuilder.metadataKey("doc_id").isEqualTo(docId));
        } catch (Exception e) {
            log.warn("文档向量删除失败（docId={}）：{}", docId, e.getMessage());
        }
    }

    public void removeSpace(long spaceId) {
        if (!vectorEnabled()) {
            return;
        }
        try {
            stores.getIfAvailable().removeAll(MetadataFilterBuilder.metadataKey("space_id").isEqualTo(spaceId));
        } catch (Exception e) {
            log.warn("空间向量删除失败（spaceId={}）：{}", spaceId, e.getMessage());
        }
    }

    /** 按空间重建向量索引：从 MySQL 读权威数据（READY 且启用的文档），逐片向量化写入 */
    public void reindexSpace(JdbcTemplate jdbc, long spaceId) {
        if (!vectorEnabled()) {
            return;
        }
        removeSpace(spaceId);
        List<long[]> docs = jdbc.query(
                "SELECT id FROM kb_document WHERE space_id = ? AND status = 'READY' AND enabled = 1",
                (rs, i) -> new long[]{rs.getLong("id")}, spaceId);
        for (long[] doc : docs) {
            reindexDoc(jdbc, doc[0]);
        }
    }

    /** 单文档重建：切片文本从 MySQL 读，标题取文档表 */
    public void reindexDoc(JdbcTemplate jdbc, long docId) {
        if (!vectorEnabled()) {
            return;
        }
        String title = jdbc.queryForObject(
                "SELECT title FROM kb_document WHERE id = ?", String.class, docId);
        long spaceId = jdbc.queryForObject(
                "SELECT space_id FROM kb_document WHERE id = ?", Long.class, docId);
        List<String> contents = jdbc.query(
                "SELECT content FROM kb_chunk WHERE doc_id = ? ORDER BY seq",
                (rs, i) -> rs.getString(1), docId);
        List<TextIndexer.Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < contents.size(); i++) {
            chunks.add(new TextIndexer.Chunk(i, contents.get(i), contents.get(i).length()));
        }
        indexDoc(spaceId, docId, title, chunks);
    }

    /**
     * RRF（Reciprocal Rank Fusion）融合：每路按排名贡献 1/(k+rank)，取全路之和。
     * 融合只看排名不看原始分——关键词分与余弦相似度量纲不同，直接加权会互相污染。
     * 静态方法便于对融合语义做纯函数级单测。
     */
    static List<TextIndexer.Hit> rrfMerge(List<TextIndexer.Hit> keywordHits,
                                          List<TextIndexer.Hit> vectorHits, int topN) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, TextIndexer.Hit> meta = new LinkedHashMap<>();
        for (int i = 0; i < keywordHits.size(); i++) {
            TextIndexer.Hit h = keywordHits.get(i);
            scores.merge(key(h), 1.0 / (RRF_K + i + 1), Double::sum);
            meta.putIfAbsent(key(h), h);
        }
        for (int i = 0; i < vectorHits.size(); i++) {
            TextIndexer.Hit h = vectorHits.get(i);
            scores.merge(key(h), 1.0 / (RRF_K + i + 1), Double::sum);
            meta.putIfAbsent(key(h), h);
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topN)
                .map(e -> {
                    TextIndexer.Hit h = meta.get(e.getKey());
                    return new TextIndexer.Hit(h.docId(), h.docTitle(), h.seq(), h.content(), e.getValue());
                })
                .toList();
    }

    private static String key(TextIndexer.Hit h) {
        return h.docId() + ":" + h.seq();
    }

    private static Filter orFilter(Set<Long> spaceIds) {
        Filter filter = null;
        for (Long id : spaceIds) {
            Filter eq = MetadataFilterBuilder.metadataKey("space_id").isEqualTo(id);
            filter = filter == null ? eq : filter.or(eq);
        }
        return filter == null ? MetadataFilterBuilder.metadataKey("space_id").isEqualTo(-1L) : filter;
    }
}
