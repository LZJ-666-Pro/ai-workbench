package com.ai.workbench.bank.tool;

import com.ai.workbench.bank.audit.ToolAuditLogger;
import com.ai.workbench.bank.service.DbBankService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 查询类工具：只读，但在边界层同样留审计（memoryId 来自 ToolCallContext）。
 */
@Component
public class AccountTools {

    private final DbBankService bankService;
    private final ToolAuditLogger audit;

    public AccountTools(DbBankService bankService, ToolAuditLogger audit) {
        this.bankService = bankService;
        this.audit = audit;
    }

    @Tool("列出系统中所有可用账户，包括账号、户名和余额。用户没有说清账户时先调用此工具")
    public String listAccounts() {
        audit.record(ToolCallContext.currentMemoryId(), "listAccounts", "-", "SUCCESS");
        return bankService.allAccounts();
    }

    @Tool("根据账号或户名查询账户余额和最近交易记录")
    public String queryAccount(
            @P(value = "账号或户名，例如 62220001 或 张三") String accountOrOwner) {
        audit.record(ToolCallContext.currentMemoryId(), "queryAccount",
                "target=%s".formatted(accountOrOwner), "SUCCESS");
        return bankService.find(accountOrOwner)
                .map(account -> "账户 %s（%s）余额：%.2f 元。最近交易：\n%s".formatted(
                        account.accountNo(),
                        account.owner(),
                        account.balance(),
                        String.join("\n", account.recentTransactions())))
                .orElse("未找到账户「%s」。当前可用账户：%s".formatted(accountOrOwner, bankService.allAccounts()));
    }
}
