package com.ai.workbench.bank.console;

import java.util.List;

/**
 * 平台工作台视图对象（首页与「查看日志」共用）。
 * 每个字段都对应一句真实 SQL 的结果，没有前端写死的常量。
 */
public final class ConsoleDtos {

    private ConsoleDtos() {
    }

    /** 英雄区能力胶囊：4 个指标全部来自数据库 */
    public record HeroCaps(
            long models,
            Integer sseP95Ms,
            long memorySegments,
            long tools) {
    }

    /** 应用卡片 */
    public record AppCard(
            String key,
            String name,
            String type,
            String tone,
            String status,
            String statusText,
            List<Metric> metrics,
            String to) {
    }

    public record Metric(String label, String value) {
    }

    /** 页脚平台状态栏 */
    public record PlatformStats(
            long eventsToday,
            Integer avgLatencyMs,
            Double successRate,
            long endpoints,
            String updatedAt) {
    }

    /** 首页通知中心。ADMIN 才返回内容（待审批转账等属运维视角） */
    public record Notification(String level, String text, String to) {
    }

    public record Workbench(
            HeroCaps hero,
            List<AppCard> apps,
            PlatformStats platform,
            List<Notification> notifications,
            boolean admin) {
    }

    /** 日志行（跨应用统一形状） */
    public record LogRow(
            long id,
            String time,
            String app,
            String category,
            String action,
            String actor,
            String memoryId,
            String detail,
            String result,
            Integer durationMs,
            Integer tokens,
            String traceId) {
    }

    /** 日志分页结果，附一份不随分页变化的汇总（页面顶部统计条用） */
    public record LogPage(
            List<LogRow> list,
            long total,
            int page,
            int size,
            LogSummary summary) {
    }

    public record LogSummary(long total, long success, long deny, long fail) {
    }
}
