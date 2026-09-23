package com.ai.workbench.bank.tool;

import com.ai.workbench.bank.audit.ToolAuditLogger;
import com.ai.workbench.bank.identity.BankIdentity;
import com.ai.workbench.bank.service.Account;
import com.ai.workbench.bank.service.DbBankService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 查询类工具：按服务对象身份过滤数据可见范围——
 * 零售/对公客户只能看本人（本企业）账户，内部员工可看全行。边界处留审计。
 */
@Component
public class AccountTools {

    private final DbBankService bankService;
    private final ToolAuditLogger audit;

    public AccountTools(DbBankService bankService, ToolAuditLogger audit) {
        this.bankService = bankService;
        this.audit = audit;
    }

    @Tool("列出当前登录用户可见的账户，包括账号、户名和余额。用户没有说清账户时先调用此工具")
    public String listAccounts() {
        BankIdentity identity = ToolCallContext.currentIdentity();
        audit.record(ToolCallContext.currentMemoryId(), "listAccounts", "identity=" + identity.id(), "SUCCESS");
        if (identity.isStaff()) {
            return "全行账户：" + bankService.allAccounts();
        }
        return "您的账户：" + bankService.find(identity.boundAccount())
                .map(AccountTools::describe)
                .orElse("暂无账户数据");
    }

    @Tool("根据账号或户名查询账户余额和最近交易记录")
    public String queryAccount(
            @P(value = "账号或户名，例如 62220001 或 张三") String accountOrOwner) {
        BankIdentity identity = ToolCallContext.currentIdentity();
        Optional<Account> found = bankService.find(accountOrOwner);
        if (found.isEmpty()) {
            audit.record(ToolCallContext.currentMemoryId(), "queryAccount",
                    "target=%s".formatted(accountOrOwner), "FAIL");
            return "未找到账户「%s」。".formatted(accountOrOwner)
                    + (identity.isStaff() ? "可先调用 listAccounts 查看全行账户。" : "请核对账号或户名后重试。");
        }
        // 数据权限：客户身份只能查本人/本企业账户，员工可查任意
        if (!identity.isStaff() && !found.get().accountNo().equals(identity.boundAccount())) {
            audit.record(ToolCallContext.currentMemoryId(), "queryAccount",
                    "target=%s".formatted(accountOrOwner), "DENY");
            return "查询被拒绝：%s只能查询%s的账户（%s）。".formatted(
                    identity.displayName(), identity.role(), identity.boundAccount());
        }
        audit.record(ToolCallContext.currentMemoryId(), "queryAccount",
                "target=%s".formatted(accountOrOwner), "SUCCESS");
        Account account = found.get();
        return "账户 %s（%s）余额：%.2f 元。最近交易：\n%s".formatted(
                account.accountNo(),
                account.owner(),
                account.balance(),
                String.join("\n", account.recentTransactions()));
    }

    /** 仅内部员工可见（BankToolProvider 按 STAFF_ONLY 过滤） */
    @Tool("查看全行概况：账户总数、客户数、对公与个人账户数、总余额")
    public String bankOverview() {
        audit.record(ToolCallContext.currentMemoryId(), "bankOverview", "-", "SUCCESS");
        return bankService.overview();
    }

    private static String describe(Account account) {
        return "%s（%s）余额 %.2f 元".formatted(account.accountNo(), account.owner(), account.balance());
    }
}