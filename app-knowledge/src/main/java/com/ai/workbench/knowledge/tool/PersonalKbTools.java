package com.ai.workbench.knowledge.tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ai.workbench.core.kb.HybridSearchService;
import com.ai.workbench.core.kb.TextIndexer;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 个人知识检索工具：Agent 回答知识/笔记类问题的依据来源。
 *
 * 检索范围由两部分组成——当前登录人的个人空间（owner + space_type='PERSONAL'）
 * 与绑定给本助手的平台空间（kb_binding.agent_key='knowledge'）。空间 id 来自库表
 * 而非配置文件：运营在平台改绑定立即生效，个人空间懒创建后即刻可检索，都不用改代码。
 *
 * 数据权限：个人空间 id 按 ThreadLocal 里的登录名查询（见 KnowledgeToolContext），
 * 模型在工具参数里无法指定「检索谁的空间」，跨人检索在数据面上不可达。
 */
@Component
public class PersonalKbTools {

    /** 每次返回的命中切片数：太多会稀释上下文，个人笔记一次问答几段足够 */
    private static final int TOP_N = 4;

    /** 本工具的 Agent 标识；'knowledge:%' 的身份编码为多身份语义预留（对齐 bank:RETAIL 形态），
     *  个人会话没有银行式身份细分，暂不解析后缀 */
    private static final String AGENT_KEY = "knowledge";

    private final JdbcTemplate jdbc;
    private final HybridSearchService hybrid;

    public PersonalKbTools(JdbcTemplate jdbc, HybridSearchService hybrid) {
        this.jdbc = jdbc;
        this.hybrid = hybrid;
    }

    @Tool("检索用户的个人知识库与绑定给本助手的平台知识库（上传文档、网页收藏、笔记等）。"
            + "知识/笔记类问题必须先调用本工具，依据检索内容回答并注明出处文档；"
            + "检索不到时如实告知用户，不要编造")
    public String searchMyKnowledge(@P("要检索的问题或关键词") String question) {
        try {
            String username = KnowledgeToolContext.currentUsername();
            // 个人空间：当前登录人自己的库（可能还没懒创建过 → 为空）
            List<Long> personalIds = username == null || username.isBlank()
                    ? List.of()
                    : jdbc.query("""
                            SELECT id FROM kb_space WHERE owner = ? AND space_type = 'PERSONAL'
                            """, (rs, i) -> rs.getLong("id"), username);
            // 平台空间：绑定表决定的共享知识库
            List<Long> platformIds = jdbc.query(
                    "SELECT space_id FROM kb_binding WHERE agent_key = ?",
                    (rs, i) -> rs.getLong("space_id"), AGENT_KEY);
            // LinkedHashSet 去重：理论上个人空间不会被绑到 agent_key 上，防御将来配置失误
            Set<Long> spaceIds = new LinkedHashSet<>(personalIds);
            spaceIds.addAll(platformIds);
            if (spaceIds.isEmpty()) {
                return "暂无可检索的知识库，请告知用户先上传文档。";
            }
            // docId → space_id 映射：命中结果要标注出处库别（个人库/平台库）
            String inClause = String.join(",", spaceIds.stream().map(id -> "?").toList());
            Map<Long, Long> docSpace = new HashMap<>();
            List<TextIndexer.Candidate> candidates = jdbc.query("""
                    SELECT c.doc_id, c.space_id, d.title AS doc_title, c.seq, c.content, d.enabled
                    FROM kb_chunk c JOIN kb_document d ON d.id = c.doc_id
                    WHERE c.space_id IN (%s)
                    """.formatted(inClause),
                    (rs, i) -> {
                        docSpace.put(rs.getLong("doc_id"), rs.getLong("space_id"));
                        return new TextIndexer.Candidate(rs.getLong("doc_id"), rs.getString("doc_title"),
                                rs.getInt("seq"), rs.getString("content"), rs.getBoolean("enabled"));
                    },
                    spaceIds.toArray());
            List<TextIndexer.Hit> hits = hybrid.search(
                    question, candidates, spaceIds, TOP_N);
            if (hits.isEmpty()) {
                return "知识库中未检索到与该问题相关的内容。请如实告知用户暂无相关知识，不要编造。";
            }
            StringBuilder text = new StringBuilder("知识库检索结果（按相关度排序）：\n");
            for (int i = 0; i < hits.size(); i++) {
                TextIndexer.Hit hit = hits.get(i);
                Long spaceId = docSpace.get(hit.docId());
                String library = spaceId != null && personalIds.contains(spaceId) ? "个人库" : "平台库";
                text.append("\n[%d] 出处《%s》（%s，相关度 %.2f）\n%s\n"
                        .formatted(i + 1, hit.docTitle(), library, hit.score(), hit.content()));
            }
            return text
                    + "\n请依据以上内容回答，回答末尾注明引用的文档名及所属库别；内容不足以回答时如实说明。";
        } catch (Exception e) {
            return "知识库检索异常，请告知用户稍后重试。";
        }
    }
}
