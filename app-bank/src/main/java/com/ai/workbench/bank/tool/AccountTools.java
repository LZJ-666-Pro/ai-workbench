package com.ai.workbench.bank.tool;

import com.ai.workbench.bank.service.MockBankService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 银行 Agent 的工具集。Phase 1 将追加：转账（带 human-in-the-loop 确认单）、
 * 查交易明细、账单汇总等；幂等键与审计日志也在那个阶段落地。
 */
@Component
public class AccountTools {

    private final MockBankService bankService;

    public AccountTools(MockBankService bankService) {
        this.bankService = bankService;
    }

    @Tool("根据账号或户名查询账户余额和最近交易记录")
    public String queryAccount(@P(value = "账号或户名，例如 62220001 或 张三") String accountOrOwner) {
        return bankService.find(accountOrOwner)
                .map(account -> "账户 %s（%s）余额：%.2f 元。最近交易：\n%s".formatted(
                        account.accountNo(),
                        account.owner(),
                        account.balance(),
                        String.join("\n", account.recentTransactions())))
                .orElse("未找到账户「%s」。当前可用账户：%s".formatted(accountOrOwner, bankService.allAccounts()));
    }
}
