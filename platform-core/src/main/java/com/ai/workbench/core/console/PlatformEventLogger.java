package com.ai.workbench.core.console;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 平台运行日志写入端（表 platform_event_log，见 V6 迁移）。
 *
 * 与 bank_audit_log 的分工：那张是银行业务域的合规审计（只记业务工具调用，字段为银行定制），
 * 这张是平台运行日志（形状跨应用统一，还记对话、登录、护栏拒绝、知识库检索等）。
 * 消费者不同：前者给管理后台审计页与合规追溯，后者给工作台首页与「查看日志」。
 *
 * 写入绝不抛异常：日志是旁路设施，不能因为记日志失败把一次成功的对话变成报错。
 * 失败只留 WARN，由调用方继续。
 */
@Component
public class PlatformEventLogger {

    private static final Logger log = LoggerFactory.getLogger(PlatformEventLogger.class);

    /** 结果取值（与页面配色一一对应） */
    public static final String SUCCESS = "SUCCESS";
    public static final String DENY = "DENY";
    public static final String FAIL = "FAIL";

    /** 应用标识 */
    public static final String APP_BANK = "bank";
    public static final String APP_KNOWLEDGE = "knowledge";
    public static final String APP_INTERVIEW = "interview";
    public static final String APP_PLATFORM = "platform";

    /** 事件类别 */
    public static final String CATEGORY_CHAT = "chat";
    public static final String CATEGORY_TOOL = "tool";
    public static final String CATEGORY_AUTH = "auth";
    public static final String CATEGORY_GUARD = "guard";
    public static final String CATEGORY_SEARCH = "search";
    public static final String CATEGORY_INTERVIEW = "interview";

    private static final int DETAIL_MAX = 500;

    private final JdbcTemplate jdbc;

    public PlatformEventLogger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 一条平台事件。用 record + 链式 wither 而不是长参数列表：
     * 十来个位置参数很容易传错顺序，而传错顺序的日志不会报错、只会一直错下去。
     */
    public record PlatformEvent(String app, String category, String action, String detail, String result,
                                String actor, String memoryId, Integer durationMs, Integer tokens,
                                String traceId) {

        public static PlatformEvent of(String app, String category, String action,
                                       String detail, String result) {
            return new PlatformEvent(app, category, action, detail, result, null, null, null, null, null);
        }

        public PlatformEvent by(String actor) {
            return new PlatformEvent(app, category, action, detail, result, actor, memoryId,
                    durationMs, tokens, traceId);
        }

        public PlatformEvent session(String memoryId) {
            return new PlatformEvent(app, category, action, detail, result, actor, memoryId,
                    durationMs, tokens, traceId);
        }

        public PlatformEvent timing(Integer durationMs, Integer tokens) {
            return new PlatformEvent(app, category, action, detail, result, actor, memoryId,
                    durationMs, tokens, traceId);
        }

        public PlatformEvent tracedBy(String traceId) {
            return new PlatformEvent(app, category, action, detail, result, actor, memoryId,
                    durationMs, tokens, traceId);
        }
    }

    public void record(PlatformEvent event) {
        try {
            jdbc.update("""
                    INSERT INTO platform_event_log
                        (app, category, action, actor, memory_id, detail, result,
                         duration_ms, tokens, trace_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    event.app(), event.category(), event.action(), event.actor(), event.memoryId(),
                    truncate(event.detail(), DETAIL_MAX), event.result(),
                    event.durationMs(), event.tokens(), event.traceId());
        } catch (Exception e) {
            log.warn("平台事件写入失败（不影响本次请求）: app={}, action={}, err={}",
                    event.app(), event.action(), e.getMessage());
        }
    }

    private static String truncate(String value, int max) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
