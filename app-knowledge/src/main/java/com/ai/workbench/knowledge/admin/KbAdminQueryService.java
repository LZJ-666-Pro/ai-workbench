package com.ai.workbench.knowledge.admin;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.ai.workbench.core.kb.KbDocService;

/**
 * 个人知识库管理后台查询/治理服务（ADMIN 专用，HTTP 层见 KbAdminController）。
 *
 * 为什么放 app-knowledge：治理对象是 kb_space/kb_document/kb_binding（本域表）
 * 与 platform_event_log（底座表），不触碰银行域表；SQL 风格仿 app-bank 的
 * BankAdminQueryService——JdbcTemplate 直查，动态条件拼 WHERE 但值一律走 ? 占位。
 *
 * 治理边界（一期约定，避免危险的越界操作）：
 *  - 文档级治理只针对 PERSONAL 空间：动手前 JOIN 确认空间类型，防误伤平台库；
 *  - 文档启停 = UPDATE kb_document.enabled（检索候选集带 enabled 过滤，禁用即不召回，
 *    向量保留以便随时重新启用）；删除才走 KbDocService.removeDoc（级联清切片与向量）；
 *  - 绑定只允许 PLATFORM 空间：个人库绑给 Agent 等于把个人内容变成共享语料。
 */
@Service
public class KbAdminQueryService {

    /**
     * kb 检索事件的落库口径：个人库检索工具 PersonalKbTools.searchMyKnowledge 的
     * 工具调用经审计链路写 platform_event_log（app=knowledge，action=工具名）。
     * 按 action 精确匹配而非 category 模糊——category 是大类（chat/tool/search…），
     * 会混进对话等其他事件；app 过滤保证其他应用的同名动作不计入。
     */
    private static final String KB_SEARCH_WHERE =
            " WHERE app = 'knowledge' AND action = 'searchMyKnowledge'";

    private static final DateTimeFormatter MM_DD = DateTimeFormatter.ofPattern("MM-dd");

    private final JdbcTemplate jdbc;
    private final KbDocService docService;

    public KbAdminQueryService(JdbcTemplate jdbc, KbDocService docService) {
        this.jdbc = jdbc;
        this.docService = docService;
    }

    // ==================== DTO（契约字段小驼峰，前端并行开发中不得偏离） ====================

    /** 趋势点：date 展示格式 MM-dd（前端 x 轴直用） */
    public record TrendPoint(String date, long count) {
    }

    public record Overview(long personalSpaces, long readySpaces, long totalDocs, long failedDocs,
                           long totalSizeMb, long totalWords, long totalChunks, List<TrendPoint> trend) {
    }

    public record LibraryItem(long spaceId, String owner, String displayName, String name,
                              int docCount, int chunkTotal, long sizeKb, String createdAt) {
    }

    /** 通用分页壳：total 全量计数 + items 当前页 */
    public record PageResult<T>(long total, List<T> items) {
    }

    /** 管理员视角的文档行（列同 PersonalKbController 的 documents，复用同一契约形状） */
    public record AdminDocView(long id, String title, String docType, int sizeKb, int words, int chunks,
                               String status, boolean enabled, String sourceUrl, String createdAt) {
    }

    public record BindingItem(long spaceId, String spaceName, int docCount, String createdAt) {
    }

    /** 审计行：platform_event_log 的 actor 即登录用户名 */
    public record AuditItem(long id, String createdAt, String username, String action,
                            String detail, String result) {
    }

    // ==================== 总览 ====================

    /**
     * 总览统计：PERSONAL 空间聚合 + 关联文档聚合 + 近 7 日检索趋势，一次 SQL 拿全
     * （标量子查询写法同 BankAdminQueryService.overview，避免多趟往返口径漂移）。
     */
    public Overview overview() {
        return jdbc.queryForObject(
                "SELECT"
                + " (SELECT COUNT(*) FROM kb_space WHERE space_type = 'PERSONAL') AS spaces,"
                + " (SELECT COUNT(*) FROM kb_space WHERE space_type = 'PERSONAL' AND status = 'READY') AS ready_spaces,"
                + " (SELECT COUNT(*) FROM kb_document d JOIN kb_space s ON s.id = d.space_id"
                + "   WHERE s.space_type = 'PERSONAL') AS total_docs,"
                + " (SELECT COUNT(*) FROM kb_document d JOIN kb_space s ON s.id = d.space_id"
                + "   WHERE s.space_type = 'PERSONAL' AND d.status = 'FAILED') AS failed_docs,"
                + " (SELECT COALESCE(SUM(d.size_kb), 0) FROM kb_document d JOIN kb_space s ON s.id = d.space_id"
                + "   WHERE s.space_type = 'PERSONAL') AS total_size_kb,"
                + " (SELECT COALESCE(SUM(d.words), 0) FROM kb_document d JOIN kb_space s ON s.id = d.space_id"
                + "   WHERE s.space_type = 'PERSONAL') AS total_words,"
                + " (SELECT COALESCE(SUM(d.chunks), 0) FROM kb_document d JOIN kb_space s ON s.id = d.space_id"
                + "   WHERE s.space_type = 'PERSONAL') AS total_chunks",
                (rs, i) -> new Overview(
                        rs.getLong("spaces"), rs.getLong("ready_spaces"),
                        rs.getLong("total_docs"), rs.getLong("failed_docs"),
                        // 契约要 MB：KB 累计值除 1024 后四舍五入，小库不至于恒显 0
                        Math.round(rs.getLong("total_size_kb") / 1024.0),
                        rs.getLong("total_words"), rs.getLong("total_chunks"),
                        trend7d()));
    }

    /** 近 7 日 kb 检索按日计数：无事件日期补零保证 x 轴连续（同 BankAdminQueryService.dailyFlow） */
    private List<TrendPoint> trend7d() {
        LocalDate from = LocalDate.now().minusDays(6);
        record DayCount(LocalDate day, long count) {
        }
        List<DayCount> rows = jdbc.query(
                "SELECT DATE(created_at) AS d, COUNT(*) AS cnt FROM platform_event_log"
                        + KB_SEARCH_WHERE + " AND DATE(created_at) >= ? GROUP BY DATE(created_at)",
                (rs, i) -> new DayCount(rs.getDate("d").toLocalDate(), rs.getLong("cnt")),
                from);
        Map<LocalDate, Long> byDate = new LinkedHashMap<>();
        for (DayCount row : rows) {
            byDate.put(row.day(), row.count());
        }
        List<TrendPoint> out = new ArrayList<>(7);
        for (int i = 0; i < 7; i++) {
            LocalDate d = from.plusDays(i);
            out.add(new TrendPoint(d.format(MM_DD), byDate.getOrDefault(d, 0L)));
        }
        return out;
    }

    // ==================== 用户库治理 ====================

    /**
     * 个人库分页列表：keyword 对 owner/库名模糊匹配。
     * display_name JOIN platform_user 取用户昵称——但 LEFT JOIN + owner 兜底，
     * 用户被删或测试库没有用户表时列表照常可用，不因昵称缺失而整页报错。
     */
    public PageResult<LibraryItem> libraries(int page, int size, String keyword) {
        StringBuilder where = new StringBuilder(" WHERE s.space_type = 'PERSONAL'");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (s.owner LIKE ? OR s.name LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        String w = where.toString();
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM kb_space s" + w, Long.class, args.toArray());
        List<LibraryItem> items = jdbc.query("""
                SELECT s.id, s.owner, u.display_name, s.name, s.doc_count, s.chunk_total,
                       COALESCE((SELECT SUM(d.size_kb) FROM kb_document d WHERE d.space_id = s.id), 0) AS size_kb,
                       s.created_at
                FROM kb_space s LEFT JOIN platform_user u ON u.username = s.owner
                """ + w + " ORDER BY s.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new LibraryItem(
                        rs.getLong("id"), rs.getString("owner"),
                        rs.getString("display_name") == null ? rs.getString("owner") : rs.getString("display_name"),
                        rs.getString("name"), rs.getInt("doc_count"), rs.getInt("chunk_total"),
                        rs.getLong("size_kb"), formatTime(rs.getTimestamp("created_at"))),
                appendPaging(args, page, size).toArray());
        return new PageResult<>(total, items);
    }

    /** 某个人库的文档列表（列同 PersonalKbController.documents）；非 PERSONAL/不存在 → IllegalArgumentException */
    public List<AdminDocView> personalDocuments(long spaceId) {
        requirePersonalSpace(spaceId);
        return jdbc.query("""
                SELECT id, title, doc_type, size_kb, words, chunks, status, enabled, source_url, created_at
                FROM kb_document WHERE space_id = ? ORDER BY id DESC
                """, (rs, i) -> new AdminDocView(rs.getLong("id"), rs.getString("title"),
                rs.getString("doc_type"), rs.getInt("size_kb"), rs.getInt("words"), rs.getInt("chunks"),
                rs.getString("status"), rs.getBoolean("enabled"), rs.getString("source_url"),
                formatTime(rs.getTimestamp("created_at"))), spaceId);
    }

    /**
     * 管理员停用/启用个人库文档：只改 enabled 位。禁用后检索候选集带 enabled=false
     * 被过滤，即刻不召回；向量库保留，重新启用零成本。空间派生计数（chunk_total
     * 只统计启用文档）随 recalcSpace 刷新，列表页数字不漂移。
     */
    public void setDocEnabled(long docId, boolean enabled) {
        long spaceId = personalSpaceIdOfDoc(docId);
        jdbc.update("UPDATE kb_document SET enabled = ? WHERE id = ?", enabled ? 1 : 0, docId);
        docService.recalcSpace(spaceId);
    }

    /**
     * 管理员删除个人库文档：走 KbDocService.removeDoc 级联清切片与向量索引，
     * 防已删内容从向量路残留召回（幽灵切片）——删除语义只有一份实现。
     */
    public void deletePersonalDoc(long docId) {
        long spaceId = personalSpaceIdOfDoc(docId);
        if (!docService.removeDoc(spaceId, docId)) {
            // JOIN 刚确认过存在，走到这里只可能是并发删除，如实报错而非静默成功
            throw new IllegalStateException("文档不存在");
        }
    }

    /** 文档所属空间必须存在且为 PERSONAL：平台库文档不在个人库治理范围内（防误伤） */
    private long personalSpaceIdOfDoc(long docId) {
        List<Long> spaceIds = jdbc.query("""
                SELECT s.id FROM kb_document d JOIN kb_space s ON s.id = d.space_id
                WHERE d.id = ? AND s.space_type = 'PERSONAL'
                """, (rs, i) -> rs.getLong(1), docId);
        if (spaceIds.isEmpty()) {
            throw new IllegalStateException("文档不存在或不属于个人知识库（docId=" + docId + "）");
        }
        return spaceIds.getFirst();
    }

    /** 空间存在且为 PERSONAL 才放行；否则 IllegalArgumentException → 控制器转 404 */
    private void requirePersonalSpace(long spaceId) {
        List<Long> ids = jdbc.query(
                "SELECT id FROM kb_space WHERE id = ? AND space_type = 'PERSONAL'",
                (rs, i) -> rs.getLong(1), spaceId);
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("个人知识库不存在（spaceId=" + spaceId + "）");
        }
    }

    // ==================== 绑定管理 ====================

    /** knowledge Agent 当前绑定的平台空间（检索的数据源配置，此前无任何 UI 可管） */
    public List<BindingItem> bindings() {
        return jdbc.query("""
                SELECT b.space_id, s.name AS space_name, s.doc_count, b.created_at
                FROM kb_binding b JOIN kb_space s ON s.id = b.space_id
                WHERE b.agent_key = 'knowledge'
                ORDER BY b.id DESC
                """, (rs, i) -> new BindingItem(rs.getLong("space_id"), rs.getString("space_name"),
                rs.getInt("doc_count"), formatTime(rs.getTimestamp("created_at"))));
    }

    /**
     * 绑定平台空间给 knowledge Agent：只允许 PLATFORM 空间——个人库一旦绑定，
     * 库主之外的用户检索也会召回其个人内容，等于把私密笔记变成共享语料。
     * 重复绑定撞 uk_kb_binding，按「已绑定」幂等处理。
     */
    public void bind(long spaceId) {
        List<String> types = jdbc.query("SELECT space_type FROM kb_space WHERE id = ?",
                (rs, i) -> rs.getString(1), spaceId);
        if (types.isEmpty()) {
            throw new IllegalArgumentException("知识库空间不存在（spaceId=" + spaceId + "）");
        }
        if (!"PLATFORM".equals(types.getFirst())) {
            throw new IllegalStateException("个人知识库不允许绑定给 Agent（会把个人内容变成共享语料）");
        }
        try {
            jdbc.update("INSERT INTO kb_binding (space_id, agent_key) VALUES (?, 'knowledge')", spaceId);
        } catch (DuplicateKeyException e) {
            // 幂等：重复绑定视为成功，前端无需区分「新建」与「已存在」
        }
    }

    /** 解绑：按空间 + agent_key 删，重复调用删 0 行也是成功（幂等） */
    public void unbind(long spaceId) {
        jdbc.update("DELETE FROM kb_binding WHERE agent_key = 'knowledge' AND space_id = ?", spaceId);
    }

    // ==================== 检索审计 ====================

    /** kb 检索审计分页：platform_event_log 里本应用的检索工具调用，倒序给最新在前 */
    public PageResult<AuditItem> audit(int page, int size) {
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_event_log" + KB_SEARCH_WHERE, Long.class);
        List<AuditItem> items = jdbc.query(
                "SELECT id, actor, action, detail, result, created_at FROM platform_event_log"
                        + KB_SEARCH_WHERE + " ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new AuditItem(rs.getLong("id"), formatTime(rs.getTimestamp("created_at")),
                        rs.getString("actor"), rs.getString("action"),
                        rs.getString("detail"), rs.getString("result")),
                size, (long) (page - 1) * size);
        return new PageResult<>(total, items);
    }

    // ==================== 内部工具 ====================

    private static List<Object> appendPaging(List<Object> args, int page, int size) {
        List<Object> all = new ArrayList<>(args);
        all.add(size);
        all.add((long) (page - 1) * size);
        return all;
    }

    private static String formatTime(Timestamp ts) {
        return ts == null ? "" : ts.toLocalDateTime()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
