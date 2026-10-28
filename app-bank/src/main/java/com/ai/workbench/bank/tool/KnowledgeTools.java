package com.ai.workbench.bank.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.ai.workbench.bank.audit.ToolAuditLogger;
import com.ai.workbench.core.kb.TextIndexer;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 业务知识检索工具：小银的制度/费率/流程类咨询接平台知识库（RAG）。
 *
 * 数据源直接复用 platform-core 的 kb_* 表与 TextIndexer（bigram 关键词召回，两个模块
 * 同库同容器，不需要跨服务调用）。检索哪些空间由 kb_binding 决定——运营在知识库管理页
 * 把空间绑定给银行助手（agent_key='bank'）即生效，换语料、加文档都不用改代码。
 *
 * 工具只读不写，且对三种身份（零售/员工/对公）一视同仁：制度条文不是客户敏感数据，
 * 员工做业务咨询同样需要。检索结果作为「引用依据」返回，要求模型注明出处、查不到就如实说，
 * 防止拿对话记忆编造费率与政策。
 */
@Component
public class KnowledgeTools {

    /** 每次返回的命中切片数：太多会稀释上下文，银行制度条文一段足够回答一个问题 */
    private static final int TOP_N = 3;

    /** 本工具的 Agent 标识；绑定表里支持 agent[:identity] 编码（见 bindingVisible） */
    private static final String AGENT_KEY = "bank";

    private final JdbcTemplate jdbc;
    private final TextIndexer indexer;
    private final ToolAuditLogger audit;

    public KnowledgeTools(JdbcTemplate jdbc, TextIndexer indexer, ToolAuditLogger audit) {
        this.jdbc = jdbc;
        this.indexer = indexer;
        this.audit = audit;
    }

    @Tool("检索银行业务知识库（制度、费率、限额政策、办理流程等业务咨询）。"
            + "此类问题必须先调用本工具，依据检索内容回答并注明出处文档；检索不到时如实告知用户")
    public String searchKnowledge(@P("要咨询的业务问题，如「新开账户多久能提升转账限额」") String question) {
        String memoryId = ToolCallContext.currentMemoryId();
        try {
            // 检索范围 = 绑定给银行助手、且对当前身份可见的空间。空间 id 来自库表而非配置文件：
            // kb_binding 是平台知识库的既有绑定机制，运营改绑定立即生效
            List<Long> spaceIds = new ArrayList<>();
            for (String[] binding : jdbc.query("""
                    SELECT space_id, agent_key FROM kb_binding WHERE agent_key = ? OR agent_key LIKE ?
                    """, (rs, i) -> new String[]{rs.getString("agent_key"), String.valueOf(rs.getLong("space_id"))},
                    AGENT_KEY, AGENT_KEY + ":%")) {
                if (bindingVisible(binding[0])) {
                    spaceIds.add(Long.parseLong(binding[1]));
                }
            }
            if (spaceIds.isEmpty()) {
                return "未配置可用的业务知识库，无法检索。请如实告知用户暂不支持该咨询。";
            }
            String inClause = spaceIds.stream().map(id -> "?").collect(Collectors.joining(","));
            List<TextIndexer.Candidate> candidates = jdbc.query("""
                    SELECT c.doc_id, d.title AS doc_title, c.seq, c.content, d.enabled
                    FROM kb_chunk c JOIN kb_document d ON d.id = c.doc_id
                    WHERE c.space_id IN (%s)
                    """.formatted(inClause),
                    (rs, i) -> new TextIndexer.Candidate(rs.getLong("doc_id"), rs.getString("doc_title"),
                            rs.getInt("seq"), rs.getString("content"), rs.getBoolean("enabled")),
                    spaceIds.toArray());
            List<TextIndexer.Hit> hits = indexer.search(question, candidates, TOP_N);
            if (hits.isEmpty()) {
                audit.record(memoryId, "searchKnowledge", "未命中: " + question, "SUCCESS");
                return "知识库中未检索到与该问题相关的内容。请如实告知用户暂无相关知识，不要编造。";
            }
            audit.record(memoryId, "searchKnowledge",
                    "命中 %d 片: %s".formatted(hits.size(), question), "SUCCESS");
            StringBuilder text = new StringBuilder("知识库检索结果（按相关度排序）：\n");
            for (int i = 0; i < hits.size(); i++) {
                TextIndexer.Hit hit = hits.get(i);
                text.append("\n[%d] 出处《%s》（相关度 %.2f）\n%s\n"
                        .formatted(i + 1, hit.docTitle(), hit.score(), hit.content()));
            }
            return text + "\n请依据以上内容回答，回答末尾注明引用的文档名；内容不足以回答时如实说明。";
        } catch (Exception e) {
            return "知识库检索异常，请告知用户稍后重试。";
        }
    }

    /**
     * 绑定是否对当前身份可见：agent_key 支持 agent[:identity] 编码——
     * 'bank' 是全域绑定（三种身份都能检索），'bank:RETAIL' 只有零售会话可见。
     * 身份区分的价值：对公客户问「对公开户费率」不该召回零售制度的答案；
     * 员工做咨询时绑到哪个库取决于运营，编码本身不限制。
     */
    private boolean bindingVisible(String agentKey) {
        int sep = agentKey.indexOf(':');
        if (sep < 0) {
            return true;
        }
        return agentKey.substring(sep + 1).equals(ToolCallContext.currentIdentity().name());
    }
}
