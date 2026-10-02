package com.ai.workbench.bank.console;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ai.workbench.bank.console.ConsoleDtos.AppCard;
import com.ai.workbench.bank.console.ConsoleDtos.AppHit;
import com.ai.workbench.bank.console.ConsoleDtos.EndpointHit;
import com.ai.workbench.bank.console.ConsoleDtos.HeroCaps;
import com.ai.workbench.bank.console.ConsoleDtos.LogPage;
import com.ai.workbench.bank.console.ConsoleDtos.LogRow;
import com.ai.workbench.bank.console.ConsoleDtos.LogSummary;
import com.ai.workbench.bank.console.ConsoleDtos.Metric;
import com.ai.workbench.bank.console.ConsoleDtos.Notification;
import com.ai.workbench.bank.console.ConsoleDtos.PlatformStats;
import com.ai.workbench.bank.console.ConsoleDtos.SearchResult;
import com.ai.workbench.bank.console.ConsoleDtos.Workbench;
import com.ai.workbench.core.agent.AgentRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 平台工作台读模型：首页所有数字与「查看日志」都从这里来，每一格都对应一句真实 SQL。
 *
 * 为什么放在 app-bank：工作台是平台级视图，按分层本应在 platform-core。但当前只有 app-bank
 * 是真实运行的应用（有登录、有业务数据），另两个应用还是占位；读模型需要同时访问
 * platform_event_log（底座表）与银行域表，放在 app-bank 才能既不打破"底座不认识业务表"
 * 的边界、又拿到真实数据。Phase 2 合并为 app-platform 时，这里随之上移。
 */
@Service
public class PlatformConsoleService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 统计窗口：对话耗时看 7 天（样本足够算 P95），其余今日口径 */
    private static final String RECENT_WINDOW = "created_at >= DATE_SUB(CURDATE(), INTERVAL 7 DAY)";
    private static final String TODAY = "DATE(created_at) = CURDATE()";

    private final JdbcTemplate jdbc;
    private final AgentRegistry agentRegistry;
    /**
     * 延迟解析：应用启动早期就拿 HandlerMapping 会触发过早初始化。
     *
     * 必须按 bean 名限定：Actuator 也注册了一个 RequestMappingHandlerMapping
     * （controllerEndpointHandlerMapping），只按类型注入会抛 NoUniqueBeanDefinitionException——
     * 而那个异常只在真正调用接口时才暴露。
     */
    private final ObjectProvider<RequestMappingHandlerMapping> handlerMappings;

    public PlatformConsoleService(JdbcTemplate jdbc, AgentRegistry agentRegistry,
                                  @Qualifier("requestMappingHandlerMapping")
                                  ObjectProvider<RequestMappingHandlerMapping> handlerMappings) {
        this.jdbc = jdbc;
        this.agentRegistry = agentRegistry;
        this.handlerMappings = handlerMappings;
    }

    // ==================== 首页 ====================

    public Workbench workbench(boolean admin) {
        return new Workbench(hero(), appCards(), platformStats(), notifications(admin), admin);
    }

    /** 能力胶囊：四个数字分别来自 llm_usage / 运行日志 / 会话记忆表 / 工具事件 */
    private HeroCaps hero() {
        long models = count("""
                SELECT COUNT(DISTINCT model) FROM llm_usage
                WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)
                """);
        Integer sseP95 = p95("platform_event_log", "duration_ms",
                "category = 'chat' AND result = 'SUCCESS' AND duration_ms IS NOT NULL AND " + RECENT_WINDOW);
        long memory = count("SELECT COUNT(*) FROM chat_memory");
        long tools = count("SELECT COUNT(DISTINCT action) FROM platform_event_log WHERE category = 'tool'");
        return new HeroCaps(models, sseP95, memory, tools);
    }

    private List<AppCard> appCards() {
        return List.of(bankCard(), knowledgeCard(), interviewCard());
    }

    private AppCard bankCard() {
        // 注意：这里刻意把日期条件写全，而不是用 "... AND " + TODAY 拼接。
        // Java 文本块会**去掉每行行尾空白**，text block 里以 AND 结尾再拼 TODAY
        // 会变成 "ANDDATE(created_at) = CURDATE()" 这种语法错误——而且只在运行到这句时才炸。
        // 需要拼接的地方一律用普通字符串字面量（行尾空格会被保留）。
        long sessions = count("""
                SELECT COUNT(DISTINCT memory_id) FROM platform_event_log
                WHERE app = 'bank' AND memory_id IS NOT NULL AND DATE(created_at) = CURDATE()
                """);
        long toolCalls = count("""
                SELECT COUNT(*) FROM platform_event_log
                WHERE app = 'bank' AND category = 'tool' AND DATE(created_at) = CURDATE()
                """);
        return new AppCard("bank", "银行助手「小银」",
                "交易型 Agent · 工具调用 + 流式对话，支持转账确认卡片、幂等与全程审计",
                "blue", statusOf("bank"), statusTextOf("bank"),
                List.of(new Metric("今日会话", num(sessions)),
                        new Metric("工具调用", num(toolCalls)),
                        new Metric("成功率", rate("app = 'bank' AND " + TODAY) + "%")),
                "/bank");
    }

    private AppCard knowledgeCard() {
        long documents = count("SELECT COUNT(*) FROM knowledge_document");
        Integer searchP95 = p95("knowledge_query_log", "latency_ms", RECENT_WINDOW);
        return new AppCard("knowledge", "个人知识库",
                "检索型 Agent · 多源路由 + RAG + 引用溯源，向量索引已就绪",
                "violet", statusOf("knowledge"), statusTextOf("knowledge"),
                List.of(new Metric("文档", num(documents) + " 篇"),
                        new Metric("检索 P95", searchP95 == null ? "—" : searchP95 + "ms")),
                "/knowledge");
    }

    private AppCard interviewCard() {
        long sessions = count("SELECT COUNT(*) FROM interview_session");
        Double average = jdbc.queryForObject(
                "SELECT AVG(score) FROM interview_session WHERE score IS NOT NULL", Double.class);
        return new AppCard("interview", "面试模拟器",
                "流程型 Agent · 结构化评分与评估报告，支持多轮追问",
                "green", statusOf("interview"), statusTextOf("interview"),
                List.of(new Metric("累计面试", num(sessions) + " 场"),
                        new Metric("平均分", average == null ? "—" : String.valueOf(Math.round(average)))),
                "/interview");
    }

    /** 应用状态取自 Agent 注册中心：注册了才算在线，不是写死的文案 */
    private String statusOf(String agent) {
        return agentRegistry.names().contains(agent) ? "running" : "ready";
    }

    private String statusTextOf(String agent) {
        return "running".equals(statusOf(agent)) ? "运行中" : "未注册";
    }

    private PlatformStats platformStats() {
        long eventsToday = count("SELECT COUNT(*) FROM platform_event_log WHERE " + TODAY);
        // 用普通字符串字面量拼接（行尾空格会保留）；文本块会吃掉行尾空白，见 bankCard 的说明
        Integer avgLatency = jdbc.queryForObject(
                "SELECT ROUND(AVG(duration_ms)) FROM platform_event_log "
                        + "WHERE duration_ms IS NOT NULL AND " + TODAY, Integer.class);
        long sessionsToday = count("""
                SELECT COUNT(DISTINCT memory_id) FROM platform_event_log
                WHERE memory_id IS NOT NULL AND DATE(created_at) = CURDATE()
                """);
        // Token 消耗取 llm_usage 台账（护栏那张表），它记的才是真实的计费用量；
        // 运行日志里的 tokens 是对话事件的附带信息，二者口径不同
        long tokensToday = count("SELECT COALESCE(SUM(input_tokens + output_tokens), 0) "
                + "FROM llm_usage WHERE created_at >= CURDATE()");
        return new PlatformStats(eventsToday, avgLatency, rate(TODAY), apiEndpoints(),
                AGENT_APPS.size(), sessionsToday, tokensToday,
                LocalDateTime.now().format(TIME));
    }

    /**
     * 管理端接口数：直接问 Spring 已注册的映射，而不是写死一个数字——
     * 写死的数字加一个接口就过期，而这是最容易被忽略的一类"假数据"。
     */
    private long apiEndpoints() {
        RequestMappingHandlerMapping mapping = handlerMappings.getIfAvailable();
        if (mapping == null) {
            return 0;
        }
        return mapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPathPatternsCondition() == null
                        ? java.util.stream.Stream.<String>empty()
                        : info.getPathPatternsCondition().getPatternValues().stream())
                .filter(path -> path.startsWith("/api/"))
                .distinct()
                .count();
    }

    /**
     * 通知中心：只有 ADMIN 看得到。待审批转账、风控拒绝这类内容属运维视角，
     * 展示给普通客户等于信息越权——宁可空着也不越权。
     */
    private List<Notification> notifications(boolean admin) {
        List<Notification> list = new ArrayList<>();
        if (!admin) {
            return list;
        }
        long pending = count("SELECT COUNT(*) FROM bank_transfer_order WHERE status = 'PENDING'");
        if (pending > 0) {
            list.add(new Notification("warn", "转账审批：%d 笔确认单待处理".formatted(pending), "/admin/approvals"));
        }
        long denied = count("SELECT COUNT(*) FROM bank_audit_log WHERE result = 'DENY' AND " + TODAY);
        if (denied > 0) {
            list.add(new Notification("warn", "风控拒绝：今日 %d 次工具调用被拦截".formatted(denied), "/admin/audit"));
        }
        long guard = count("SELECT COUNT(*) FROM platform_event_log WHERE category = 'guard' AND " + TODAY);
        if (guard > 0) {
            list.add(new Notification("info", "护栏拦截：今日 %d 次对话被限流或超出预算".formatted(guard), "/logs"));
        }
        if (list.isEmpty()) {
            list.add(new Notification("info", "暂无待处理事项", null));
        }
        return list;
    }

    // ==================== 日志 ====================

    public LogPage logs(String app, String category, String result, String keyword, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (app != null && !app.isBlank()) {
            where.append(" AND app = ?");
            args.add(app.trim());
        }
        if (category != null && !category.isBlank()) {
            where.append(" AND category = ?");
            args.add(category.trim());
        }
        if (result != null && !result.isBlank()) {
            where.append(" AND result = ?");
            args.add(result.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            // 关键字同时匹配动作、明细、会话与 traceId：用户手上往往只有其中一个
            where.append(" AND (action LIKE ? OR detail LIKE ? OR memory_id LIKE ? OR trace_id LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.addAll(List.of(like, like, like, like));
        }
        String w = where.toString();

        long total = count("SELECT COUNT(*) FROM platform_event_log" + w, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<LogRow> rows = jdbc.query("""
                SELECT id, app, category, action, actor, memory_id, detail, result,
                       duration_ms, tokens, trace_id, created_at
                FROM platform_event_log
                """ + w + " ORDER BY id DESC LIMIT ? OFFSET ?",
                logMapper(),
                pageArgs.toArray());
        return new LogPage(rows, total, page, size, summary(w, args));
    }

    /** 日志行映射：分页查询与搜索共用，避免两处 SELECT 的列顺序对不上 */
    private static RowMapper<LogRow> logMapper() {
        return (rs, i) -> new LogRow(
                rs.getLong("id"),
                formatTime(rs.getTimestamp("created_at")),
                rs.getString("app"),
                rs.getString("category"),
                rs.getString("action"),
                rs.getString("actor"),
                rs.getString("memory_id"),
                rs.getString("detail"),
                rs.getString("result"),
                (Integer) rs.getObject("duration_ms"),
                (Integer) rs.getObject("tokens"),
                rs.getString("trace_id"));
    }

    // ==================== 全局搜索 ====================

    /**
     * 平台产品目录：搜索「应用」这一组的数据源，也用来数「平台总应用数」。
     *
     * 为什么是静态目录而不是查库：这是「平台上有哪些入口」的产品事实，
     * 不是业务数据——库里没有也不该有「开发者文档」这张表。
     * 但每个入口的 to 都是真实路由，点进去确实能到。
     *
     * keywords 是必需的：用户搜的是「转账」「余额」这类**业务词**，
     * 而不是「交易型 Agent」这种我们内部的分类词。只匹配名称与描述的话，
     * 搜「转账」会一条应用都命中不了——而用户此刻想找的恰恰是银行助手。
     */
    private record AppEntry(String key, String name, String type, String to, String keywords) {
    }

    /**
     * 三个业务 Agent 应用——工作台上的卡片就是它们，页脚「平台总应用数」数的也是它们。
     * 与下面的平台页面分开：日志页/管理后台/开发者文档是平台自身的功能页，
     * 把它们也算进"应用数"会把 3 说成 6（本人已犯过这个错）。
     */
    private static final List<AppEntry> AGENT_APPS = List.of(
            new AppEntry("bank", "银行助手「小银」", "交易型 Agent · 工具调用 + 流式对话", "/bank",
                    "转账 余额 账户 交易 流水 风控 确认单 银行 客服"),
            new AppEntry("knowledge", "个人知识库", "检索型 Agent · 多源路由 + RAG", "/knowledge",
                    "知识库 文档 检索 rag 引用 制度 产品手册 监管 向量"),
            new AppEntry("interview", "面试模拟器", "流程型 Agent · 结构化评分", "/interview",
                    "面试 评分 简历 追问 评估 候选人 招聘"));

    /** 平台自身的功能页，可被搜索到，但不计入应用数 */
    private static final List<AppEntry> PLATFORM_PAGES = List.of(
            new AppEntry("logs", "平台运行日志", "对话 / 工具调用 / 登录 / 护栏拦截的记录", "/logs",
                    "日志 运行记录 traceid 审计 排障 报错"),
            new AppEntry("admin", "管理后台", "客户 360 / 审批中心 / 资金管理 / 配置中心", "/admin",
                    "管理 后台 客户 审批 资金 限额 配置 用户"),
            new AppEntry("developers", "开发者文档", "AgentSpec 声明与平台 API 说明", "/developers",
                    "接口 api 文档 agentspec 接入 开发者"));

    private static final List<AppEntry> APP_CATALOG =
            java.util.stream.Stream.concat(AGENT_APPS.stream(), PLATFORM_PAGES.stream()).toList();

    /**
     * 全局搜索：应用入口（静态目录）+ 运行日志（查库）+ 已注册接口（问 Spring）。
     *
     * 日志只对 ADMIN 返回：里面有会话 id、traceId 与业务动作明细，与日志页同一把尺子。
     * 非管理员仍能搜应用与接口，不会因为权限不同就整个功能不可用。
     */
    public SearchResult search(String keyword, int limit, boolean admin) {
        String q = keyword == null ? "" : keyword.trim();
        if (q.isEmpty()) {
            return new SearchResult(List.of(), List.of(), List.of());
        }
        return new SearchResult(searchApps(q), admin ? searchLogs(q, limit) : List.of(),
                searchEndpoints(q, limit));
    }

    private static List<AppHit> searchApps(String q) {
        String needle = q.toLowerCase();
        return APP_CATALOG.stream()
                .filter(app -> app.name().toLowerCase().contains(needle)
                        || app.type().toLowerCase().contains(needle)
                        || app.key().toLowerCase().startsWith(needle)
                        || app.keywords().toLowerCase().contains(needle))
                .map(app -> new AppHit(app.key(), app.name(), app.type(), app.to()))
                .toList();
    }

    private List<LogRow> searchLogs(String q, int limit) {
        String like = "%" + q + "%";
        return jdbc.query("""
                SELECT id, app, category, action, actor, memory_id, detail, result,
                       duration_ms, tokens, trace_id, created_at
                FROM platform_event_log
                WHERE action LIKE ? OR detail LIKE ? OR memory_id LIKE ? OR trace_id LIKE ?
                ORDER BY id DESC LIMIT ?
                """, logMapper(), like, like, like, like, limit);
    }

    /** 搜索接口：直接问 Spring 已注册的映射，搜出来的就是真正存在的路径 */
    private List<EndpointHit> searchEndpoints(String q, int limit) {
        RequestMappingHandlerMapping mapping = handlerMappings.getIfAvailable();
        if (mapping == null) {
            return List.of();
        }
        String needle = q.toLowerCase();
        Set<EndpointHit> seen = new LinkedHashSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            if (info.getPathPatternsCondition() == null) {
                continue;
            }
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            String method = methods.isEmpty() ? "ANY" : methods.iterator().next().name();
            for (String path : info.getPathPatternsCondition().getPatternValues()) {
                if (!path.startsWith("/api/") || !path.toLowerCase().contains(needle)) {
                    continue;
                }
                seen.add(new EndpointHit(method, path));
                if (seen.size() >= limit) {
                    return List.copyOf(seen);
                }
            }
        }
        return List.copyOf(seen);
    }

    /** 汇总不受当前页影响，否则统计条会随着翻页变化，读起来像 bug */
    private LogSummary summary(String where, List<Object> args) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN result = 'SUCCESS' THEN 1 ELSE 0 END) AS success,
                       SUM(CASE WHEN result = 'DENY'    THEN 1 ELSE 0 END) AS deny,
                       SUM(CASE WHEN result = 'FAIL'    THEN 1 ELSE 0 END) AS fail
                FROM platform_event_log
                """ + where,
                (rs, i) -> new LogSummary(rs.getLong("total"), rs.getLong("success"),
                        rs.getLong("deny"), rs.getLong("fail")),
                args.toArray());
    }

    // ==================== 查询辅助 ====================

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    /**
     * P95。MySQL 没有 PERCENTILE_CONT，用「排序后取第 95 百分位那一行」等价实现：
     * 先数总数，再按偏移量取一行。样本为空返回 null，页面显示 —，而不是假装是 0。
     *
     * 耗时列名由调用方传入：运行日志叫 duration_ms，知识库检索日志叫 latency_ms——
     * 两者是同一件事（端到端耗时）但按各自表的语义命名，硬编码成其中一个，
     * 另一张表就会在运行期报 Unknown column。
     */
    private Integer p95(String table, String column, String where) {
        long n = count("SELECT COUNT(*) FROM " + table + " WHERE " + where);
        if (n == 0) {
            return null;
        }
        int offset = (int) Math.floor((n - 1) * 0.95);
        List<Integer> values = jdbc.queryForList(
                "SELECT " + column + " FROM " + table + " WHERE " + where
                        + " ORDER BY " + column + " LIMIT 1 OFFSET " + offset,
                Integer.class);
        return values.isEmpty() ? null : values.getFirst();
    }

    /** 成功率（百分比，一位小数）；没有样本时返回 0 而不是 100，避免"没数据看起来最健康" */
    private Double rate(String where) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_event_log WHERE " + where, Long.class);
        if (total == null || total == 0) {
            return 0.0;
        }
        Long ok = jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_event_log WHERE result = 'SUCCESS' AND " + where, Long.class);
        return Math.round((ok == null ? 0 : ok) * 1000.0 / total) / 10.0;
    }

    private static String num(long value) {
        return String.format("%,d", value);
    }

    private static String formatTime(Timestamp ts) {
        return ts == null ? "-" : ts.toLocalDateTime().format(TIME);
    }
}
