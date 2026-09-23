package com.ai.workbench.bank.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ai.workbench.bank.admin.AdminDtos;
import com.ai.workbench.bank.admin.BankAdminQueryService;

/**
 * 管理端只读接口：供 /admin 后台（顾客管理、资金管理、可视化、AI 审计）使用。
 * 全部 GET、无任何写操作；分页参数 page 从 1 开始。
 */
@RestController
@RequestMapping("/api/admin")
public class BankAdminController {

    private final BankAdminQueryService query;

    public BankAdminController(BankAdminQueryService query) {
        this.query = query;
    }

    @GetMapping("/overview")
    public AdminDtos.OverviewStats overview() {
        return query.overview();
    }

    @GetMapping("/accounts")
    public AdminDtos.PageResult<AdminDtos.AccountView> accounts(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "all") String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return query.accounts(keyword, type, page, Math.min(size, 100));
    }

    @GetMapping("/accounts/{accountNo}/transactions")
    public AdminDtos.PageResult<AdminDtos.TxnView> accountTransactions(
            @PathVariable String accountNo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return query.accountTransactions(accountNo, page, Math.min(size, 100));
    }

    @GetMapping("/transactions")
    public AdminDtos.PageResult<AdminDtos.TxnView> transactions(
            @RequestParam(defaultValue = "") String accountNo,
            @RequestParam(defaultValue = "all") String direction,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return query.transactions(accountNo, direction, page, Math.min(size, 100));
    }

    @GetMapping("/transfer-orders")
    public AdminDtos.PageResult<AdminDtos.OrderView> transferOrders(
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return query.transferOrders(status, page, Math.min(size, 100));
    }

    @GetMapping("/audit-logs")
    public AdminDtos.PageResult<AdminDtos.AuditLogView> auditLogs(
            @RequestParam(defaultValue = "") String result,
            @RequestParam(defaultValue = "") String toolName,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return query.auditLogs(result, toolName, page, Math.min(size, 100));
    }

    @GetMapping("/stats/balance-distribution")
    public List<AdminDtos.BalancePoint> balanceDistribution() {
        return query.balanceDistribution();
    }

    @GetMapping("/stats/daily-flow")
    public List<AdminDtos.DailyFlowPoint> dailyFlow(@RequestParam(defaultValue = "7") int days) {
        return query.dailyFlow(days);
    }
}
