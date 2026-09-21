package com.ai.workbench.bank.api;

import com.ai.workbench.bank.service.TransferService;
import com.ai.workbench.bank.service.TransferService.TransferResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 转账确认接口：资金变动的唯一入口。前端确认卡片调用；
 * 幂等、过期、规则复检都在 TransferService.confirmOrder 的事务里。
 */
@RestController
@RequestMapping("/api/transfer")
public class TransferConfirmController {

    private final TransferService transferService;

    public TransferConfirmController(TransferService transferService) {
        this.transferService = transferService;
    }

    public record ConfirmRequest(String memoryId, String confirmId, String action) {

    }

    public record ConfirmResponse(boolean ok, String message) {

    }

    @PostMapping("/confirm")
    public ConfirmResponse confirm(@RequestBody ConfirmRequest request) {
        TransferResult result = transferService.confirmOrder(
                request.memoryId() == null || request.memoryId().isBlank()
                        ? "-" : request.memoryId(),
                request.confirmId(),
                "confirm".equals(request.action()));
        return new ConfirmResponse("SUCCESS".equals(result.kind()), result.message());
    }
}
