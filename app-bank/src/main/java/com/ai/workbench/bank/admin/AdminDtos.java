package com.ai.workbench.bank.admin;

import java.math.BigDecimal;
import java.util.List;

/** 管理端只读 DTO 集合：全部为不可变 record，避免返回裸 Map */
public final class AdminDtos {

    private AdminDtos() {
    }

    /** 统一分页包装 */
    public record PageResult<T>(List<T> list, long total, int page, int size) {
    }

    /** 仪表盘概览统计 */
    public record OverviewStats(
            BigDecimal totalBalance,
            long accountCount,
            long personalCount,
            long corporateCount,
            long customerCount,
            long todayTxnCount,
            BigDecimal todayTxnAmount) {
    }

    /** 账户列表行 */
    public record AccountView(
            String accountNo,
            String owner,
            String type, // personal / corporate
            BigDecimal balance,
            long txnCount) {
    }

    /** 流水行（通用：单账户明细与全行流水共用） */
    public record TxnView(
            long id,
            String accountNo,
            String owner,
            BigDecimal amount,
            String description,
            String createdAt) {
    }

    /** AI 转账订单行 */
    public record OrderView(
            long id,
            String confirmId,
            String memoryId,
            String fromAccount,
            String toAccount,
            BigDecimal amount,
            String reason,
            String status,
            String createdAt) {
    }

    /** 审计日志行 */
    public record AuditLogView(
            long id,
            String memoryId,
            String toolName,
            String detail,
            String result,
            String createdAt) {
    }

    // ---------- 客户 360 视图 ----------

    /** 客户列表行：按户名聚合名下账户 */
    public record CustomerView(
            String owner,
            long accountCount,
            BigDecimal totalBalance,
            String type, // personal / corporate / mixed
            List<AccountBrief> accounts) {
    }

    /** 客户名下账户摘要 */
    public record AccountBrief(String owner, String accountNo, BigDecimal balance) {
    }

    /** 客户 360 视图全景：基本信息 + 存贷款口径统计 + 业务轨迹 */
    public record CustomerProfile(
            String owner,
            String type,
            List<AccountBrief> accounts,
            BigDecimal totalBalance,
            long txnCount,
            BigDecimal txnIncome,
            BigDecimal txnExpense,
            long orderCount,
            long orderExecuted,
            BigDecimal orderAmount,
            long auditCount,
            List<TxnView> recentTxns,
            List<OrderView> recentOrders,
            List<AuditLogView> recentAudits) {
    }

    // ---------- 审批中心 ----------

    /** 审批中心统计 + 待审批队列 */
    public record ApprovalBoard(
            long pending,
            long executedToday,
            long cancelled,
            long rejected,
            List<OrderView> pendingOrders) {
    }

    /** 交易旅程：一笔订单从创建到落地的完整轨迹 */
    public record OrderJourney(
            OrderView order,
            List<JourneyNode> timeline) {
    }

    /** 旅程节点 */
    public record JourneyNode(String time, String type, String text, String result) {
    }

    // ---------- 交易限额 ----------

    /** 限额包：服务对象身份 × 交易额度 */
    public record LimitPackage(
            String identityId,
            String displayName,
            String role,
            String boundAccount,
            boolean canTransfer,
            String maxSingle,
            String maxDaily) {
    }

    /** 饼图数据点：账户余额分布 */
    public record BalancePoint(String accountNo, String owner, BigDecimal balance) {
    }

    /** 折线数据点：每日入账/支出 */
    public record DailyFlowPoint(String date, BigDecimal income, BigDecimal expense) {
    }
}
