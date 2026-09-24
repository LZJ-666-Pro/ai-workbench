package com.ai.workbench.bank.api;

import com.ai.workbench.bank.auth.AuthInterceptor;
import com.ai.workbench.bank.auth.JwtService.AuthPrincipal;
import com.ai.workbench.bank.service.TransferService;
import com.ai.workbench.bank.service.TransferService.TransferResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    public ConfirmResponse confirm(@RequestBody ConfirmRequest request, HttpServletRequest http) {
        // memoryId 在请求体里，拦截器看不到：这里校验确认单会话归属登录身份，
        // 防止用别人的 confirmId 跨身份动账（如员工确认客户的转账单）
        AuthPrincipal principal =
                (AuthPrincipal) http.getAttribute(AuthInterceptor.ATTR_PRINCIPAL);
        if (principal != null && request.memoryId() != null) {
            String[] parts = request.memoryId().split(":");
            boolean owned = (parts.length == 3 && parts[1].equals(principal.identityId()))
                    || (parts.length == 2 && "zhangsan".equals(principal.identityId()));
            if (!owned) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作其他身份的转账确认单");
            }
        }
        TransferResult result = transferService.confirmOrder(
                request.memoryId() == null || request.memoryId().isBlank()
                        ? "-" : request.memoryId(),
                request.confirmId(),
                "confirm".equals(request.action()));
        return new ConfirmResponse("SUCCESS".equals(result.kind()), result.message());
    }
}
