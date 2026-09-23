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

    /** 饼图数据点：账户余额分布 */
    public record BalancePoint(String accountNo, String owner, BigDecimal balance) {
    }

    /** 折线数据点：每日入账/支出 */
    public record DailyFlowPoint(String date, BigDecimal income, BigDecimal expense) {
    }
}
