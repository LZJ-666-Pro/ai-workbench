package com.ai.workbench.bank.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ai.workbench.bank.admin.AdminDtos;
import com.ai.workbench.bank.admin.BankAdminQueryService;
import com.ai.workbench.bank.audit.ToolAuditLogger;
import com.ai.workbench.bank.service.TransferService;

/**
 * 管理端接口：查询全部只读 GET；唯一写路径是审批中心的通过/驳回
 * （复用 TransferService 的 HITL 确认状态机，CAS 幂等 + 过期检查 + 规则复检）。
 */
@RestController
@RequestMapping("/api/admin")
public class BankAdminController {

    private final BankAdminQueryService query;
    private final TransferService transferService;
    private final ToolAuditLogger audit;

    public BankAdminController(BankAdminQueryService query, TransferService transferService,
                               ToolAuditLogger audit) {
        this.query = query;
        this.transferService = transferService;
        this.audit = audit;
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

    // ==================== 客户 360 视图 ====================

    /** 客户列表（按户名聚合名下账户） */
    @GetMapping("/customers")
    public List<AdminDtos.CustomerView> customers() {
        return query.customers();
    }

    /** 客户 360 全景 */
    @GetMapping("/customers/{owner}/profile")
    public ResponseEntity<AdminDtos.CustomerProfile> customerProfile(@PathVariable String owner) {
        AdminDtos.CustomerProfile profile = query.customerProfile(owner);
        return profile == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(profile);
    }

    // ==================== 审批中心 ====================

    /** 审批看板：统计 + 待审批队列 */
    @GetMapping("/approvals")
    public AdminDtos.ApprovalBoard approvals() {
        return query.approvalBoard();
    }

    /** 交易旅程：一笔订单从创建到落地的完整轨迹 */
    @GetMapping("/transfer-orders/{id}/journey")
    public ResponseEntity<AdminDtos.OrderJourney> orderJourney(@PathVariable long id) {
        AdminDtos.OrderJourney journey = query.orderJourney(id);
        return journey == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(journey);
    }

    /**
     * 管理端审批：通过=执行划款，驳回=取消订单。
     * 复用订单原有 memoryId 做身份解析与限额复检（保证对公/个人分档口径不变），
     * 管理端操作本身以 admin:console 记入审计日志。
     */
    @PostMapping("/transfer-orders/{id}/decision")
    public ResponseEntity<AdminDtos.AdminDecision> decide(
            @PathVariable long id, @RequestParam boolean approve) {
        AdminDtos.OrderView order = query.orderById(id);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        if (!"PENDING".equals(order.status())) {
            return ResponseEntity.ok(new AdminDtos.AdminDecision(id, order.status(),
                    "订单 #%d 已处理（%s），无需审批".formatted(id, order.status())));
        }
        TransferService.TransferResult result =
                transferService.confirmOrder(order.memoryId(), order.confirmId(), approve);
        String action = approve ? "通过" : "驳回";
        audit.record("admin:console", "admin.approval",
                "管理端%s订单 #%d: %s -> %s, %.2f 元".formatted(action, id,
                        order.fromAccount(), order.toAccount(), order.amount()),
                result.kind());
        return ResponseEntity.ok(new AdminDtos.AdminDecision(id, result.kind(), result.message()));
    }

    // ==================== 交易限额 ====================

    /** 限额包：各服务对象身份的当前生效限额（代码层风控规则） */
    @GetMapping("/limits")
    public List<AdminDtos.LimitPackage> limits() {
        return query.limitPackages();
    }
}
