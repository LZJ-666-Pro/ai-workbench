package com.ai.workbench.bank.tool;

import java.math.BigDecimal;

import com.ai.workbench.bank.service.TransferService;
import com.ai.workbench.bank.service.TransferService.TransferResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 转账工具（两段式）：模型只能「创建确认单」，不能执行转账——
 * 执行权只在确认接口的 CAS 状态机手里，模型再怎么即兴发挥也动不了钱。
 */
@Component
public class TransferTools {

    private final TransferService transferService;

    public TransferTools(TransferService transferService) {
        this.transferService = transferService;
    }

    @Tool("查询转账确认单的真实状态。用户询问之前发起的转账、卡片不见了、能不能继续确认时，先调用此工具，不要凭对话记忆回答")
    public String queryTransferOrder(
            @P(value = "转账确认码（UUID）；不清楚时可传空，将查询最近一张确认单", required = false) String confirmId) {
        try {
            return transferService.describeOrder(ToolCallContext.currentMemoryId(), confirmId);
        } catch (Exception e) {
            return "查询确认单状态异常，请稍后重试。";
        }
    }

    @Tool("从当前登录客户的账户向他人转账。会先生成一张待确认的转账单，等待用户在确认卡片上操作后才执行")
    public String transfer(
            @P("收款账号或户名") String toAccountOrOwner,
            @P("转账金额，数字，单位元") BigDecimal amount,
            @P(value = "转账附言", required = false) String reason) {
        try {
            TransferResult result = transferService.createPendingOrder(
                    ToolCallContext.currentMemoryId(), toAccountOrOwner, amount, reason);
            return switch (result.kind()) {
                case "DENY", "FAIL" ->
                        result.message() + "（请如实告知用户，不要以任何形式重试，包括调整金额后重试）";
                default ->
                        result.message() + "（请告知用户在页面的确认卡片上操作即可，不要重复创建确认单）";
            };
        } catch (Exception e) {
            return "创建转账确认单异常，请引导用户稍后重试或联系人工客服。";
        }
    }
}
