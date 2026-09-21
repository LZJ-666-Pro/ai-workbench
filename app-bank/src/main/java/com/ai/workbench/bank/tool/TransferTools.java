package com.ai.workbench.bank.tool;

import java.math.BigDecimal;

import com.ai.workbench.bank.audit.ToolAuditLogger;
import com.ai.workbench.bank.service.TransferService;
import com.ai.workbench.bank.service.TransferService.TransferResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 转账工具：AI 边界层——只做参数承接、审计与话术包装；
 * 风控、事务、幂等都在 TransferService，不在这里。
 * TODO(Step 3)：审计的会话关联改由确认接口回传 memoryId（@MemoryId 在 @Tool 参数中会被
 * 当作普通参数暴露给模型，不可用；届时用 ToolProvider 方案）。
 */
@Component
public class TransferTools {

    private final TransferService transferService;
    private final ToolAuditLogger audit;

    public TransferTools(TransferService transferService, ToolAuditLogger audit) {
        this.transferService = transferService;
        this.audit = audit;
    }

    @Tool("从当前登录客户的账户向他人转账。调用前先与用户确认收款人和金额")
    public String transfer(
            @P("收款账号或户名") String toAccountOrOwner,
            @P("转账金额，数字，单位元") BigDecimal amount,
            @P(value = "转账附言", required = false) String reason) {
        try {
            TransferResult result = transferService.transfer(toAccountOrOwner, amount, reason);
            audit.record(null, "transfer",
                    "to=%s, amount=%s, reason=%s".formatted(toAccountOrOwner, amount, reason),
                    result.kind());
            return switch (result.kind()) {
                case "DENY", "FAIL" -> result.message() + "（请如实告知用户，不要以任何形式重试，包括调整金额后重试）";
                default -> result.message();
            };
        } catch (Exception e) {
            audit.record(null, "transfer",
                    "to=%s, amount=%s, error=%s".formatted(toAccountOrOwner, amount, e.getMessage()),
                    "FAIL");
            return "转账执行异常，请稍后重试或引导用户联系人工客服。";
        }
    }
}
